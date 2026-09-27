"""SQLite-backed state machine. This is what makes stop/resume lossless:
every comic, chapter and image row carries a status + checksums, all keyed by
stable IDs (path_word / chapter uuid / page index). A restart re-reads these rows
and skips anything already done — nothing is ever re-fetched or lost."""
import sqlite3
import threading
from pathlib import Path

from . import config

_local = threading.local()


def connect() -> sqlite3.Connection:
    """One connection per thread (sqlite connections are not shareable across threads)."""
    conn = getattr(_local, "conn", None)
    if conn is None:
        config.DATA_DIR.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(config.DB_PATH, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")   # allow GUI reader + worker writer concurrently
        conn.execute("PRAGMA synchronous=NORMAL")
        _local.conn = conn
    return conn


def init() -> None:
    c = connect()
    c.executescript(
        """
        CREATE TABLE IF NOT EXISTS comics (
            path_word   TEXT PRIMARY KEY,
            name        TEXT,
            author      TEXT,
            cover_url   TEXT,
            synopsis    TEXT,
            serial_status TEXT DEFAULT '',  -- site status: 連載中 / 已完結 ...
            status      TEXT DEFAULT 'new',     -- new | chapters_done | done | error
            selected    INTEGER DEFAULT 0,       -- user picked it for download
            favorite    INTEGER DEFAULT 0,       -- user starred it (收藏)
            last_seen   TEXT,                    -- ISO date we last saw it in the listing
            updated_at  TEXT
        );

        CREATE TABLE IF NOT EXISTS chapters (
            comic_path_word TEXT,
            chapter_id      TEXT,                -- uuid from /chapter/<uuid>
            title           TEXT,
            url             TEXT,
            status          TEXT DEFAULT 'pending',  -- pending | done | error
            image_count     INTEGER DEFAULT 0,       -- pages actually downloaded (done)
            expected_pages  INTEGER DEFAULT 0,       -- badge total for this chapter (for 核對/progress)
            cbz_path        TEXT,
            updated_at      TEXT,
            PRIMARY KEY (comic_path_word, chapter_id)
        );

        CREATE TABLE IF NOT EXISTS images (
            comic_path_word TEXT,
            chapter_id      TEXT,
            page_index      INTEGER,             -- 0-based order within the chapter
            url             TEXT,                -- canonical webp URL we download
            path            TEXT,                -- local file path
            sha256          TEXT,
            size            INTEGER,
            status          TEXT DEFAULT 'pending',  -- pending | done | error
            updated_at      TEXT,
            PRIMARY KEY (comic_path_word, chapter_id, page_index)
        );

        CREATE INDEX IF NOT EXISTS idx_chapters_status ON chapters(comic_path_word, status);
        CREATE INDEX IF NOT EXISTS idx_images_status   ON images(chapter_id, status);

        CREATE TABLE IF NOT EXISTS kv (
            key   TEXT PRIMARY KEY,
            value TEXT
        );
        """
    )
    # migrate older DBs that predate the serial_status column
    cols = [r[1] for r in c.execute("PRAGMA table_info(comics)")]
    if "serial_status" not in cols:
        c.execute("ALTER TABLE comics ADD COLUMN serial_status TEXT DEFAULT ''")
    # migrate older DBs that predate the favorite (收藏) column
    if "favorite" not in cols:
        c.execute("ALTER TABLE comics ADD COLUMN favorite INTEGER DEFAULT 0")
    # migrate older DBs that predate per-chapter expected_pages (for 核對/progress)
    chcols = [r[1] for r in c.execute("PRAGMA table_info(chapters)")]
    if "expected_pages" not in chcols:
        c.execute("ALTER TABLE chapters ADD COLUMN expected_pages INTEGER DEFAULT 0")
    c.commit()


def set_meta(key: str, value) -> None:
    c = connect()
    c.execute(
        "INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, str(value)),
    )
    c.commit()


def get_meta(key: str, default=None):
    c = connect()
    r = c.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
    return r[0] if r else default


# --- comics -----------------------------------------------------------------
def upsert_comic(path_word: str, name=None, author=None, cover_url=None, synopsis=None, serial_status=None) -> None:
    now = _now()
    c = connect()
    row = c.execute("SELECT 1 FROM comics WHERE path_word=?", (path_word,)).fetchone()
    if row is None:
        c.execute(
            "INSERT INTO comics(path_word,name,author,cover_url,synopsis,serial_status,status,last_seen,updated_at) "
            "VALUES(?,?,?,?,?,?, 'new', ?, ?)",
            (path_word, name, author, cover_url, synopsis, serial_status or "", now, now),
        )
    else:
        # only overwrite fields we actually have this time; keep existing if new value is None
        sets, vals = [], []
        for col, val in (("name", name), ("author", author), ("cover_url", cover_url),
                         ("synopsis", synopsis), ("serial_status", serial_status)):
            if val not in (None, ""):
                sets.append(f"{col}=?")
                vals.append(val)
        sets.append("last_seen=?"); vals.append(now)
        sets.append("updated_at=?"); vals.append(now)
        vals.append(path_word)
        c.execute(f"UPDATE comics SET {', '.join(sets)} WHERE path_word=?", vals)
    c.commit()


def set_selected(path_word: str, selected: bool) -> None:
    c = connect()
    c.execute("UPDATE comics SET selected=? WHERE path_word=?", (1 if selected else 0, path_word))
    c.commit()


def set_favorite(path_word: str, fav: bool) -> None:
    """Star / un-star a comic (收藏)."""
    c = connect()
    c.execute("UPDATE comics SET favorite=? WHERE path_word=?", (1 if fav else 0, path_word))
    c.commit()


def get_comics(search: str = "", limit: int = 300, offset: int = 0, serial_status=None,
               favorite_only=False) -> list:
    c = connect()
    q = f"%{search}%" if search else "%"
    where = "(name LIKE ? OR author LIKE ?)"
    params = [q, q]
    if serial_status:
        where += " AND serial_status=?"
        params.append(serial_status)
    if favorite_only:
        where += " AND favorite=1"
    rows = c.execute(
        f"SELECT * FROM comics WHERE {where} ORDER BY updated_at DESC LIMIT ? OFFSET ?",
        (*params, limit, offset),
    ).fetchall()
    return [dict(r) for r in rows]


def count_comics(search: str = "", serial_status=None, favorite_only=False) -> int:
    c = connect()
    q = f"%{search}%" if search else "%"
    where = "(name LIKE ? OR author LIKE ?)"
    params = [q, q]
    if serial_status:
        where += " AND serial_status=?"
        params.append(serial_status)
    if favorite_only:
        where += " AND favorite=1"
    r = c.execute(f"SELECT COUNT(*) FROM comics WHERE {where}", params).fetchone()
    return int(r[0])


def collection_counts() -> dict:
    """Whole-collection summary counts for the bottom status bar (single aggregate query).

    Buckets partition `total`: pending (not downloaded yet) + done + errored. The serial_*
    fields mirror config.STATUS_LABELS values ("連載中"/"已完結"/"短篇"). `favs` counts starred
    comics (收藏) — a cross-cutting count, not part of the download-status partition."""
    c = connect()
    r = c.execute(
        "SELECT COUNT(*) AS total, "
        "SUM(status='done') AS done, "
        "SUM(status IN ('new','chapters_done')) AS pending, "
        "SUM(status='error') AS errored, "
        "SUM(favorite=1) AS favs, "
        "SUM(serial_status='連載中') AS ongoing, "
        "SUM(serial_status='已完結') AS finished, "
        "SUM(serial_status='短篇') AS oneshot "
        "FROM comics"
    ).fetchone()
    d = dict(r)   # sqlite3.Row has no .items(); convert to a plain dict first
    return {k: (int(v) if v is not None else 0) for k, v in d.items()}


def progress_for_selected():
    """Return (done_chapters, total_chapters) across all selected comics."""
    c = connect()
    r = c.execute(
        "SELECT COUNT(*) AS done, "
        "(SELECT COUNT(*) FROM chapters ch WHERE EXISTS "
        "  (SELECT 1 FROM comics cm WHERE cm.path_word=ch.comic_path_word AND cm.selected=1)) AS total "
        "FROM chapters ch WHERE ch.status='done' AND EXISTS "
        "  (SELECT 1 FROM comics cm WHERE cm.path_word=ch.comic_path_word AND cm.selected=1)"
    ).fetchone()
    return int(r["done"]), int(r["total"])


def comic_status_map() -> dict:
    """Per-comic download state for the list, in two aggregate queries (no per-row round-trips).

    Returns {path_word: {"done","total","ch_done","ch_total","ch_err"}} where done/total are
    image counts and ch_* are chapter counts by status. Used once per tree refresh."""
    c = connect()
    out = {}
    blank = lambda: {"done": 0, "total": 0, "ch_done": 0, "ch_total": 0, "ch_err": 0}
    for r in c.execute(
        "SELECT comic_path_word pw, SUM(status='done') done, COUNT(*) total "
        "FROM images GROUP BY comic_path_word"
    ):
        d = out.setdefault(r["pw"], blank())
        d["done"] = int(r["done"]); d["total"] = int(r["total"])
    for r in c.execute(
        "SELECT comic_path_word pw, SUM(status='done') cd, SUM(status='error') ce, COUNT(*) ct "
        "FROM chapters GROUP BY comic_path_word"
    ):
        d = out.setdefault(r["pw"], blank())
        d["ch_done"] = int(r["cd"]); d["ch_err"] = int(r["ce"]); d["ch_total"] = int(r["ct"])
    return out


def comic_progress_one(comic_path_word: str) -> dict:
    """Lightweight per-comic progress (scoped to one comic) for live row updates."""
    c = connect()
    r = c.execute(
        "SELECT SUM(status='done') d, COUNT(*) t FROM images WHERE comic_path_word=?",
        (comic_path_word,),
    ).fetchone()
    r2 = c.execute(
        "SELECT SUM(status='done') cd, SUM(status='error') ce, COUNT(*) ct FROM chapters WHERE comic_path_word=?",
        (comic_path_word,),
    ).fetchone()
    return {"done": int(r["d"] or 0), "total": int(r["t"] or 0),
            "ch_done": int(r2["cd"] or 0), "ch_err": int(r2["ce"] or 0), "ch_total": int(r2["ct"] or 0)}


def get_comic(path_word: str):
    c = connect()
    r = c.execute("SELECT * FROM comics WHERE path_word=?", (path_word,)).fetchone()
    return dict(r) if r else None


def selected_path_words() -> list:
    c = connect()
    rows = c.execute("SELECT path_word FROM comics WHERE selected=1 ORDER BY updated_at DESC").fetchall()
    return [r["path_word"] for r in rows]


def downloaded_path_words() -> list:
    """Path words of the comics that are fully on disk — same definition as the GUI's green row:
    status 'done', OR every tracked chapter is done (covers comics finished before the
    status field was set). Used by 檢查更新 to pick which comics get an update check."""
    c = connect()
    rows = c.execute(
        "SELECT path_word FROM comics WHERE status='done' "
        "UNION SELECT comic_path_word FROM chapters GROUP BY comic_path_word "
        "HAVING COUNT(*) > 0 AND SUM(status='done') = COUNT(*)"
    ).fetchall()
    return [r["path_word"] for r in rows]


def clear_all() -> None:
    """Wipe every comic, chapter and image row (used by the 'clear all' action)."""
    c = connect()
    c.execute("DELETE FROM comics")
    c.execute("DELETE FROM chapters")
    c.execute("DELETE FROM images")
    # reset the listing resume point so the next refresh starts from scratch
    c.execute(
        "INSERT INTO kv(key,value) VALUES('listing_next_offset','0') "
        "ON CONFLICT(key) DO UPDATE SET value='0'"
    )
    c.commit()


def clear_downloads_state() -> None:
    """Reset all *download* state but keep the comic/chapter catalog (no re-crawl needed).

    Used by '下載清除': the caller deletes files on disk; this drops every image row and puts
    each chapter back to pending so a fresh download starts clean, while the list of comics and
    their chapters stays intact.
    """
    c = connect()
    c.execute("DELETE FROM images")
    c.execute(
        "UPDATE chapters SET status='pending', image_count=0, expected_pages=0, cbz_path=NULL"
    )
    # finished/errored comics become 'ready to download' again; never touch 'new'/'chapters_done'
    c.execute("UPDATE comics SET status='chapters_done' WHERE status IN ('done','error')")
    c.commit()


def reset_comic_downloads(comic_path_word: str) -> None:
    """Reset ONE comic's download state so it fully re-downloads, keeping its existing folder.

    Image rows are kept (with their paths) and just set back to pending — this both forces a
    fresh download AND lets the engine reuse the same on-disk folder (no orphaned files)."""
    c = connect()
    now = _now()
    c.execute(
        "UPDATE images SET status='pending', updated_at=? WHERE comic_path_word=?",
        (now, comic_path_word),
    )
    c.execute(
        "UPDATE chapters SET status='pending', image_count=0, expected_pages=0, updated_at=? "
        "WHERE comic_path_word=?",
        (now, comic_path_word),
    )
    c.commit()


# --- chapters ----------------------------------------------------------------
def add_chapters(comic_path_word: str, chapters: list) -> int:
    """chapters: list of (chapter_id, title, url).

    Idempotent upsert. New rows are inserted as 'pending'. Existing rows keep their
    status EXCEPT errored ones, which are reset to 'pending' so a re-render retries them.
    Returns the number of chapters now tracked for this comic."""
    now = _now()
    c = connect()
    for cid, title, url in chapters:
        exists = c.execute(
            "SELECT status FROM chapters WHERE comic_path_word=? AND chapter_id=?",
            (comic_path_word, cid),
        ).fetchone()
        if exists is None:
            c.execute(
                "INSERT INTO chapters(comic_path_word, chapter_id, title, url, status, updated_at) "
                "VALUES(?,?,?,?,'pending',?)",
                (comic_path_word, cid, title, url, now),
            )
        else:
            new_status = "pending" if exists["status"] == "error" else exists["status"]
            c.execute(
                "UPDATE chapters SET title=?, url=?, status=?, updated_at=? "
                "WHERE comic_path_word=? AND chapter_id=?",
                (title, url, new_status, now, comic_path_word, cid),
            )
    c.commit()
    return len(chapters)


def pending_chapters(comic_path_word: str) -> list:
    c = connect()
    rows = c.execute(
        "SELECT * FROM chapters WHERE comic_path_word=? AND status='pending' ORDER BY title",
        (comic_path_word,),
    ).fetchall()
    return [dict(r) for r in rows]


def reset_error_chapters(comic_path_word: str) -> None:
    """Reset errored chapters and their images back to 'pending' so a re-run retries them.

    Called once per comic at the start of processing — this is what makes 'press Start
    again after a failure' recover without having to re-render the detail page."""
    c = connect()
    now = _now()
    c.execute(
        "UPDATE chapters SET status='pending', updated_at=? WHERE comic_path_word=? AND status='error'",
        (now, comic_path_word),
    )
    c.execute(
        "UPDATE images SET status='pending', updated_at=? WHERE comic_path_word=? AND status='error'",
        (now, comic_path_word),
    )
    c.commit()


def all_chapters(comic_path_word: str) -> list:
    c = connect()
    rows = c.execute(
        "SELECT * FROM chapters WHERE comic_path_word=? ORDER BY title", (comic_path_word,)
    ).fetchall()
    return [dict(r) for r in rows]


def set_chapter_status(comic_path_word: str, chapter_id: str, status: str, image_count=0, cbz_path=None) -> None:
    now = _now()
    c = connect()
    if cbz_path is not None:
        c.execute(
            "UPDATE chapters SET status=?, image_count=?, cbz_path=?, updated_at=? WHERE comic_path_word=? AND chapter_id=?",
            (status, image_count, cbz_path, now, comic_path_word, chapter_id),
        )
    else:
        c.execute(
            "UPDATE chapters SET status=?, updated_at=? WHERE comic_path_word=? AND chapter_id=?",
            (status, now, comic_path_word, chapter_id),
        )
    c.commit()


def set_chapter_expected(comic_path_word: str, chapter_id: str, n) -> None:
    """Store the badge page total for a chapter (used for progress + post-download 核對)."""
    c = connect()
    c.execute(
        "UPDATE chapters SET expected_pages=? WHERE comic_path_word=? AND chapter_id=?",
        (int(n or 0), comic_path_word, chapter_id),
    )
    c.commit()


def set_comic_status(path_word: str, status: str) -> None:
    c = connect()
    c.execute("UPDATE comics SET status=?, updated_at=? WHERE path_word=?", (status, _now(), path_word))
    c.commit()


# --- images ------------------------------------------------------------------
def add_images(comic_path_word: str, chapter_id: str, urls: list) -> int:
    """urls in reading order.

    Upsert by page_index. Done pages keep their file; errored pages are reset to
    'pending' for retry. Any previously-stored page beyond the new count is dropped
    (the chapter's content changed). Returns the number of pages now tracked."""
    now = _now()
    c = connect()
    n = len(urls)
    for i, u in enumerate(urls):
        exists = c.execute(
            "SELECT status FROM images WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
            (comic_path_word, chapter_id, i),
        ).fetchone()
        if exists is None:
            c.execute(
                "INSERT INTO images(comic_path_word, chapter_id, page_index, url, status, updated_at) "
                "VALUES(?,?,?,?,'pending',?)",
                (comic_path_word, chapter_id, i, u, now),
            )
        else:
            new_status = "pending" if exists["status"] == "error" else exists["status"]
            c.execute(
                "UPDATE images SET url=?, status=?, updated_at=? "
                "WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
                (u, new_status, now, comic_path_word, chapter_id, i),
            )
    # drop stale pages beyond the current count
    c.execute(
        "DELETE FROM images WHERE comic_path_word=? AND chapter_id=? AND page_index>=?",
        (comic_path_word, chapter_id, n),
    )
    c.commit()
    return n


def pending_images(comic_path_word: str, chapter_id: str) -> list:
    c = connect()
    rows = c.execute(
        "SELECT * FROM images WHERE comic_path_word=? AND chapter_id=? AND status='pending' ORDER BY page_index",
        (comic_path_word, chapter_id),
    ).fetchall()
    return [dict(r) for r in rows]


def all_images(comic_path_word: str, chapter_id: str) -> list:
    c = connect()
    rows = c.execute(
        "SELECT * FROM images WHERE comic_path_word=? AND chapter_id=? ORDER BY page_index",
        (comic_path_word, chapter_id),
    ).fetchall()
    return [dict(r) for r in rows]


def get_chapter(comic_path_word: str, chapter_id: str):
    """Return one chapter row as a dict (or None)."""
    c = connect()
    r = c.execute(
        "SELECT * FROM chapters WHERE comic_path_word=? AND chapter_id=?",
        (comic_path_word, chapter_id),
    ).fetchone()
    return dict(r) if r else None


def first_image_path(comic_path_word: str):
    """Return the local path of any already-downloaded image for this comic (or None).

    Used to keep a comic's existing folder stable when the folder-naming scheme changes —
    only brand-new downloads get the new 'Name - Author' layout.
    """
    c = connect()
    r = c.execute(
        "SELECT path FROM images WHERE comic_path_word=? AND path IS NOT NULL LIMIT 1",
        (comic_path_word,),
    ).fetchone()
    return r["path"] if r else None


def mark_image_done(comic_path_word: str, chapter_id: str, page_index: int, path: str, sha256: str, size: int) -> None:
    c = connect()
    c.execute(
        "UPDATE images SET status='done', path=?, sha256=?, size=?, updated_at=? "
        "WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
        (path, sha256, size, _now(), comic_path_word, chapter_id, page_index),
    )
    c.commit()


def mark_image_error(comic_path_word: str, chapter_id: str, page_index: int) -> None:
    c = connect()
    c.execute(
        "UPDATE images SET status='error', updated_at=? WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
        (_now(), comic_path_word, chapter_id, page_index),
    )
    c.commit()


def update_image_path(comic_path_word: str, chapter_id: str, page_index: int, path: str) -> None:
    """Point a done image row at a new local file (e.g. after webp conversion)."""
    c = connect()
    c.execute(
        "UPDATE images SET path=?, updated_at=? WHERE comic_path_word=? AND chapter_id=? AND page_index=?",
        (path, _now(), comic_path_word, chapter_id, page_index),
    )
    c.commit()


def set_chapter_cbz(comic_path_word: str, chapter_id: str, cbz_path: str) -> None:
    """Record the generated .cbz path for a chapter (status untouched)."""
    c = connect()
    c.execute(
        "UPDATE chapters SET cbz_path=?, updated_at=? WHERE comic_path_word=? AND chapter_id=?",
        (cbz_path, _now(), comic_path_word, chapter_id),
    )
    c.commit()


def _now() -> str:
    from datetime import datetime
    return datetime.now().isoformat(timespec="seconds")
