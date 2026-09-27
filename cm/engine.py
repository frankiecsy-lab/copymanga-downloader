"""Download orchestrator. Runs in a worker thread and drives each selected comic
through the state machine:

    detail (chapters+synopsis) -> per chapter: render images -> download pages

Every unit of work is persisted to SQLite as it completes, and a stop flag is checked
between every chapter AND between every image. Stopping just halts the loop; on the
next run the worker re-reads the DB and skips anything already 'done' — so stopping
mid-way and resuming never loses or duplicates work."""
import os
import re
import threading
import time

from . import config, db, outputs
from .browser import Browser
from .download import download_image, make_session


def _safe(name: str) -> str:
    name = (name or "").strip()
    name = re.sub(r'[\\/:*?"<>|]', "_", name).strip(" ._")
    return name[:80] or "untitled"


class Engine:
    def __init__(self, on_event=None):
        self.on_event = on_event          # fn(type, payload)
        self._stop = threading.Event()
        self._thread = None
        self.running = False

    # -- control -------------------------------------------------------------
    def request_stop(self):
        self._stop.set()

    @property
    def stop_requested(self):
        return self._stop.is_set()

    def start(self, slugs: list):
        if self.running:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, args=(slugs,), daemon=True)
        self.running = True
        self._thread.start()

    # -- event helper --------------------------------------------------------
    def _emit(self, etype, **payload):
        if self.on_event:
            try:
                self.on_event(etype, payload)
            except Exception:
                pass

    def _log(self, msg):
        self._emit("log", message=msg)

    # -- main loop -----------------------------------------------------------
    def _run(self, slugs):
        db.init()
        session = make_session()
        try:
            with Browser() as br:
                for slug in slugs:
                    if self._stop.is_set():
                        break
                    comic = db.get_comic(slug)
                    if not comic:
                        continue
                    name = comic["name"] or slug
                    author = comic.get("author") or ""
                    self._log(f"▶ {name}")
                    self._emit("downloading", name=name, slug=slug)   # -> GUI "正在下載" panel
                    try:
                        self._process_comic(br, session, slug, name, author, comic.get("serial_status") or "")
                    except Exception as e:
                        db.set_comic_status(slug, "error")
                        self._log(f"  ✗ {name} 出錯：{e}")
        finally:
            self.running = False
            self._emit("all_done", stopped=self._stop.is_set())

    def _process_comic(self, br, session, slug, name, author="", serial_status=""):
        # Folder naming: new downloads go under a top-level status folder —
        #   <StatusLabel>/<Name> - <Author>/...  (e.g. 連載中/某漫 - 作者/)
        # A comic that already has files on disk keeps its EXISTING folder (relative to
        # downloads/, so any status sub-folder is preserved) so a resume/re-download never
        # orphans files. Unknown status -> the STATUS_UNKNOWN fallback folder. Fall back to
        # path_word if the title is empty.
        existing = db.first_image_path(slug)
        if existing:
            try:
                comic_dir = os.path.relpath(os.path.dirname(os.path.dirname(existing)), config.DOWNLOADS_DIR)
            except ValueError:
                comic_dir = slug
        else:
            label = serial_status or config.STATUS_UNKNOWN
            folder_name = f"{name} - {author}" if author else name
            comic_dir = os.path.join(_safe(label), _safe(folder_name) or slug)

        # 0) retry any chapters that errored on a previous run (no re-render needed)
        db.reset_error_chapters(slug)

        # 1) chapters + synopsis + status (only if we don't have the chapter list yet)
        existing = db.all_chapters(slug)
        if not existing:
            self._log(f"  載入章節列表…")
            detail = br.render_detail(slug)
            db.upsert_comic(slug, synopsis=detail.get("synopsis"), serial_status=detail.get("serial_status"))
            n = db.add_chapters(slug, detail["chapters"])
            db.set_comic_status(slug, "chapters_done")
            self._log(f"  共 {n} 章")

        # 2) each pending chapter (each one transitions to done/error, so no infinite loop)
        while True:
            if self._stop.is_set():
                return
            pend = db.pending_chapters(slug)
            if not pend:
                break
            ch = pend[0]
            self._log(f"  · {ch['title']}")
            try:
                self._process_chapter(br, session, slug, comic_dir, ch)
            except Exception as e:
                db.set_chapter_status(slug, ch["chapter_id"], "error")
                self._log(f"    ✗ 章節出錯：{e}")

        # 3) mark comic done if no pending/error chapters remain
        remaining = [c for c in db.all_chapters(slug) if c["status"] != "done"]
        if not remaining:
            db.set_comic_status(slug, "done")
            self._log(f"  ✓ {name} 完成")

        # 3b) ensure the selected OUTPUT (cbz/webp) exists for every done chapter — idempotent;
        # also covers switching OUTPUT mode after a comic was already downloaded.
        for ch in db.all_chapters(slug):
            if ch["status"] != "done":
                continue
            dest = os.path.join(config.DOWNLOADS_DIR, comic_dir, _safe(ch["title"]))
            for m in outputs.ensure_outputs(slug, ch["chapter_id"], dest):
                self._log(m)

    def _process_chapter(self, br, session, slug, comic_dir, ch):
        cid = ch["chapter_id"]
        # Re-render when we have no pages yet, OR when a previous render came up short of the
        # badge total (a missing page) — re-rendering lets us try to load the gap. If all images
        # are already present (e.g. stopped after download), skip the render and just finish.
        imgs = db.all_images(slug, cid)
        exp_now = db.get_chapter(slug, cid).get("expected_pages") or 0
        if not imgs or (exp_now and len(imgs) < exp_now):
            urls, expected = br.render_chapter(ch["url"])
            if not urls:
                # Empty render — usually the site is down / in maintenance right now. Mark it
                # error (NOT done!) so a later run retries instead of silently "completing" 0 pages.
                db.set_chapter_status(slug, cid, "error")
                self._log("    ✗ 章節渲染為空（網站可能維護中）— 稍後重試")
                return
            n = db.add_images(slug, cid, urls)
            if expected:
                db.set_chapter_expected(slug, cid, expected)
            if expected and len(urls) < expected:
                self._log(f"    ⚠ 抓到 {len(urls)} / 預期 {expected} 頁（可能未載完）")
            else:
                self._log(f"    {n} 頁")
            time.sleep(config.CHAPTER_RENDER_DELAY)

        # download any pending images into the comic/chapter folder (check stop between each)
        dest_dir = os.path.join(config.DOWNLOADS_DIR, comic_dir, _safe(ch["title"]))
        while True:
            if self._stop.is_set():
                return
            pend = db.pending_images(slug, cid)
            if not pend:
                break
            img = pend[0]
            try:
                path, sha, size = download_image(session, img["url"], dest_dir, img["page_index"])
                db.mark_image_done(slug, cid, img["page_index"], path, sha, size)
            except Exception as e:
                db.mark_image_error(slug, cid, img["page_index"])
                self._log(f"    ✗ 第{img['page_index']+1}頁：{e}")
            time.sleep(config.IMAGE_FETCH_DELAY)

        # images-only: done when every page downloaded; otherwise mark error so this run
        # moves on (a re-run resets errored pages and retries them).
        all_imgs = db.all_images(slug, cid)
        failed = [i for i in all_imgs if i["status"] != "done"]

        # 核對頁數: compare what we actually have against the badge total. A shortfall (site
        # flaked mid-chapter, lazy-load gap, etc.) is treated as an error so it retries later —
        # never silently marked done with missing pages.
        expected = db.get_chapter(slug, cid).get("expected_pages") or 0
        if not failed and expected and len(all_imgs) < expected:
            db.set_chapter_status(slug, cid, "error", image_count=len(all_imgs))
            self._log(f"    ⚠ 核對不符：{len(all_imgs)} / {expected} 頁 — 再按「開始下載」可重試")
        elif not failed:
            db.set_chapter_status(slug, cid, "done", image_count=len(all_imgs))
            if expected:
                self._log(f"    ✓ 核對 {len(all_imgs)} / {expected} 頁")
            for m in outputs.ensure_outputs(slug, cid, dest_dir):
                self._log(m)
        else:
            db.set_chapter_status(slug, cid, "error", image_count=len(all_imgs))
            self._log(f"    ⚠ 缺 {len(failed)} 頁 — 再按「開始下載」可重試")
