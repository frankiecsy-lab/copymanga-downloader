"""Download chapter page images over plain HTTP (image hosts are not WAF-gated).

The site serves each page as <base>.c1500x.webp or <base>.c1500x.jpg. We prefer the
webp variant (smaller); if it 404s we fall back to whatever URL the browser actually
requested. Each file is checksummed so resume can verify integrity and skip re-fetch."""
import hashlib
import os
import re
import time

import requests

from . import config

_SIZE_RE = re.compile(r"\.c\d+x\.(webp|jpg|jpeg|png)$", re.I)


def _to_webp(url: str):
    """Return a webp variant of url if it has a recognizable size suffix, else None."""
    base = url.split("?")[0]
    m = _SIZE_RE.search(base)
    if not m:
        return None
    return f"{base[:m.start()]}.c1500x.webp"


def download_image(session, url, dest_dir, page_index):
    """Download one image to dest_dir/<page_index+1:03d>.<ext>.

    Returns (path, sha256, size) on success or raises RuntimeError after retries."""
    os.makedirs(dest_dir, exist_ok=True)

    candidates = []
    webp = _to_webp(url)
    if webp:
        candidates.append(webp)
    candidates.append(url.split("?")[0])

    last_err = None
    for cand in candidates:
        low = cand.lower()
        ext = ".webp" if low.endswith(".webp") else (".jpg" if low.endswith((".jpg", ".jpeg")) else ".png")
        dest = os.path.join(dest_dir, f"{page_index + 1:03d}{ext}")
        for attempt in range(config.DOWNLOAD_RETRIES):
            try:
                r = session.get(cand, headers={"User-Agent": config.USER_AGENT},
                                timeout=config.DOWNLOAD_TIMEOUT)
                if r.status_code == 200 and len(r.content) > 0:
                    with open(dest, "wb") as f:
                        f.write(r.content)
                    sha = hashlib.sha256(r.content).hexdigest()
                    return dest, sha, len(r.content)
                last_err = f"HTTP {r.status_code}"
            except requests.RequestException as e:
                last_err = str(e)
            time.sleep(1.0 + attempt)

    raise RuntimeError(f"failed to download image after retries: {url} ({last_err})")


def make_session():
    s = requests.Session()
    s.headers.update({"User-Agent": config.USER_AGENT})
    return s
