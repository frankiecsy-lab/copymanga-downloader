"""Central configuration for the mangacopy downloader."""
import os
import sys
from pathlib import Path

BASE_URL = "https://www.mangacopy.com"

# Browser-like UA used for both plain HTTP (listing/images) and Playwright context.
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

# --- Paths -----------------------------------------------------------------
# When frozen (PyInstaller), anchor everything to the folder that contains the .exe so the
# whole app is PORTABLE: data/, downloads/ and browsers/ all live right next to MangaCopy.exe,
# never in %APPDATA% or a temp dir. In dev we keep using the project root.
if getattr(sys, "frozen", False):
    ROOT = Path(sys.executable).resolve().parent          # .../MangaCopy  (the .exe's folder)
else:
    ROOT = Path(__file__).resolve().parent.parent         # project root (D:\coding\copymanga)

DATA_DIR = ROOT / "data"
DOWNLOADS_DIR = ROOT / "downloads"
DB_PATH = DATA_DIR / "mangacopy.db"


def set_downloads_dir(p):
    """Override the download destination at runtime. The caller persists the choice in DB meta;
    every reader (engine, GUI) sees the new value because they read config.DOWNLOADS_DIR live."""
    global DOWNLOADS_DIR
    DOWNLOADS_DIR = Path(p)
    try:
        DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass


# --- Playwright browsers ---------------------------------------------------
# For a portable build we keep Chromium inside the app folder so nothing depends on the user's
# %LOCALAPPDATA%\ms-playwright. build.bat installs it into <ROOT>/playwright-browsers/; older
# builds used <ROOT>/browsers/. Only redirect when frozen AND one of those folders actually
# exists; otherwise fall back to Playwright's default install location.
BROWSERS_DIR = next((p for p in (ROOT / "browsers", ROOT / "playwright-browsers") if p.exists()), None)
if getattr(sys, "frozen", False) and BROWSERS_DIR:
    os.environ["PLAYWRIGHT_BROWSERS_PATH"] = str(BROWSERS_DIR)

# --- Listing crawl ---------------------------------------------------------
LIST_LIMIT = 50            # items per page on /comics?offset=&limit=
LIST_DELAY = 2.0           # seconds between listing pages (be polite; robots says 10)
LIST_ORDERING = "-popular"   # most-popular first (the site's own UI uses -popular; bare "popular" is ascending/least-first)

# Site `status` int -> Traditional-Chinese label, used both for the GUI filter and as the
# top-level download folder name. 0=連載中 1=已完結 2=短篇 (verified against detail pages).
STATUS_LABELS = {0: "連載中", 1: "已完結", 2: "短篇"}
STATUS_UNKNOWN = "未分類"   # fallback folder for a comic whose status we couldn't determine

# --- Image download --------------------------------------------------------
# Site serves each page image as <base>.c1500x.webp (compressed) and <base>.c1500x.jpg.
# We prefer webp; fall back to whatever URL the site actually requested if webp 404s.
IMAGE_SIZE_SUFFIX = ".c1500x.webp"
DOWNLOAD_RETRIES = 3
DOWNLOAD_TIMEOUT = 60

# --- Browser ---------------------------------------------------------------
HEADLESS = True
VIEWPORT = {"width": 1280, "height": 900}
NAV_TIMEOUT = 60_000       # ms
RENDER_SETTLE_MS = 3_000   # initial wait after load before reading DOM

# --- Concurrency / pacing --------------------------------------------------
CHAPTER_RENDER_DELAY = 1.5   # seconds between chapter renders (browser is the bottleneck)
CHAPTER_SCROLL_DELAY = 0.4   # seconds per scroll step while collecting lazy-loaded pages
IMAGE_FETCH_DELAY = 0.2      # seconds between image downloads
