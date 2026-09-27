"""Post-download output generation.

After a chapter's pages are on disk, optionally produce extra outputs based on the
user-selected OUTPUT checkboxes (persisted in DB meta "output_cbz" / "output_webp"):

    CBZ   — zip the chapter's pages (in page order) into <comic_dir>/<name>_<author>_vol_NN.cbz
            (NN = the chapter's 1-based position in title order, e.g. vol_01, vol_02 …)
    WEBP  — convert any non-webp page files to .webp in place (DB paths updated)

Both default ON. Older builds stored a single "output_mode" value ("CBZ"/"WEBP"/"BOTH"),
which _output_flags() still honours for backwards compatibility.

Everything here is idempotent: re-running on an already-finished chapter skips work
(webp pages are left alone; a cbz is only rebuilt when it's missing or older than its
pages), so switching OUTPUT mode later regenerates outputs without re-downloading.

ensure_outputs() returns a list of short log messages for the caller to emit."""
import os
import re
import zipfile

from . import db


def _safe_name(name: str) -> str:
    name = (name or "").strip()
    name = re.sub(r'[\\/:*?"<>|]', "_", name).strip(" ._")
    return name[:80] or "chapter"


def _clean(s: str) -> str:
    """Sanitize one filename fragment; empty input stays empty (no fallback)."""
    s = re.sub(r'[\\/:*?"<>|]', "_", (s or "").strip()).strip(" ._")
    return s[:80]


def _cbz_filename(slug, cid):
    """'<name>_<author>_vol_NN.cbz' — NN is the chapter's 1-based position in title order."""
    comic = db.get_comic(slug) or {}
    name = _clean(comic.get("name")) or slug
    author = _clean(comic.get("author"))
    chapters = db.all_chapters(slug)
    idx = next((i for i, ch in enumerate(chapters, 1) if ch["chapter_id"] == cid), None)
    vol = f"vol_{idx:02d}" if idx else "vol_00"
    return "_".join([name] + ([author] if author else []) + [vol]) + ".cbz"


def _done_pages(slug, cid):
    """[(page_index, path)] for this chapter's downloaded pages, in page order."""
    out = []
    for i in db.all_images(slug, cid):
        if i["status"] == "done" and i.get("path") and os.path.exists(i["path"]):
            out.append((i["page_index"], i["path"]))
    return out


def _convert_to_webp(path: str) -> str:
    """Convert one image file to WebP beside it, then replace the original.

    Returns the new .webp path, or raises on failure (original left untouched)."""
    from PIL import Image  # lazy — Pillow is only needed when WEBP output is selected
    new_path = os.path.splitext(path)[0] + ".webp"
    with Image.open(path) as im:
        im.save(new_path, "WEBP", quality=85)
    os.remove(path)
    return new_path


def ensure_webp(slug, cid):
    """Convert any non-webp pages of this chapter to webp; update DB paths. Returns #converted."""
    n = 0
    for pi, p in _done_pages(slug, cid):
        if p.lower().endswith(".webp"):
            continue
        try:
            new_path = _convert_to_webp(p)
        except Exception as e:
            print(f"[outputs] webp convert failed {os.path.basename(p)}: {e}")
            continue
        db.update_image_path(slug, cid, pi, new_path)
        n += 1
    return n


def _cbz_stale(cbz_path, pages):
    """True when the cbz is missing or any page file is newer than it."""
    if not os.path.exists(cbz_path):
        return True
    m = os.path.getmtime(cbz_path)
    for _, p in pages:
        try:
            if os.path.getmtime(p) > m:
                return True
        except OSError:
            continue
    return False


def ensure_cbz(slug, cid, dest_dir):
    """Zip this chapter's pages into <comic_dir>/<name>_<author>_vol_NN.cbz.

    Returns (path_or_None, rebuilt_bool)."""
    pages = _done_pages(slug, cid)
    if not pages:
        return None, False
    comic_dir = os.path.dirname(dest_dir)
    cbz_path = os.path.join(comic_dir, _cbz_filename(slug, cid))
    # drop a file recorded under an older naming scheme (only inside this comic's folder)
    old_cbz = (db.get_chapter(slug, cid) or {}).get("cbz_path")
    if old_cbz and os.path.abspath(old_cbz) != os.path.abspath(cbz_path) \
            and os.path.dirname(os.path.abspath(old_cbz)) == os.path.abspath(comic_dir):
        try:
            os.remove(old_cbz)
        except OSError:
            pass
    if not _cbz_stale(cbz_path, pages):
        return cbz_path, False
    with zipfile.ZipFile(cbz_path, "w", zipfile.ZIP_STORED) as z:
        for _, p in pages:
            z.write(p, arcname=os.path.basename(p))
    db.set_chapter_cbz(slug, cid, cbz_path)
    return cbz_path, True


def _output_flags():
    """(cbz_on, webp_on) from the OUTPUT checkboxes; falls back to legacy 'output_mode'."""
    try:
        cbz = db.get_meta("output_cbz")
        webp = db.get_meta("output_webp")
    except Exception:
        return True, True
    if cbz is None and webp is None:
        mode = (db.get_meta("output_mode", "BOTH") or "BOTH").upper()
        return mode in ("CBZ", "BOTH"), mode in ("WEBP", "BOTH")
    return cbz != "0", webp != "0"


def ensure_outputs(slug, cid, dest_dir):
    """Apply the selected OUTPUT options to one finished chapter (idempotent).

    Returns a list of short log messages (may be empty)."""
    cbz_on, webp_on = _output_flags()
    msgs = []
    if webp_on:
        n = ensure_webp(slug, cid)
        if n:
            msgs.append(f"  ⇄ {n} 頁已轉為 WebP")
    if cbz_on:
        p, rebuilt = ensure_cbz(slug, cid, dest_dir)
        if p and rebuilt:
            msgs.append(f"  📦 CBZ：{os.path.basename(p)}")
    return msgs
