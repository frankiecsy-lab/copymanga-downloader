"""mangacopy downloader — desktop GUI (tkinter).

Layout:
  Row1: [search] [狀態▾] [★只看收藏] [輸出 ☑CBZ ☑WEBP]   [🌙主題][開啟資料夾][停止下載][開始下載]
  Row2: [更新清單][停止更新][清空並重抓] | [全選(可見)][取消全選][清空下載列表] | [檢查更新][重下(選取)][清單清除][下載清除]  [▦圖格視圖]
  [📂更改位置 (dark btn) ............ path]
  +----------------------------------+---------------------------+
  | comic list (tree OR cover grid)  | detail sidebar (~30%)    |
  |  ✓ ★ name author 連載 進度 狀態 |  <cover 原圖, scrollable>|
  +----------------------------------+---------------------------+
  [統計: full-width collection stats strip]
  [====progress====] shown/selected   (log)

The list pane keeps ~70% of the window width (sash auto-set until the user drags it).
Two palettes (light/dark, 🌙 toggle, persisted in DB meta "ui_theme"). The app opens
maximized and declares Per-Monitor-V2 DPI awareness so text stays crisp on scaled displays.

Selection and all download progress live in SQLite, so closing the app mid-download
and reopening it resumes exactly where it stopped — nothing is lost.
"""
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import webbrowser
from pathlib import Path


def _enable_high_dpi():
    """Declare Per-Monitor-V2 DPI awareness BEFORE any Tk window exists.

    Without this, Windows bitmap-stretches the whole app on scaled displays (125%/150%),
    which is what makes text look blurry/jagged in a frozen .exe. PMv2 lets Tk render at
    native resolution and re-layout when the window moves between differently-scaled monitors."""
    if sys.platform != "win32":
        return
    import ctypes
    try:
        # -4 == DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 (Windows 10 1703+)
        if not ctypes.windll.shcore.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4)):
            ctypes.windll.shcore.SetProcessDpiAwareness(2)   # PER_MONITOR_DPI_AWARE fallback
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()       # legacy (system-aware) last resort
        except Exception:
            pass


_enable_high_dpi()

import tkinter as tk  # noqa: E402  (must come AFTER _enable_high_dpi())
from tkinter import ttk, filedialog, messagebox  # noqa: E402

# make `cm` importable when run from the project root
sys.path.insert(0, str(Path(__file__).resolve().parent))

from cm import config, db, listing  # noqa: E402
from cm.engine import Engine        # noqa: E402

# Detail-pane cover key size (right pane is ~30% of window width). The cover itself is shown at
# NATIVE resolution inside a scrollable viewer — DETAIL_W/DETAIL_H only identify the detail fetch.
DETAIL_W, DETAIL_H = 520, 680


def _open_folder(path):
    try:
        if sys.platform.startswith("win"):
            os.startfile(path)  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path])
    except Exception as e:
        print("could not open folder:", e)


# Two full palettes for the 🌙/☀️ theme toggle (active one persisted in DB meta "ui_theme").
THEMES = {
    "light": {
        "bg": "#eef1f5", "panel": "#ffffff", "fg": "#1f2733", "muted": "#6b7684",
        "accent": "#2563eb", "accent_hover": "#1d4ed8", "accent_fg": "#ffffff",
        "danger": "#dc2626", "danger_hover": "#b91c1c",
        "border": "#d3dae3", "row_alt": "#f4f7fb", "head_bg": "#e6ebf2",
        "sel_bg": "#dbeafe", "sel_fg": "#1e293b", "done_bg": "#e0f0e2",
        "entry_bg": "#ffffff", "trough": "#dbe3ee", "btn_active": "#e3ebf7",
        "ink": "#1f2937", "ink_hover": "#374151", "ink_border": "#111827",
        "done_border": "#57b46a",
    },
    "dark": {
        "bg": "#0f172a", "panel": "#1e293b", "fg": "#e2e8f0", "muted": "#94a3b8",
        "accent": "#3b82f6", "accent_hover": "#2563eb", "accent_fg": "#ffffff",
        "danger": "#ef4444", "danger_hover": "#dc2626",
        "border": "#334155", "row_alt": "#233047", "head_bg": "#28354d",
        "sel_bg": "#1e40af", "sel_fg": "#ffffff", "done_bg": "#1c3a2b",
        "entry_bg": "#0b1220", "trough": "#0b1220", "btn_active": "#334155",
        "ink": "#475569", "ink_hover": "#5b6b82", "ink_border": "#1e293b",
        "done_border": "#3fae6a",
    },
}


class App:
    def __init__(self, root):
        self.root = root
        root.title("MangaCopy 下載器")
        root.geometry("1520x840")   # fallback size; the window opens maximized (see __main__)
        root.minsize(1180, 640)

        db.init()
        self._theme = db.get_meta("ui_theme", "light") or "light"
        if self._theme not in THEMES:
            self._theme = "light"
        self._apply_theme()          # active palette (stored on self._c) + all widget styles

        config.DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
        self._restore_downloads_dir()   # re-apply a previously chosen download location if any

        self.events: "queue.Queue" = queue.Queue()
        self.engine = Engine(on_event=self._engine_event)
        self.thumb_cache = {}      # (url, max_w) -> PhotoImage (created on the Tk thread)
        self._thumb_sem = threading.Semaphore(8)   # cap concurrent cover fetches (grid view fires many)
        self._iid_to_slug = {}     # tree item id -> path_word
        self._iid_base_tag = {}    # tree item id -> "even"/"odd" (for live tag updates)
        self._view = "list"        # "list" (tree) or "grid" (cover cards)
        self._rows = []            # rows currently displayed (either view)
        self._grid_cards = {}      # slug -> card widget refs (grid view only)
        self._grid_order = []      # slugs in display order (grid view)
        self._grid_sel_slug = None # highlighted card in grid view
        self._sash_user_moved = False   # stop auto-70% sash once the user drags it
        self._setting_sash = False
        self._listing_thread = None
        self._listing_stop = threading.Event()   # set to halt a running listing crawl
        self._sort_col = None      # column id currently sorted by (None = DB default order)
        self._sort_reverse = False  # True = descending, False = ascending
        self._active_slugs = []    # slugs in the current download run (for the live panel)
        self._run_is_update = False  # True while the active engine run is a 檢查更新 pass
        self._current_name = ""    # comic the engine is processing right now
        self._dl_visible = False   # whether the "正在下載" panel is currently packed

        self._build_ui()
        # if a listing crawl was interrupted last time, offer to resume it (the crawl is always "all")
        try:
            off = int(db.get_meta("listing_next_offset", "0") or 0)
            if off > 0:
                self.refresh_btn.configure(text="繼續更新清單")
        except (TypeError, ValueError):
            pass
        self._load_comics()
        root.after(100, self._poll_events)
        root.protocol("WM_DELETE_WINDOW", self._on_close)

    # ------------------------------------------------------------------ UI --
    def _apply_theme(self):
        """Apply the active palette (light/dark) to every ttk style + native tk widget."""
        c = self._c = THEMES[self._theme]
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except Exception:
            pass
        self.root.configure(bg=c["bg"])
        style.configure(".", font=("Segoe UI", 10), background=c["bg"], foreground=c["fg"])

        # buttons (default + accent/primary + danger variants)
        style.configure("TButton", padding=(12, 6), borderwidth=1)
        style.map("TButton",
                  background=[("active", c["btn_active"]), ("disabled", c["bg"])],
                  foreground=[("disabled", c["muted"])])
        style.configure("Accent.TButton", background=c["accent"], foreground=c["accent_fg"],
                        bordercolor=c["accent"], lightcolor=c["accent"], darkcolor=c["accent"])
        style.map("Accent.TButton",
                  background=[("active", c["accent_hover"]), ("disabled", "#9db6e8")],
                  foreground=[("disabled", "#ffffff")])
        style.configure("Danger.TButton", background=c["danger"], foreground="white",
                        bordercolor=c["danger"], lightcolor=c["danger"], darkcolor=c["danger"])
        style.map("Danger.TButton",
                  background=[("active", c["danger_hover"]), ("disabled", "#e7a3a3")],
                  foreground=[("disabled", "#ffffff")])
        # "ink" button (used by 更改位置) — deep slate bg, semibold white text
        style.configure("Dark.TButton", background=c["ink"], foreground="#ffffff",
                        font=("Segoe UI Semibold", 10), padding=(14, 6),
                        bordercolor=c["ink_border"], lightcolor=c["ink"], darkcolor=c["ink"])
        style.map("Dark.TButton",
                  background=[("active", c["ink_hover"]), ("pressed", c["bg"]), ("disabled", "#64748b")],
                  foreground=[("disabled", "#cbd5e1")])

        # treeview + headers
        style.configure("Treeview", rowheight=30, font=("Segoe UI", 10),
                        fieldbackground=c["panel"], background=c["panel"],
                        foreground=c["fg"], borderwidth=0)
        style.map("Treeview",
                  background=[("selected", c["sel_bg"])],
                  foreground=[("selected", c["sel_fg"])])
        style.configure("Treeview.Heading", font=("Segoe UI", 10, "bold"),
                        background=c["head_bg"], foreground=c["fg"], padding=(8, 7))

        # labelled frames (cards) + progressbar + combobox + entry + checkbutton
        style.configure("Card.TLabelframe", background=c["panel"], bordercolor=c["border"])
        style.configure("Card.TLabelframe.Label", background=c["bg"], foreground=c["muted"],
                        font=("Segoe UI", 9, "bold"))
        style.configure("Accent.Horizontal.TProgressbar", troughcolor=c["trough"],
                        background=c["accent"], bordercolor=c["border"], lightcolor=c["accent"])
        style.configure("TCombobox", fieldbackground=c["entry_bg"], background=c["head_bg"],
                        arrowcolor=c["fg"], bordercolor=c["border"])
        style.map("TCombobox",
                  fieldbackground=[("readonly", c["entry_bg"])],
                  foreground=[("readonly", c["fg"])])
        style.configure("TEntry", fieldbackground=c["entry_bg"], foreground=c["fg"],
                        insertcolor=c["fg"])
        style.configure("Checkbutton", background=c["bg"], foreground=c["fg"],
                        indicatorcolor=c["entry_bg"])
        style.map("Checkbutton", background=[("active", c["btn_active"])])

        self._apply_widget_colors()

    def _apply_widget_colors(self):
        """Recolour the native tk widgets + tree tags that ttk styles don't reach.
        Safe to call before the widgets exist (first theme pass) — it just skips them."""
        c = self._c
        for w in (getattr(self, "log_text", None), getattr(self, "synopsis_text", None)):
            if w is not None:
                w.configure(bg=c["panel"], fg=c["fg"], insertbackground=c["fg"])
        for lbl, key in ((getattr(self, "_stats_label", None), c["fg"]),
                         (getattr(self, "_loc_label", None), c["muted"]),
                         (getattr(self, "_detail_meta_lbl", None), c["muted"])):
            if lbl is not None:
                lbl.configure(foreground=key)
        for w in (getattr(self, "grid_box", None), getattr(self, "_grid_canvas", None),
                  getattr(self, "_grid_inner", None)):
            if w is not None:
                w.configure(bg=c["bg"])
        # detail-pane cover viewer (placeholder box + scrollable canvas + inner image label)
        for w in (getattr(self, "detail_img_box", None), getattr(self, "_detail_canvas", None),
                  getattr(self, "_detail_img_lbl", None)):
            if w is not None:
                w.configure(bg=c["bg"])
        tree = getattr(self, "tree", None)
        if tree is not None:
            tree.tag_configure("even", background=c["panel"])
            tree.tag_configure("odd", background=c["row_alt"])
            tree.tag_configure("done", background=c["done_bg"])

    def _toggle_theme(self):
        self._theme = "dark" if self._theme == "light" else "light"
        db.set_meta("ui_theme", self._theme)
        self.theme_btn.configure(text="☀️ 淺色模式" if self._theme == "dark" else "🌙 暗色模式")
        self._apply_theme()
        self._render()   # rebuild tree/grid cards with the new palette

    def _build_ui(self):
        pad = self.pad = {"padx": 8, "pady": 5}
        c = self._c

        # ================= Row 1 — primary toolbar (search/filters | main actions) ====
        top = ttk.Frame(self.root)
        top.pack(fill="x", **pad)

        self.search_var = tk.StringVar()
        self.search_var.trace_add("write", lambda *a: self._filter())
        ent = ttk.Entry(top, textvariable=self.search_var, width=30)
        ent.pack(side="left")
        ttk.Label(top, text="搜尋").pack(side="left", padx=(4, 12))

        # status filter (連載中 / 已完結 / 短篇). Drives BOTH the displayed list and which
        # comics get pulled in when you click 更新清單. "全部" = no filter.
        ttk.Label(top, text="狀態").pack(side="left", padx=(0, 2))
        self.status_var = tk.StringVar(value="全部")
        self.status_var.trace_add("write", lambda *a: self._filter())
        self.status_box = ttk.Combobox(
            top, textvariable=self.status_var, state="readonly", width=9,
            values=["全部"] + list(config.STATUS_LABELS.values()),
        )
        self.status_box.pack(side="left")

        # 收藏 filter — show only starred comics (independent of the status filter)
        self.fav_filter = tk.BooleanVar(value=False)
        ttk.Checkbutton(top, text="★ 只看收藏", variable=self.fav_filter,
                        command=self._filter).pack(side="left", padx=(14, 0))

        # output post-processing toggles (persisted in DB meta; default = BOTH on)
        ttk.Label(top, text="輸出").pack(side="left", padx=(14, 2))
        cbz_on = db.get_meta("output_cbz")
        webp_on = db.get_meta("output_webp")
        if cbz_on is None and webp_on is None:
            # first run (or a pre-checkbox build that stored a single "output_mode" value)
            legacy = (db.get_meta("output_mode", "") or "").upper()
            if legacy == "CBZ":
                self.cbz_var = tk.BooleanVar(value=True)
                self.webp_var = tk.BooleanVar(value=False)
            elif legacy == "WEBP":
                self.cbz_var = tk.BooleanVar(value=False)
                self.webp_var = tk.BooleanVar(value=True)
            else:   # BOTH or unset -> default both on
                self.cbz_var = tk.BooleanVar(value=True)
                self.webp_var = tk.BooleanVar(value=True)
        else:
            self.cbz_var = tk.BooleanVar(value=cbz_on != "0")
            self.webp_var = tk.BooleanVar(value=webp_on != "0")
        ttk.Checkbutton(top, text="CBZ", variable=self.cbz_var,
                        command=self._save_output_mode).pack(side="left")
        ttk.Checkbutton(top, text="WEBP", variable=self.webp_var,
                        command=self._save_output_mode).pack(side="left", padx=(2, 0))

        # main download actions (right side of row 1)
        self.start_btn = ttk.Button(top, text="開始下載", style="Accent.TButton", command=self._start)
        self.start_btn.pack(side="right")
        self.stop_btn = ttk.Button(top, text="停止下載", style="Danger.TButton", command=self._stop, state="disabled")
        self.stop_btn.pack(side="right", padx=6)
        ttk.Button(top, text="開啟資料夾", command=lambda: _open_folder(str(config.DOWNLOADS_DIR))).pack(side="right", padx=(0, 8))
        # light/dark theme toggle (persisted in DB meta "ui_theme")
        self.theme_btn = ttk.Button(top, text="🌙 暗色模式" if self._theme == "light" else "☀️ 淺色模式",
                                    command=self._toggle_theme)
        self.theme_btn.pack(side="right", padx=(0, 8))

        # ================= Row 2 — list-management toolbar (grouped) ====
        bar2 = ttk.Frame(self.root)
        bar2.pack(fill="x", **pad)

        self.refresh_btn = ttk.Button(bar2, text="更新清單", style="Accent.TButton", command=self._refresh_list)
        self.refresh_btn.pack(side="left")
        self.list_stop_btn = ttk.Button(bar2, text="停止更新", command=self._stop_list, state="disabled")
        self.list_stop_btn.pack(side="left", padx=6)
        ttk.Button(bar2, text="清空並重抓", style="Danger.TButton", command=self._clear_and_refresh).pack(side="left", padx=(0, 14))

        ttk.Separator(bar2, orient="vertical").pack(side="left", fill="y", pady=2)
        ttk.Button(bar2, text="全選(可見)", command=lambda: self._set_all_visible(True)).pack(side="left")
        ttk.Button(bar2, text="取消全選", command=lambda: self._set_all_visible(False)).pack(side="left", padx=6)
        # empties the WHOLE download list (incl. rows hidden by filters); per-item removal is in the right-click menu
        ttk.Button(bar2, text="清空下載列表", command=self._clear_download_list).pack(side="left", padx=(0, 14))

        ttk.Separator(bar2, orient="vertical").pack(side="left", fill="y", pady=2)
        # 檢查更新: re-render the chapter list of every fully-downloaded comic and download any new chapters
        self.update_btn = ttk.Button(bar2, text="🔄 檢查更新", style="Accent.TButton", command=self._check_updates)
        self.update_btn.pack(side="left")
        ttk.Button(bar2, text="重下(選取)", command=self._redownload_selected).pack(side="left", padx=(6, 0))
        ttk.Button(bar2, text="清單清除", style="Danger.TButton", command=self._clear_list).pack(side="left", padx=(6, 0))
        ttk.Button(bar2, text="下載清除", style="Danger.TButton", command=self._clear_downloads).pack(side="left", padx=6)

        # list <-> grid (cover cards) view toggle, right end of the row
        self.view_btn = ttk.Button(bar2, text="▦ 圖格視圖", command=self._toggle_view)
        self.view_btn.pack(side="right")

        # --- download location bar (choose where files are saved; persisted in DB meta) ---
        loc = ttk.Frame(self.root)
        loc.pack(fill="x", **pad)
        self.loc_var = tk.StringVar(value=str(config.DOWNLOADS_DIR))
        ttk.Button(loc, text="📂 更改位置", style="Dark.TButton",
                   command=self._choose_downloads_dir).pack(side="left")
        self._loc_label = ttk.Label(loc, textvariable=self.loc_var, foreground=c["muted"], anchor="w")
        self._loc_label.pack(side="left", fill="x", expand=True, padx=(10, 0))

        mid = self.mid = ttk.Panedwindow(self.root, orient="horizontal")
        mid.pack(fill="both", expand=True, **pad)
        # keep the list pane at ~70% of the window width until the user drags the sash themselves
        mid.bind("<Configure>", self._on_mid_configure)
        mid.bind("<<PaneChanged>>", self._on_pane_changed)

        # --- left: comic list (tree view + grid/cover-cards view, switchable) ---
        left = ttk.Frame(mid)
        mid.add(left, weight=1)

        # list (tree) view
        self.list_box = ttk.Frame(left)
        self.list_box.pack(fill="both", expand=True)
        cols = ("sel", "fav", "name", "author", "serial", "progress", "status", "link")
        self.tree = ttk.Treeview(self.list_box, columns=cols, show="headings", selectmode="browse")
        for col, w, txt in (("sel", 34, "✓"), ("fav", 34, "★"), ("name", 220, "名稱"),
                            ("author", 110, "作者"), ("serial", 58, "連載"),
                            ("progress", 168, "進度"), ("status", 82, "狀態"),
                            ("link", 40, "原連結")):
            self.tree.heading(col, text=txt)
            self.tree.column(col, width=w, anchor="w" if col not in ("sel", "fav", "link") else "center")
        # alternating row colours for a cleaner look; downloaded rows get a green tint
        self.tree.tag_configure("even", background=c["panel"])
        self.tree.tag_configure("odd", background=c["row_alt"])
        self.tree.tag_configure("done", background=c["done_bg"])
        # clickable headers -> sort that column; click again to flip direction
        self._head_text = {"name": "名稱", "author": "作者", "serial": "連載", "status": "狀態"}
        for col in self._head_text:
            self.tree.heading(col, command=lambda c2=col: self._sort_by(c2))
        vsb = ttk.Scrollbar(self.list_box, orient="vertical", command=self.tree.yview)
        hsb = ttk.Scrollbar(self.list_box, orient="horizontal", command=self.tree.xview)
        self.tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        self.tree.pack(side="left", fill="both", expand=True)
        vsb.pack(side="right", fill="y")
        hsb.pack(side="bottom", fill="x")
        self.tree.bind("<Configure>", self._on_tree_configure)  # stretch name column to fill pane
        self.tree.bind("<Button-1>", self._on_tree_click)
        self.tree.bind("<Double-1>", self._open_link)          # double-click a row -> open original page
        self.tree.bind("<Button-3>", self._on_context_menu)    # right-click -> re-download / open link

        # grid (cover cards) view — same rows as the tree, shown as clickable cover images.
        # Built hidden; _toggle_view swaps it with list_box.
        self._card_w, self._card_h = 150, 216
        self.grid_box = tk.Frame(left, bg=c["bg"])
        self._grid_canvas = tk.Canvas(self.grid_box, bg=c["bg"], highlightthickness=0, borderwidth=0)
        gvsb = ttk.Scrollbar(self.grid_box, orient="vertical", command=self._grid_canvas.yview)
        self._grid_inner = tk.Frame(self._grid_canvas, bg=c["bg"])
        self._grid_win = self._grid_canvas.create_window((0, 0), window=self._grid_inner, anchor="nw")
        self._grid_inner.bind("<Configure>",
                              lambda e: self._grid_canvas.configure(scrollregion=self._grid_canvas.bbox("all")))
        self._grid_canvas.configure(yscrollcommand=gvsb.set)
        self._grid_canvas.pack(side="left", fill="both", expand=True)
        gvsb.pack(side="right", fill="y")
        self._grid_canvas.bind("<Configure>", lambda e: self._grid_reflow())   # reflow columns on resize
        for w in (self._grid_canvas, self._grid_inner):
            w.bind("<MouseWheel>", self._on_grid_wheel)

        # --- right: detail pane (~30% of the window; the list keeps ~70%) ---
        right = ttk.Frame(mid, relief="sunken", borderwidth=1, width=300)   # initial size; sash auto-sets 70/30
        mid.add(right, weight=0)
        # Cover viewer: a placeholder label while loading / when there's no cover; once the full-res
        # original lands it is shown at NATIVE pixels (no downscale) inside a scrollable canvas so big
        # covers can be panned with the scrollbars or the mouse wheel.
        self.detail_img_box = tk.Frame(right, bg=c["bg"])
        self.detail_img_box.pack(fill="both", expand=True, padx=8, pady=(8, 4))
        self._detail_ph = ttk.Label(self.detail_img_box, text="(請選取一部漫畫)", anchor="center")
        self._detail_ph.pack(fill="both", expand=True)
        self._detail_canvas = tk.Canvas(self.detail_img_box, highlightthickness=0, borderwidth=0, bg=c["bg"])
        self._dimg_vsb = ttk.Scrollbar(self.detail_img_box, orient="vertical", command=self._detail_canvas.yview)
        self._dimg_hsb = ttk.Scrollbar(self.detail_img_box, orient="horizontal", command=self._detail_canvas.xview)
        self._detail_canvas.configure(yscrollcommand=self._dimg_vsb.set, xscrollcommand=self._dimg_hsb.set)
        self._detail_img_lbl = tk.Label(self._detail_canvas, bg=c["bg"], bd=0)
        self._dimg_win = self._detail_canvas.create_window((0, 0), window=self._detail_img_lbl, anchor="nw")
        self._detail_img_lbl.bind("<Configure>", self._on_detail_img_configure)
        self._detail_canvas.bind("<Configure>", self._on_detail_img_configure)
        self._detail_canvas.bind(
            "<MouseWheel>", lambda e: self._detail_canvas.yview_scroll(-1 if e.delta > 0 else 1, "units"))
        self._detail_img_shown = False   # whether the canvas (vs the placeholder) is currently packed
        self._detail_photo = None        # keeps the full-res PhotoImage alive while it's displayed
        self._detail_title_lbl = ttk.Label(right, font=("Segoe UI", 12, "bold"), wraplength=276)
        self.detail_title = tk.StringVar(value="")
        self._detail_title_lbl.configure(textvariable=self.detail_title)
        self._detail_title_lbl.pack(anchor="w", padx=8)
        right.bind("<Configure>", self._on_right_configure)   # keep title wraplength in sync with pane width

        self.detail_meta = tk.StringVar(value="")
        self._detail_meta_lbl = ttk.Label(right, textvariable=self.detail_meta, foreground=c["muted"])
        self._detail_meta_lbl.pack(anchor="w", padx=8)
        syn_frame = ttk.Frame(right)
        syn_frame.pack(fill="both", expand=True, padx=8, pady=(4, 8))
        self.synopsis_text = tk.Text(syn_frame, wrap="word", height=10, state="disabled")
        svsb = ttk.Scrollbar(syn_frame, orient="vertical", command=self.synopsis_text.yview)
        self.synopsis_text.configure(yscrollcommand=svsb.set)
        self.synopsis_text.pack(side="left", fill="both", expand=True)
        svsb.pack(side="right", fill="y")

        # --- "正在下載" live panel (packed only while a download is running; sits above the progress bar) ---
        self.dl_frame = ttk.LabelFrame(self.root, text="  正在下載  ", style="Card.TLabelframe")
        dl_inner = ttk.Frame(self.dl_frame)
        dl_inner.pack(fill="both", expand=True, padx=6, pady=4)
        self.dl_tree = ttk.Treeview(dl_inner, columns=("dname", "dprog"), show="headings", height=3)
        self.dl_tree.heading("dname", text="作品")
        self.dl_tree.heading("dprog", text="進度")
        self.dl_tree.column("dname", width=420, anchor="w")
        self.dl_tree.column("dprog", width=180, anchor="e")
        dlsb = ttk.Scrollbar(dl_inner, orient="vertical", command=self.dl_tree.yview)
        self.dl_tree.configure(yscrollcommand=dlsb.set)
        self.dl_tree.pack(side="left", fill="both", expand=True)
        dlsb.pack(side="right", fill="y")

        # --- collection summary: full-width strip between the list area and the log ---
        stats = ttk.LabelFrame(self.root, text="  統計  ", style="Card.TLabelframe")
        stats.pack(fill="x", **pad)
        self.counts_var = tk.StringVar(value="")
        self._stats_label = ttk.Label(stats, textvariable=self.counts_var, foreground=c["fg"], anchor="w")
        self._stats_label.pack(fill="x", padx=8, pady=2)

        # --- bottom: progress + log ---
        bot = self.bot = ttk.Frame(self.root)
        bot.pack(fill="x", **pad)
        self.progress_var = tk.DoubleVar(value=0.0)
        self.progress = ttk.Progressbar(bot, variable=self.progress_var, maximum=100.0,
                                        style="Accent.Horizontal.TProgressbar")
        self.progress.pack(side="left", fill="x", expand=True)
        self.progress_label = tk.StringVar(value="")
        ttk.Label(bot, textvariable=self.progress_label).pack(side="right", padx=(8, 0))

        logf = ttk.Frame(self.root)
        logf.pack(fill="both", **pad)
        self.log_text = tk.Text(logf, height=6, wrap="word")
        lvsb = ttk.Scrollbar(logf, orient="vertical", command=self.log_text.yview)
        self.log_text.configure(yscrollcommand=lvsb.set)
        self.log_text.pack(side="left", fill="both", expand=True)
        lvsb.pack(side="right", fill="y")

        # the tk.Text widgets were created after the first theme pass — recolour them now
        self._apply_widget_colors()

    def _on_tree_configure(self, event):
        """Stretch the name column to fill the tree pane (no trailing blank gap)."""
        try:
            others = sum(int(self.tree.column(col, "width")) for col in
                         ("sel", "fav", "author", "serial", "progress", "status", "link"))
            w = event.width - others - 6   # small slack for the vertical scrollbar
            if w > 120:
                self.tree.column("name", width=w)
        except Exception:
            pass

    def _on_mid_configure(self, event):
        """Keep the list pane at ~70% of the window width until the user drags the sash."""
        if self._sash_user_moved or self._setting_sash:
            return
        w = event.width
        if w > 500:
            self._setting_sash = True
            try:
                self.mid.sashpos(0, int(w * 0.7))
            except Exception:
                pass
            finally:
                self._setting_sash = False

    def _on_pane_changed(self, event):
        if not self._setting_sash:
            self._sash_user_moved = True   # the user dragged the sash — stop auto-positioning

    def _on_right_configure(self, event):
        """Keep the detail title's wraplength in sync with the pane's actual width."""
        if event.width > 60:
            self._detail_title_lbl.configure(wraplength=event.width - 24)

    def _on_detail_img_configure(self, event=None):
        """Keep the cover viewer's scrollregion in sync; center a cover that's smaller than the
        viewport and show/hide each scrollbar only when its axis actually overflows."""
        cv = self._detail_canvas
        vw, vh = cv.winfo_width(), cv.winfo_height()
        if vw < 10 or vh < 10:
            return
        bb = cv.bbox("all")
        if not bb:
            return
        cv.configure(scrollregion=bb)
        win_bb = cv.bbox(self._dimg_win)
        if win_bb:
            x = max(0, (vw - win_bb[2]) // 2)
            y = max(0, (vh - win_bb[3]) // 2)
            cx, cy = cv.coords(self._dimg_win)[:2]
            if int(cx) != x or int(cy) != y:
                cv.coords(self._dimg_win, x, y)
        want_v, have_v = bb[1] + bb[3] > vh, bool(self._dimg_vsb.winfo_manager())
        if want_v != have_v:
            self._dimg_vsb.pack(side="right", fill="y") if want_v else self._dimg_vsb.pack_forget()
        want_h, have_h = bb[0] + bb[2] > vw, bool(self._dimg_hsb.winfo_manager())
        if want_h != have_h:
            self._dimg_hsb.pack(side="bottom", fill="x") if want_h else self._dimg_hsb.pack_forget()

    # ------------------------------------------------------------- events --
    def _engine_event(self, etype, payload):
        """Called from the engine worker thread — push to queue for the Tk loop."""
        self.events.put((etype, payload))

    def _poll_events(self):
        got = False
        page_refresh = False   # set when a listing page lands -> live-refresh list + counts this tick
        try:
            while True:
                etype, payload = self.events.get_nowait()
                got = True
                if etype == "log":
                    self._append_log(payload["message"])
                elif etype == "ui":
                    # callable dispatched from a worker thread — tkinter is main-thread only,
                    # so threads must never call widget methods / root.after directly
                    try:
                        payload()
                    except Exception as e:
                        print("ui dispatch error:", e)
                elif etype == "all_done":
                    self._on_all_done(payload.get("stopped", False))
                elif etype == "downloading":
                    self._current_name = payload.get("name", "")   # -> live panel title
                elif etype == "listing_page":
                    page_refresh = True   # a listing page committed -> refresh list + counts now
                elif etype == "listing_done":
                    self.refresh_btn.configure(state="normal")
                    self.list_stop_btn.configure(state="disabled")
                    stopped = payload.get("stopped", False)
                    total = payload.get("total")
                    if stopped:
                        db.set_meta("listing_next_offset", str(payload.get("next_offset", 0)))
                        self.refresh_btn.configure(text="繼續更新清單")
                        self._append_log(f"■ 已停止更新。目前資料庫 {db.count_comics()} 部 — 點「繼續更新清單」可從中斷處續抓。")
                    else:
                        db.set_meta("listing_next_offset", "0")
                        self.refresh_btn.configure(text="更新清單")
                        n = total if total is not None else db.count_comics()
                        self._append_log(f"■ 清單更新完成 — 共 {n} 部漫畫。")
                    self._reload_tree()
        except queue.Empty:
            pass
        if page_refresh:
            self._reload_tree()   # live update of list + counts as the listing crawl progresses
        # refresh progress bar while a download is running (or right after an event)
        if self.engine.running or got:
            self._refresh_progress()
            now = time.time()
            # live "正在下載" panel — refresh ~1/s while running; hide promptly when idle
            if (self.engine.running and now - getattr(self, "_last_dl_refresh", 0.0) > 0.8) \
                    or (not self.engine.running and self._dl_visible):
                self._last_dl_refresh = now
                try:
                    self._update_downloading_panel()
                except Exception as e:
                    print("dl panel error:", e)
            # keep the per-comic progress/status cells in the list current (~1/s, not every 100ms tick)
            if self.engine.running and (now - getattr(self, "_last_row_refresh", 0.0)) > 0.8:
                self._last_row_refresh = now
                try:
                    self._refresh_rows_live()
                    self._update_counts()
                except Exception as e:
                    print("row refresh error:", e)
        self.root.after(100, self._poll_events)

    def _append_log(self, msg):
        self.log_text.configure(state="normal")
        self.log_text.insert("end", time.strftime("%H:%M:%S ") + msg + "\n")
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    # ------------------------------------------------------------- comics --
    def _status_filter(self):
        """The selected status label, or None for '全部' (no filter)."""
        v = self.status_var.get().strip()
        return v if v and v != "全部" else None

    def _current_rows(self):
        """Fresh comic rows for the current search + status + favorite filters."""
        return db.get_comics(search=self.search_var.get().strip(), limit=500,
                             serial_status=self._status_filter(),
                             favorite_only=bool(self.fav_filter.get()))

    def _render(self):
        """Render the current rows in the active view (list tree or cover grid)."""
        rows = self._sorted_rows(self._current_rows())
        self._rows = rows
        if self._view == "grid":
            self._populate_grid(rows)
        else:
            self._populate_tree(rows)

    def _load_comics(self):
        self._render()

    def _filter(self):
        self._render()

    # ------------------------------------------------------------- sorting --
    _SORT_KEYS = {
        "name":   lambda r: (r["name"] or "").lower(),
        "author": lambda r: (r.get("author") or "").lower(),
        "serial": lambda r: (r.get("serial_status") or ""),
        "status": lambda r: (r.get("status") or ""),
    }

    def _sorted_rows(self, rows):
        """rows ordered by the active sort column (or DB order when none is set)."""
        key = self._SORT_KEYS.get(self._sort_col)
        if not key:
            return list(rows)
        return sorted(rows, key=key, reverse=self._sort_reverse)

    def _populate_tree(self, rows):
        """Replace the tree contents with `rows` (top to bottom)."""
        for iid in self.tree.get_children():
            self.tree.delete(iid)
        self._iid_to_slug = {}
        self._iid_base_tag = {}
        stmap = db.comic_status_map()
        for i, r in enumerate(rows):
            tag = "even" if i % 2 == 0 else "odd"
            tags = (tag, "done") if self._is_downloaded(r, stmap.get(r["path_word"], {})) else (tag,)
            iid = self.tree.insert("", "end", values=self._comic_row_values(r, stmap), tags=tags)
            self._iid_to_slug[iid] = r["path_word"]
            self._iid_base_tag[iid] = tag
        self._update_count()

    def _is_downloaded(self, r, st):
        """True when the comic's files are fully on disk (drives the green row tint)."""
        s = r.get("status")
        ch_done, ch_total = st.get("ch_done", 0), st.get("ch_total", 0)
        return s == "done" or (ch_total > 0 and ch_done >= ch_total)

    def _sort_by(self, col):
        """Sort by a column header; clicking the same header flips the direction."""
        if self._sort_col == col:
            self._sort_reverse = not self._sort_reverse      # same column -> flip
        else:
            self._sort_col, self._sort_reverse = col, False  # new column -> ascending
        for c, base in self._head_text.items():
            arrow = (" ▼" if self._sort_reverse else " ▲") if c == col else ""
            self.tree.heading(c, text=base + arrow)
        self._render()

    def _reload_tree(self):
        """Rebuild the tree from the DB (used after a listing refresh)."""
        self._load_comics()

    def _status_text(self, r):
        s = r.get("status")
        return {
            "new": "待下載",
            "chapters_done": "已抓章節",
            "done": "✓ 已完成",
            "error": "✗ 出錯",
        }.get(s, s or "待下載")

    @staticmethod
    def _progress_text(done, total):
        """A compact per-comic progress cell: ▓▓░░░░░░░░ 30% · 3/10."""
        if not total or total <= 0:
            return ""
        pct = int(100 * done / total)
        blocks = max(0, min(10, round(pct / 10)))
        bar = "▓" * blocks + "░" * (10 - blocks)
        return f"{bar} {pct}% · {done}/{total}"

    def _status_label(self, r, st):
        """Per-comic status cell that also reflects live download state / page shortfalls."""
        s = r.get("status")
        ch_done, ch_total, ch_err = st.get("ch_done", 0), st.get("ch_total", 0), st.get("ch_err", 0)
        if self.engine.running and ch_total > 0 and ch_done < ch_total:
            return "下載中…"
        if ch_err > 0:
            return "✗ 缺頁"
        if s == "done" or (ch_total > 0 and ch_done >= ch_total):
            return "✓ 完成"
        if ch_total > 0 and ch_done < ch_total:
            return "未完成"
        return self._status_text(r)

    def _comic_row_values(self, r, stmap):
        """Build the full values tuple for one tree row (sel,fav,name,author,serial,progress,status,link)."""
        pw = r["path_word"]
        st = stmap.get(pw, {})
        prog = self._progress_text(st.get("done", 0), st.get("total", 0)) \
            or self._progress_text(st.get("ch_done", 0), st.get("ch_total", 0))
        return ("✓" if r["selected"] else "", "★" if r.get("favorite") else "", r["name"],
                r["author"] or "-", r.get("serial_status") or "", prog, self._status_label(r, st), "🔗")

    def _refresh_rows_live(self):
        """Update the progress + status cells of visible rows (called ~1/s while downloading)."""
        stmap = db.comic_status_map()
        by_pw = {r["path_word"]: r for r in db.get_comics(limit=500)}
        if self._view == "grid":
            # cards show no progress text — just flip the border green when a comic completes
            for slug, d in list(self._grid_cards.items()):
                r = by_pw.get(slug)
                if not r:
                    continue
                done = self._is_downloaded(r, stmap.get(slug, {}))
                if done != d["done"]:
                    d["done"] = done
                    d["card"].configure(highlightbackground=self._card_border(slug))
            return
        for iid, pw in list(self._iid_to_slug.items()):
            if not self.tree.exists(iid) or not self.tree.bbox(iid):   # only rows on screen
                continue
            r = by_pw.get(pw)
            if not r:
                continue
            st = stmap.get(pw, {})
            vals = list(self.tree.item(iid, "values"))
            prog = self._progress_text(st.get("done", 0), st.get("total", 0)) \
                or self._progress_text(st.get("ch_done", 0), st.get("ch_total", 0))
            vals[5] = prog
            vals[6] = self._status_label(r, st)
            base = self._iid_base_tag.get(iid, "even")
            tags = (base, "done") if self._is_downloaded(r, st) else (base,)
            self.tree.item(iid, values=vals, tags=tags)

    def _update_count(self):
        rows = self._rows or []
        sel = sum(1 for r in rows if r["selected"])
        self.progress_label.set(f"{len(rows)} shown · {sel} selected")
        self._update_counts()

    def _update_counts(self):
        """Refresh the bottom collection-summary bar (whole DB, not the current filter)."""
        try:
            c = db.collection_counts()
        except Exception as e:
            print("counts error:", e)
            return
        self.counts_var.set(
            f"全部 {c['total']}  ·  "
            f"未下載 {c['pending']}  ·  已下載 {c['done']}  ·  出錯 {c['errored']}"
            f"    ｜    "
            f"★ 收藏 {c.get('favs', 0)}"
            f"    ｜    "
            f"連載中 {c['ongoing']}  ·  已完結 {c['finished']}  ·  短篇 {c['oneshot']}"
        )

    def _on_tree_click(self, event):
        iid = self.tree.identify_row(event.y)
        if not iid:
            return
        # single click on the sel column toggles; elsewhere selects for detail view
        col = self.tree.identify_column(event.x)
        values = self.tree.item(iid, "values")
        slug = self._iid_to_slug.get(iid)
        if col == "#1" and slug:                       # ✓ column -> toggle selection
            now_sel = values[0] != "✓"
            db.set_selected(slug, now_sel)
            self._mark_row_selected(slug, now_sel)
            vals = list(values); vals[0] = "✓" if now_sel else ""
            self.tree.item(iid, values=vals)
            self._update_count()
        elif col == "#2" and slug:                     # ★ column -> toggle favorite (收藏)
            now_fav = values[1] != "★"
            db.set_favorite(slug, now_fav)
            vals = list(values); vals[1] = "★" if now_fav else ""
            self.tree.item(iid, values=vals)
            self._update_counts()
        # always show detail for the clicked row
        if slug:
            self._show_detail(slug)

    def _set_all_visible(self, select):
        for r in (self._rows or []):
            slug = r["path_word"]
            db.set_selected(slug, select)
            r["selected"] = select   # keep the counter's row snapshot in sync
            if self._view == "list":
                iid = next((i for i, s in self._iid_to_slug.items() if s == slug), None)
                if iid:
                    vals = list(self.tree.item(iid, "values")); vals[0] = "✓" if select else ""
                    self.tree.item(iid, values=vals)
            else:
                d = self._grid_cards.get(slug)
                if d:
                    d["sel"].configure(text="✓" if select else "",
                                       bg="#16a34a" if select else self._c["panel"],
                                       fg="white" if select else self._c["muted"])
        self._update_count()

    def _clear_download_list(self):
        """Deselect every comic — empties the whole download list (incl. rows hidden by filters)."""
        db.clear_selection()
        for r in (self._rows or []):
            r["selected"] = False
        if self._view == "list":
            for iid in self.tree.get_children():
                vals = list(self.tree.item(iid, "values")); vals[0] = ""
                self.tree.item(iid, values=vals)
        else:
            for d in self._grid_cards.values():
                d["sel"].configure(text="", bg=self._c["panel"], fg=self._c["muted"])
        self._update_count()

    def _remove_from_queue(self, slug):
        """Remove one comic from the download list (right-click menu item)."""
        db.set_selected(slug, False)
        self._mark_row_selected(slug, False)
        if self._view == "list":
            iid = next((i for i, s in self._iid_to_slug.items() if s == slug), None)
            if iid:
                vals = list(self.tree.item(iid, "values")); vals[0] = ""
                self.tree.item(iid, values=vals)
        else:
            d = self._grid_cards.get(slug)
            if d:
                d["sel"].configure(text="", bg=self._c["panel"], fg=self._c["muted"])
        self._update_count()

    # ------------------------------------------------------------- grid view --
    def _toggle_view(self):
        """Switch the left pane between the list (tree) and grid (cover cards) views."""
        if self._view == "list":
            self.list_box.pack_forget()
            self.grid_box.pack(fill="both", expand=True)
            self.view_btn.configure(text="☰ 清單視圖")
            self._view = "grid"
        else:
            self.grid_box.pack_forget()
            self.list_box.pack(fill="both", expand=True)
            self.view_btn.configure(text="▦ 圖格視圖")
            self._view = "list"
        self._render()

    def _on_grid_wheel(self, event):
        self._grid_canvas.yview_scroll(-1 if event.delta > 0 else 1, "units")

    def _card_border(self, slug):
        """Border colour for a grid card: accent when selected, green when downloaded."""
        d = self._grid_cards.get(slug)
        if not d:
            return self._c["border"]
        if slug == self._grid_sel_slug:
            return self._c["accent"]
        return self._c["done_border"] if d["done"] else self._c["border"]

    def _populate_grid(self, rows):
        """Rebuild the cover-card grid with `rows` (same order as the tree view)."""
        c = self._c
        inner = self._grid_inner
        for child in inner.winfo_children():
            child.destroy()
        stmap = db.comic_status_map()
        self._grid_cards = {}
        self._grid_order = []
        self._grid_sel_slug = None
        for r in rows:
            slug = r["path_word"]
            done = self._is_downloaded(r, stmap.get(slug, {}))
            card = tk.Frame(inner, bg=c["panel"], width=self._card_w, height=self._card_h,
                            highlightthickness=1,
                            highlightbackground=c["done_border"] if done else c["border"])
            img = tk.Label(card, text="無封面", bg=c["panel"], fg=c["muted"], font=("Segoe UI", 9))
            img.pack(pady=(8, 2))
            name = tk.Label(card, text=r["name"] or slug, bg=c["panel"], fg=c["fg"],
                            font=("Segoe UI", 9), wraplength=self._card_w - 20)
            name.pack(fill="x", padx=10, pady=(0, 8))
            sel = tk.Label(card, text="✓" if r["selected"] else "", width=2,
                           bg="#16a34a" if r["selected"] else c["panel"],
                           fg="white" if r["selected"] else c["muted"], font=("Segoe UI", 9, "bold"))
            sel.place(x=2, y=2)
            fav = tk.Label(card, text="★" if r.get("favorite") else "", bg=c["panel"],
                           fg="#f59e0b", font=("Segoe UI", 10, "bold"))
            fav.place(relx=1.0, x=-18, y=2)
            for w in (card, img, name):
                w.bind("<Button-1>", lambda e, s=slug: self._on_grid_click(s))
                w.bind("<Double-1>", lambda e, s=slug: webbrowser.open(f"{config.BASE_URL}/comic/{s}"))
                w.bind("<Button-3>", lambda e, s=slug: self._on_grid_context(e, s))
            sel.bind("<Button-1>", lambda e, s=slug: self._toggle_grid_sel(s))
            self._grid_cards[slug] = {"card": card, "img": img, "name": name,
                                      "sel": sel, "fav": fav, "done": done}
            self._grid_order.append(slug)
            if r.get("cover_url"):
                threading.Thread(
                    target=self._load_thumb,
                    args=(r["cover_url"], 138, 170, lambda ph, s=slug: self._set_grid_thumb(s, ph)),
                    daemon=True).start()
        self._grid_reflow()

    def _grid_reflow(self):
        """Lay the cards out in as many columns as the canvas currently fits."""
        w = self._grid_canvas.winfo_width()
        if w < 50 or not self._grid_order:
            return
        cols = max(1, (w - 16) // (self._card_w + 8))
        if cols == getattr(self, "_grid_cols", None):
            return
        self._grid_cols = cols
        for i, slug in enumerate(self._grid_order):
            d = self._grid_cards.get(slug)
            if d:
                d["card"].grid(row=i // cols, column=i % cols, padx=4, pady=4, sticky="n")

    def _on_grid_click(self, slug):
        """Single click on a card: highlight it and show its detail in the right pane."""
        prev = self._grid_sel_slug
        self._grid_sel_slug = slug
        if prev and prev != slug and prev in self._grid_cards:
            self._grid_cards[prev]["card"].configure(highlightbackground=self._card_border(prev))
        d = self._grid_cards.get(slug)
        if d:
            d["card"].configure(highlightbackground=self._card_border(slug))
        self._show_detail(slug)

    def _mark_row_selected(self, slug, selected):
        """Keep the in-memory row (used by the shown/selected counter) in sync with the DB."""
        for r in (self._rows or []):
            if r["path_word"] == slug:
                r["selected"] = selected
                break

    def _toggle_grid_sel(self, slug):
        """Click the ✓ badge on a card to toggle its download selection."""
        r = db.get_comic(slug)
        now_sel = not (r and r["selected"])
        db.set_selected(slug, now_sel)
        self._mark_row_selected(slug, now_sel)
        d = self._grid_cards.get(slug)
        if d:
            d["sel"].configure(text="✓" if now_sel else "",
                               bg="#16a34a" if now_sel else self._c["panel"],
                               fg="white" if now_sel else self._c["muted"])
        self._update_count()

    def _on_grid_context(self, event, slug):
        c = self._c
        r = db.get_comic(slug) or {}
        m = tk.Menu(self.root, tearoff=0, bg=c["panel"], fg=c["fg"],
                    activebackground=c["accent"], activeforeground="#ffffff")
        label = "取消勾選下載" if r.get("selected") else "勾選下載"
        m.add_command(label=label, command=lambda: self._toggle_grid_sel(slug))
        m.add_command(label="檢查更新(此部)", command=lambda: self._check_updates_one(slug))
        m.add_command(label="重新下載此漫畫", command=lambda: self._redownload_one(slug))
        m.add_command(label="開啟原連結", command=lambda: webbrowser.open(f"{config.BASE_URL}/comic/{slug}"))
        try:
            m.tk_popup(event.x_root, event.y_root)
        finally:
            m.grab_release()

    def _set_grid_thumb(self, slug, photo):
        d = self._grid_cards.get(slug)   # card may have been rebuilt since the fetch started
        if d:
            d["img"].configure(image=photo, text="")

    # ------------------------------------------------------------- detail --
    def _show_detail(self, slug):
        r = db.get_comic(slug)
        if not r:
            return
        self.detail_title.set(r["name"] or slug)
        meta_bits = []
        if r.get("author"):
            meta_bits.append(f"作者：{r['author']}")
        if r.get("serial_status"):
            meta_bits.append(r["serial_status"])
        meta_bits.append(self._status_text(r))
        nch = len(db.all_chapters(slug))
        if nch:
            meta_bits.append(f"{nch} 章")
        self.detail_meta.set("   ".join(meta_bits))

        syn = r.get("synopsis") or "（尚無簡介 — 執行一次下載即可抓取）"
        self.synopsis_text.configure(state="normal")
        self.synopsis_text.delete("1.0", "end")
        self.synopsis_text.insert("1.0", syn)
        self.synopsis_text.configure(state="disabled")

        # cover (async; the full-res original is shown native in the scrollable viewer)
        cover = r.get("cover_url")
        self._detail_thumb_slug = slug   # which comic the detail pane is waiting on
        if cover:
            self._set_detail_placeholder("載入中…")
            threading.Thread(target=self._load_thumb, args=(cover, DETAIL_W, DETAIL_H), daemon=True).start()
        else:
            self._set_detail_placeholder("(無封面)")

    def _load_thumb(self, url, max_w=DETAIL_W, max_h=DETAIL_H, on_done=None):
        """Fetch (once) + resize a cover to (max_w, max_h); cache the PhotoImage per size.
        Runs in a worker thread — but Tk objects must be created on the Tk thread only,
        so the worker does network+PIL and hands the PIL image back via the events queue."""
        try:
            from PIL import Image
            import requests
            key = (url, max_w)
            cached = self.thumb_cache.get(key)
            if not cached:
                # Detail pane: prefer the full-res original — cover_url points at a CDN
                # pre-sized variant ("...1684896820.jpg.328x422.jpg"); stripping the size
                # suffix yields the original (e.g. 1587x2494). Fall back to the sized URL.
                candidates = [url]
                if max_w == DETAIL_W:
                    hi = re.sub(r"\.\d+x\d+\.(jpe?g|png)$", "", url, flags=re.I)
                    if hi != url:
                        candidates.insert(0, hi)
                with self._thumb_sem:   # cap concurrent network fetches (grid view fires many at once)
                    resp = None
                    for u in candidates:
                        try:
                            rr = requests.get(u, headers={"User-Agent": config.USER_AGENT}, timeout=30)
                            if rr.status_code == 200 and len(rr.content) > 1000:
                                resp = rr
                                break
                        except Exception:
                            continue
                    if resp is None:
                        raise RuntimeError("cover fetch failed for all candidates")
                p = Path(config.DATA_DIR) / "thumbs" / (hashlib_md5(url) + f".{max_w}.jpg")
                p.parent.mkdir(parents=True, exist_ok=True)
                if not p.exists():
                    p.write_bytes(resp.content)
                img = Image.open(p).convert("RGB")
                if max_w != DETAIL_W:   # grid cards get downscaled; the detail pane keeps NATIVE pixels (原圖)
                    img.thumbnail((max_w, max_h))
                self.events.put(("ui", lambda im=img, k=key: self._make_thumb(k, im, on_done)))
            else:
                if on_done:
                    self.events.put(("ui", lambda ph=cached: on_done(ph)))
                elif max_w == DETAIL_W:   # detail-pane size -> route to the detail label
                    self.events.put(("ui", lambda ph=cached: self._set_detail_thumb(ph)))
        except Exception as e:
            print("thumb error:", e)
            if not on_done and max_w == DETAIL_W:   # detail pane fetch failed -> say so instead of hanging on "載入中…"
                self.events.put(("ui", lambda: self._set_detail_placeholder("(封面載入失敗)")))

    def _make_thumb(self, key, pil_img, on_done):
        """Tk thread only (via the events poller): build the PhotoImage, then hand it over.
        Full-res detail covers are NOT kept in thumb_cache — a native 1500×2400 cover is ~10MB of
        RAM each; the disk cache under data/thumbs/ already avoids re-downloading."""
        from PIL import ImageTk
        photo = ImageTk.PhotoImage(pil_img)
        if key[1] != DETAIL_W:   # grid cards etc. stay memory-cached as before
            self.thumb_cache[key] = photo
        if on_done:
            on_done(photo)
        elif key[1] == DETAIL_W:   # detail-pane size -> route to the detail viewer
            self._set_detail_thumb(photo)

    def _set_detail_thumb(self, photo):
        # only set if this comic is still the one being viewed (avoid stale overwrites)
        slug = getattr(self, "_detail_thumb_slug", None)
        if self._view == "grid":
            cur = self._grid_sel_slug
        else:
            sel = self.tree.selection()
            cur = self._iid_to_slug.get(sel[0]) if sel else None
        if cur == slug and slug is not None:
            self._set_detail_image(photo)

    def _set_detail_placeholder(self, text):
        """Show the placeholder in the cover area (hides any full-res image currently shown)."""
        if self._detail_img_shown:
            self._detail_img_lbl.configure(image="", text="")
            self._detail_photo = None    # release the RAM only after Tk no longer references it
            self._dimg_vsb.pack_forget()
            self._dimg_hsb.pack_forget()
            self._detail_canvas.pack_forget()
            self._detail_img_shown = False
        if not self._detail_ph.winfo_manager():
            self._detail_ph.pack(fill="both", expand=True)
        self._detail_ph.configure(text=text)

    def _set_detail_image(self, photo):
        """Show the full-res cover at NATIVE pixels inside the scrollable canvas."""
        if self._detail_ph.winfo_manager():
            self._detail_ph.pack_forget()
        self._detail_canvas.pack(side="left", fill="both", expand=True)
        self._dimg_vsb.pack(side="right", fill="y")
        self._dimg_hsb.pack(side="bottom", fill="x")
        self._detail_img_lbl.configure(image=photo, text="")
        # Tk only stores the image NAME on the widget — without a live Python ref, CPython GCs the
        # PhotoImage and every later configure() on this label raises TclError "image pyimageN doesn't exist"
        self._detail_photo = photo
        self._detail_img_shown = True

    # ------------------------------------------------------------- actions --
    def _refresh_list(self):
        if self._listing_thread and self._listing_thread.is_alive():
            messagebox.showinfo("更新清單", "已經有更新在進行中。")
            return
        # The crawl always fetches ALL comics (every status). The 狀態 dropdown is a display filter only.
        saved_offset = int(db.get_meta("listing_next_offset", "0") or 0)
        start_offset = saved_offset if saved_offset > 0 else 0
        resuming = start_offset > 0

        self._listing_stop.clear()
        db.set_meta("listing_status", "全部")   # single global break point (crawl is always all)
        self.refresh_btn.configure(state="disabled")
        self.list_stop_btn.configure(state="normal")
        msg = (f"繼續更新清單（從 offset {start_offset} 續抓）…" if resuming
               else f"開始更新漫畫清單（全部狀態；會逐頁抓取，可能需要幾分鐘；隨時可「停止更新」）…")
        self._append_log(msg)

        prog = {"offset": start_offset}   # last page offset seen (fallback on unexpected error)

        def on_page(pg, off, n, err):
            prog["offset"] = off
            self.events.put(("log", {"message": f"  清單第 {pg} 頁 (offset {off}): +{n}" if not err else f"  {err}"}))
            if not err:
                self.events.put(("listing_page", None))   # -> live refresh of list + counts

        def worker():
            try:
                res = listing.crawl_listing(
                    on_page=on_page,
                    stop_check=self._listing_stop.is_set,
                    start_offset=start_offset,
                    status=None,   # always crawl everything; 狀態 is display-only
                )
                self.events.put(("listing_done", res))
            except Exception as e:
                # unexpected error mid-crawl: keep progress so we can resume from the last page
                self.events.put(("log", {"message": f"更新清單出錯：{e}"}))
                self.events.put(("listing_done", {"stopped": True, "next_offset": prog["offset"], "total": None}))

        self._listing_thread = threading.Thread(target=worker, daemon=True)
        self._listing_thread.start()

    def _stop_list(self):
        if not (self._listing_thread and self._listing_thread.is_alive()):
            return
        self.list_stop_btn.configure(state="disabled")   # avoid double-clicks; re-enabled on listing_done
        self._append_log("停止更新中…（會存下進度，之後可「繼續更新清單」）")
        self._listing_stop.set()

    def _clear_and_refresh(self):
        """Wipe every record (keep downloaded files) then re-crawl the listing from scratch."""
        if self._listing_thread and self._listing_thread.is_alive():
            messagebox.showwarning("清空並重抓", "更新清單進行中 — 請先「停止更新」再操作。")
            return
        if self.engine.running:
            messagebox.showwarning("清空並重抓", "下載進行中 — 請先「停止下載」再操作。")
            return
        n = db.count_comics()
        if not messagebox.askyesno(
                "清空並重新更新",
                f"確定要清空全部 {n} 部漫畫的紀錄，然後由頭重新抓取清單嗎？\n\n"
                "（已下載的檔案會保留；只清資料庫記錄再重抓）"):
            return

        def worker():
            try:
                db.clear_all()          # wipes records + resets the listing offset to 0
                self.thumb_cache.clear()
                self.events.put(("log", {"message": "■ 已清空清單，開始重新抓取…"}))
                self.events.put(("ui", self._reload_tree))   # show empty list immediately
            except Exception as e:
                self.events.put(("log", {"message": f"清除出錯：{e}"}))
                return
            # offset is now 0 (reset by clear_all), so this starts a fresh full crawl
            self.events.put(("ui", self._refresh_list))

        threading.Thread(target=worker, daemon=True).start()

    def _start(self):
        slugs = db.selected_path_words()
        if not slugs:
            messagebox.showinfo("開始下載", "尚未選擇任何漫畫。請點最左的 ✓ 欄勾選要下的。")
            return
        self.stop_btn.configure(state="normal")
        self.start_btn.configure(state="disabled")
        self._run_is_update = False
        self._append_log(f"開始下載 {len(slugs)} 部…（隨時可「停止下載」，進度會保存）")
        self._active_slugs = list(slugs)
        self._current_name = ""
        self._last_dl_refresh = 0.0   # force an immediate panel refresh on the next tick
        self.engine.start(slugs)

    def _stop(self):
        if not self.engine.running:
            return
        self._append_log("停止中…（會在目前這頁下完後停；進度已存，再按「開始下載」即可續傳）")
        self.engine.request_stop()

    def _on_all_done(self, stopped):
        self.stop_btn.configure(state="disabled")
        self.start_btn.configure(state="normal")
        if stopped:
            msg = "■ 已停止。"
        elif self._run_is_update:
            msg = "■ 更新檢查完成 — 所有新章節處理完畢。"
        else:
            msg = "■ 全部選取的漫畫下載完成。"
        self._append_log(msg)
        self._refresh_progress()

    def _refresh_progress(self):
        done, total = db.progress_for_selected()
        if total > 0:
            self.progress_var.set(100.0 * done / total)
            self.progress_label.set(f"{done}/{total} chapters")
        else:
            self.progress_label.set("")

    # --------------------------------------------------------- clear list --
    def _clear_list(self):
        """Wipe every comic/chapter/image record (the whole list) but KEEP downloaded files."""
        if self.engine.running:
            messagebox.showwarning("清單清除", "下載進行中 — 請先「停止下載」再清除。")
            return
        n = db.count_comics()
        if not messagebox.askyesno(
                "清單清除",
                f"確定要清空全部 {n} 部漫畫的紀錄嗎？\n（已下載的檔案會保留，不會刪除）"):
            return

        def worker():
            try:
                db.clear_all()
                # drop cached thumbnails (they belong to the cleared comics)
                thumbs = config.DATA_DIR / "thumbs"
                if thumbs.exists():
                    for f in list(thumbs.glob("*.jpg")):
                        try:
                            f.unlink()
                        except Exception:
                            pass
                self.thumb_cache.clear()
                self.events.put(("log", {"message": "■ 已清空漫畫清單（下載檔案保留）。"}))
                self.events.put(("ui", self._reload_tree))
            except Exception as e:
                self.events.put(("log", {"message": f"清除出錯：{e}"}))

        threading.Thread(target=worker, daemon=True).start()

    # ------------------------------------------------------ clear downloads --
    def _clear_downloads(self):
        """Delete all downloaded files and reset download state; KEEP the comic/chapter catalog."""
        if self.engine.running:
            messagebox.showwarning("下載清除", "下載進行中 — 請先「停止下載」再清除。")
            return
        if not messagebox.askyesno(
                "下載清除",
                "確定要刪除所有已下載的圖片檔案、並重置下載狀態嗎？\n（漫畫與章節清單會保留，不需重新爬取）"):
            return

        def worker():
            try:
                db.clear_downloads_state()
                if config.DOWNLOADS_DIR.exists():
                    for child in list(config.DOWNLOADS_DIR.iterdir()):
                        try:
                            shutil.rmtree(child) if child.is_dir() else child.unlink()
                        except Exception as e:
                            self.events.put(("log", {"message": f"  刪除失敗 {child.name}: {e}"}))
                self.thumb_cache.clear()
                self.events.put(("log", {"message": "■ 已清除下載檔案並重置狀態（清單保留）。"}))
                self.events.put(("ui", self._reload_tree))
            except Exception as e:
                self.events.put(("log", {"message": f"清除出錯：{e}"}))

        threading.Thread(target=worker, daemon=True).start()

    # -------------------------------------------------------- check updates --
    def _check_updates(self):
        """Check EVERY fully-downloaded comic for new chapters on the site; download any found.

        The engine re-renders each comic's detail page and merges the chapter list (idempotent —
        existing done chapters are never re-fetched), then downloads only the genuinely new ones."""
        if self.engine.running:
            messagebox.showwarning("檢查更新", "下載進行中 — 請先「停止下載」。")
            return
        slugs = db.downloaded_path_words()
        if not slugs:
            messagebox.showinfo("檢查更新", "暫時未有任何已下載完成的漫畫。")
            return
        self.stop_btn.configure(state="normal")
        self.start_btn.configure(state="disabled")
        self._run_is_update = True
        self._append_log(f"開始檢查 {len(slugs)} 部已下載漫畫的更新…（逐部重抓章節列表，發現新章節會自動下載；可「停止下載」中斷）")
        self._active_slugs = list(slugs)
        self._current_name = ""
        self._last_dl_refresh = 0.0   # force an immediate panel refresh on the next tick
        self.engine.start(slugs, check_updates=True)

    def _check_updates_one(self, pw):
        """Per-comic version (context menu): sync this one comic with the site and download new chapters."""
        if self.engine.running:
            messagebox.showwarning("檢查更新", "下載進行中 — 請先「停止下載」。")
            return
        name = (db.get_comic(pw) or {}).get("name") or pw
        self.stop_btn.configure(state="normal")
        self.start_btn.configure(state="disabled")
        self._run_is_update = True
        self._append_log(f"檢查更新：{name}…（重抓章節列表，新章節會自動下載）")
        self._active_slugs = [pw]
        self._current_name = ""
        self._last_dl_refresh = 0.0
        self.engine.start([pw], check_updates=True)

    # --------------------------------------------------------- re-download --
    def _redownload_selected(self):
        """Fully re-download every ✓-selected comic (reset state first; keeps existing folders)."""
        if self.engine.running:
            messagebox.showwarning("重下", "下載進行中 — 請先「停止下載」。")
            return
        slugs = db.selected_path_words()
        if not slugs:
            messagebox.showinfo("重下", "尚未勾選任何漫畫（點最左的 ✓ 欄）。")
            return
        for pw in slugs:
            db.reset_comic_downloads(pw)
        self._reload_tree()
        self.stop_btn.configure(state="normal")
        self.start_btn.configure(state="disabled")
        self._run_is_update = False
        self._append_log(f"重新下載 {len(slugs)} 部…（會重抓全部頁面）")
        self._active_slugs = list(slugs)
        self.engine.start(slugs)

    def _redownload_one(self, pw):
        if self.engine.running:
            messagebox.showwarning("重下", "下載進行中 — 請先「停止下載」。")
            return
        db.reset_comic_downloads(pw)
        name = (db.get_comic(pw) or {}).get("name") or pw
        self._reload_tree()
        self.stop_btn.configure(state="normal")
        self.start_btn.configure(state="disabled")
        self._run_is_update = False
        self._append_log(f"重新下載：{name}…（會重抓全部頁面）")
        self._active_slugs = [pw]
        self.engine.start([pw])

    def _on_context_menu(self, event):
        iid = self.tree.identify_row(event.y)
        pw = self._iid_to_slug.get(iid) if iid else None
        if not pw:
            return
        c = self._c
        m = tk.Menu(self.root, tearoff=0, bg=c["panel"], fg=c["fg"],
                    activebackground=c["accent"], activeforeground="#ffffff")
        if (db.get_comic(pw) or {}).get("selected"):
            m.add_command(label="從下載列表移除", command=lambda: self._remove_from_queue(pw))
        m.add_command(label="檢查更新(此部)", command=lambda: self._check_updates_one(pw))
        m.add_command(label="重新下載此漫畫", command=lambda: self._redownload_one(pw))
        m.add_command(label="開啟原連結", command=lambda: webbrowser.open(f"{config.BASE_URL}/comic/{pw}"))
        try:
            m.tk_popup(event.x_root, event.y_root)
        finally:
            m.grab_release()

    def _open_link(self, event):
        iid = self.tree.identify_row(event.y)
        pw = self._iid_to_slug.get(iid) if iid else None
        if pw:
            webbrowser.open(f"{config.BASE_URL}/comic/{pw}")

    # ------------------------------------------------------ download location --
    def _restore_downloads_dir(self):
        """Re-apply a previously chosen download folder (persisted in DB meta) if it's usable."""
        try:
            saved = db.get_meta("downloads_dir", "") or ""
        except Exception:
            saved = ""
        if not saved:
            return
        p = Path(saved)
        if p.exists() and p.is_dir():
            config.set_downloads_dir(p)

    def _choose_downloads_dir(self):
        """Pick a new download destination; persist it so it survives restarts."""
        chosen = filedialog.askdirectory(title="選擇下載位置", initialdir=str(config.DOWNLOADS_DIR))
        if not chosen:
            return
        try:
            config.set_downloads_dir(chosen)
        except Exception as e:
            messagebox.showerror("更改下載位置", f"無法使用該資料夾：{e}")
            return
        db.set_meta("downloads_dir", str(config.DOWNLOADS_DIR))
        self.loc_var.set(str(config.DOWNLOADS_DIR))
        self._append_log(f"■ 下載位置已改為：{config.DOWNLOADS_DIR}")

    def _save_output_mode(self):
        """Persist the OUTPUT checkboxes (CBZ / WEBP) so they survive restarts."""
        db.set_meta("output_cbz", "1" if self.cbz_var.get() else "0")
        db.set_meta("output_webp", "1" if self.webp_var.get() else "0")
        parts = []
        if self.cbz_var.get():
            parts.append("CBZ ✓")
        if self.webp_var.get():
            parts.append("WEBP ✓")
        self._append_log(f"■ 輸出格式：{' + '.join(parts) if parts else '（無 — 只保留原圖）'}")

    # ------------------------------------------------------ live download panel --
    def _update_downloading_panel(self):
        """Show/refresh the '正在下載' panel (active downloads + current title), or hide when idle."""
        if not self.engine.running:
            if self._dl_visible:
                self.dl_frame.pack_forget()
                self._dl_visible = False
            return
        # ensure it's visible, sitting just above the progress bar
        if not self._dl_visible:
            self.dl_frame.pack(fill="x", **self.pad, before=self.bot)
            self._dl_visible = True
        title = "  正在下載  " + (f"— {self._current_name}  " if self._current_name else "")
        self.dl_frame.configure(text=title)
        # rebuild rows: every comic in the current run that isn't fully done yet
        stmap = db.comic_status_map()
        by_pw = {r["path_word"]: r for r in db.get_comics(limit=1000)}
        self.dl_tree.delete(*self.dl_tree.get_children())
        shown = 0
        for pw in self._active_slugs:
            st = stmap.get(pw, {})
            total = st.get("total") or st.get("ch_total", 0)
            done = st.get("done") or st.get("ch_done", 0)
            if not (total and done < total):
                continue   # skip fully-done / not-yet-started comics
            r = by_pw.get(pw, {})
            name = r.get("name") or pw
            self.dl_tree.insert("", "end", values=(name, self._progress_text(done, total)))
            shown += 1
        if shown == 0:
            self.dl_tree.insert("", "end", values=("（準備中…）", ""))

    # ------------------------------------------------------------- close --
    def _on_close(self):
        if self.engine.running:
            if not messagebox.askyesno("Quit", "A download is running. Progress is saved — you can resume later.\n\nQuit now?"):
                return
            self.engine.request_stop()
            time.sleep(0.5)
        self.root.destroy()


def hashlib_md5(s):
    import hashlib
    return hashlib.md5(s.encode()).hexdigest()[:16]


if __name__ == "__main__":
    root = tk.Tk()
    App(root)          # applies the theme in its constructor
    try:
        root.state("zoomed")   # open maximized (default full-screen display on Windows)
    except Exception:
        pass
    root.mainloop()
