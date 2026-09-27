"""Enumerate every comic via /comics?offset=&limit=. This endpoint is plain HTML
(no WAF), so we use requests. Each page embeds its items as a JSON-ish array in the
`list="..."` attribute of <div class="exemptComic-box" total="N">."""
import html
import json
import re

import requests

from . import config, db

_LIST_RE = re.compile(r'exemptComic-box[^>]*?total="(\d+)"[^>]*?list="([^"]+)"', re.S)


def _status_label(status):
    """Map the site's status int (0=連載中 1=已完結 2=短篇) to its label, else None."""
    if isinstance(status, int) and status in config.STATUS_LABELS:
        return config.STATUS_LABELS[status]
    return None


def _parse_items(raw: str):
    """raw is a python dict literal (single quotes). Convert to JSON and parse."""
    try:
        data = json.loads(html.unescape(raw).replace("'", '"'))
        out = []
        for d in data:
            author = ""
            if d.get("author"):
                author = ", ".join(a.get("name", "") for a in d["author"] if a.get("name"))
            out.append({
                "path_word": d.get("path_word"),
                "name": (d.get("name") or "").strip(),
                "author": author,
                "cover_url": d.get("cover"),
                "status": d.get("status"),
            })
        return [o for o in out if o["path_word"]]
    except Exception:
        # fallback: just grab path_words so we at least don't lose comics
        pws = re.findall(r"'path_word':\s*'([^']+)'", html.unescape(raw))
        names = re.findall(r"'name':\s*'([^']*)'", html.unescape(raw))
        covers = re.findall(r"'cover':\s*'([^']*)'", html.unescape(raw))
        statuses = [int(s) for s in re.findall(r"'status':\s*(\d+)", html.unescape(raw))]
        out = []
        for i, pw in enumerate(pws):
            out.append({
                "path_word": pw,
                "name": names[i] if i < len(names) else "",
                "author": "",
                "cover_url": covers[i] if i < len(covers) else None,
                "status": statuses[i] if i < len(statuses) else None,
            })
        return out


def crawl_listing(on_page=None, stop_check=lambda: False, start_offset=0, status=None):
    """Walk every page of the listing, upserting comics into the DB.

    on_page(page_no, offset, n_items, err) is called after each page (for GUI progress).
    stop_check() lets a caller halt mid-crawl; start_offset resumes from a saved point.
    status: optional site status int (0/1/2) to filter server-side at extraction time —
        when set, only that status's comics are pulled in. None = all statuses.
    Returns {"stopped": bool, "next_offset": int, "total": int|None} — next_offset is the
    resume point when stopped early, else 0 (a fresh crawl next time)."""
    import time
    db.init()
    session = requests.Session()
    session.headers.update({"User-Agent": config.USER_AGENT})
    limit = config.LIST_LIMIT
    offset = (int(start_offset) // limit) * limit   # align to a page boundary
    total = None
    pages = 0
    stopped = False
    while True:
        if stop_check():
            stopped = True
            break
        url = f"{config.BASE_URL}/comics?ordering={config.LIST_ORDERING}&offset={offset}&limit={limit}"
        if status is not None:
            url += f"&status={int(status)}"
        try:
            r = session.get(url, timeout=30)
            r.raise_for_status()
        except requests.RequestException as e:
            if on_page:
                on_page(pages, offset, 0, f"listing error @offset {offset}: {e}")
            break
        m = _LIST_RE.search(r.text)
        if not m:
            break
        total = int(m.group(1))
        items = _parse_items(m.group(2))
        for it in items:
            db.upsert_comic(it["path_word"], name=it["name"], author=it["author"],
                            cover_url=it["cover_url"], serial_status=_status_label(it.get("status")))
        pages += 1
        if on_page:
            on_page(pages, offset, len(items), None)
        # stop when we've covered the whole set or a page came back empty
        if not items or (total is not None and offset + limit >= total):
            break
        offset += limit
        time.sleep(config.LIST_DELAY)
    return {"stopped": stopped, "next_offset": (offset if stopped else 0), "total": total}
