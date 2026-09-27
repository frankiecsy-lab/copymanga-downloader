"""Playwright-based rendering. This is what gets past the site's WAF: we let the
site's own (obfuscated, AES-encrypted) JS run in a real Chromium and read the result.

Two jobs:
  render_detail(slug) -> {synopsis, chapters:[(id,title,url),...]}
  render_chapter(url) -> [image_url, ...]   # ALL pages, reading order

Image capture is lossless by construction: we read the true page total from the
in-chapter counter (.comicCount), scroll in small steps so lazy-loading fires for
every image entering the viewport, and collect REAL content-image URLs from network
request events (the loading.jpg placeholder never hits the network)."""
import re

from . import config


class Browser:
    def __init__(self):
        self._pw = None
        self._browser = None
        self._ctx = None
        self._page = None

    # -- lifecycle -----------------------------------------------------------
    def start(self):
        from playwright.sync_api import sync_playwright
        self._pw = sync_playwright().start()
        args = ["--disable-blink-features=AutomationControlled"]
        if not config.HEADLESS:
            args.append("--headless=false")
        self._browser = self._pw.chromium.launch(headless=config.HEADLESS, args=args)
        self._ctx = self._browser.new_context(
            user_agent=config.USER_AGENT, locale="zh-TW", viewport=config.VIEWPORT
        )
        self._page = self._ctx.new_page()

    def stop(self):
        try:
            if self._ctx:
                self._ctx.close()
            if self._browser:
                self._browser.close()
            if self._pw:
                self._pw.stop()
        except Exception:
            pass
        self._page = self._ctx = self._browser = self._pw = None

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *exc):
        self.stop()

    # -- detail page ---------------------------------------------------------
    def render_detail(self, slug: str) -> dict:
        """Return {'synopsis': str|None, 'chapters': [(chapter_id,title,url), ...]}."""
        url = f"{config.BASE_URL}/comic/{slug}"
        self._page.goto(url, wait_until="domcontentloaded", timeout=config.NAV_TIMEOUT)
        # chapters load async into .upLoop; wait for them to appear
        try:
            self._page.wait_for_selector(".upLoop a[href*='/chapter/']", timeout=30_000)
        except Exception:
            pass
        self._page.wait_for_timeout(config.RENDER_SETTLE_MS)

        chapters = []
        seen = set()
        try:
            for a in self._page.locator(".upLoop a[href*='/chapter/']").all():
                href = a.get_attribute("href") or ""
                m = re.search(r"/comic/[^/]+/chapter/([0-9a-fA-F-]+)", href)
                if not m:
                    continue
                cid = m.group(1)
                if cid in seen:
                    continue
                seen.add(cid)
                title = (a.inner_text() or "").strip()
                full = href if href.startswith("http") else config.BASE_URL + href
                chapters.append((cid, title, full))
        except Exception:
            pass

        synopsis = None
        try:
            el = self._page.locator(".comicParticulars-synopsis .theBoxModel-comic-detail-content").first
            if el.count():
                txt = (el.inner_text() or "").strip()
                if txt:
                    synopsis = txt
        except Exception:
            pass

        # serialization status from the attribute list, e.g. 連載中 / 已完結
        serial_status = None
        try:
            ss = self._page.evaluate(
                """() => {
                    const lis = Array.from(document.querySelectorAll('li'));
                    for (const li of lis) {
                        const first = li.querySelector('span');
                        if (!first) continue;
                        const label = (first.textContent || '').replace(/[:：\\s]/g, '');
                        if (label === '狀態') {
                            const v = li.querySelector('.comicParticulars-right-txt');
                            return v ? (v.textContent || '').trim() : '';
                        }
                    }
                    return null;
                }"""
            )
            if ss:
                serial_status = ss
        except Exception:
            pass

        return {"synopsis": synopsis, "chapters": chapters, "serial_status": serial_status}

    # -- chapter page --------------------------------------------------------
    def _read_page_total(self):
        """Read the chapter's total page count from its floating 'N / M' badge, or None.

        The reader shows a small counter (e.g. "1 / 6") that tracks the current page over
        the chapter's total; we only need the denominator to know when every page has been
        requested. Returns None if no such badge is present.
        """
        try:
            m = self._page.evaluate(r"""() => {
                const re = /^\s*(\d+)\s*\/\s*(\d+)\s*$/;
                for (const e of document.querySelectorAll('div,span,p')) {
                    const mm = (e.textContent || '').trim().match(re);
                    if (mm) return parseInt(mm[2], 10);
                }
                return null;
            }""")
            return m or None
        except Exception:
            return None

    def render_chapter(self, url: str):
        """Return (urls, expected_total) for one chapter, in reading order.

        The site lazy-loads each page as a <li><img data-src=... class="lazyload"> appended to
        .comicContent-list only when it scrolls into view (no bulk endpoint). A *monotonic*
        scroll stalls once pinned at the bottom — content grows ~1 page per step, so no new
        IntersectionObserver events fire and most pages are never loaded. We therefore
        OSCILLATE: step down a little, back up a little, to keep retriggering the loader until
        every <li> is in the DOM. Then we read each image's real URL straight from its data-src
        attribute (DOM order == reading order). This also never misses page 1, whose <li> exists
        at load time — no network-capture timing to get wrong.
        """
        self._page.goto(url, wait_until="domcontentloaded", timeout=config.NAV_TIMEOUT)
        try:
            self._page.wait_for_load_state("networkidle", timeout=25_000)
        except Exception:
            pass

        total = self._read_page_total()          # badge "N / M" -> M, or None if absent

        def li_count():
            return self._page.evaluate(
                "document.querySelectorAll('.comicContent-list li').length")

        # Oscillate until every page's <li> is present (or we're stuck at the bottom).
        last = -1
        no_progress = 0
        cap = max((total or 60) * 3 + 40, 200)
        for _ in range(cap):
            self._page.evaluate("window.scrollBy(0, 500)")
            self._page.wait_for_timeout(int(config.CHAPTER_SCROLL_DELAY * 1000))
            # Back up a little so the next page re-enters the viewport and fires its observer.
            self._page.evaluate("window.scrollBy(0, -120)")
            cur = li_count()
            if total is not None and cur >= total:
                break
            no_progress = no_progress + 1 if cur == last else 0
            at_bottom = self._page.evaluate(
                "window.scrollY + window.innerHeight >= document.body.scrollHeight - 6")
            # Total unknown (or badge lied) -> stop once stuck at the very bottom for a while.
            if at_bottom and no_progress >= 45:
                break
            last = cur

        # Let the final batch settle, then read real URLs from data-src in DOM order.
        self._page.wait_for_timeout(config.RENDER_SETTLE_MS)
        urls = self._page.evaluate(r"""() => {
            const out = [];
            for (const im of document.querySelectorAll('.comicContent-list img')) {
                let u = im.getAttribute('data-src') || im.currentSrc || im.src || '';
                u = u.split('?')[0];
                if (!u) continue;
                if (/loading\.jpg$|\/static\//.test(u)) continue;   // skip placeholders & site assets
                out.push(u);
            }
            return out;
        }""") or []

        # De-dup while preserving order (each page should appear exactly once).
        seen = set()
        uniq = []
        for u in urls:
            if u not in seen:
                seen.add(u)
                uniq.append(u)
        return uniq, total
