# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for the MangaCopy downloader.

Builds a ONE-DIR (portable) app:  dist/MangaCopy/MangaCopy.exe  +  _internal/
The Chromium browser is NOT bundled here — build.bat installs it into
dist/MangaCopy/playwright-browsers/ afterwards, which is exactly where the
frozen app looks for it (see cm/config.py).

Set MANGACOPY_CONSOLE=1 to produce a console build (useful for debugging);
the default is a windowed GUI with no console window.
"""
import os
from PyInstaller.utils.hooks import collect_all

datas = []
binaries = []
hiddenimports = []

# Playwright ships a Node.js driver + CLI as data files; bundle all of it so the
# frozen exe can launch Chromium without an external Python install.
pw_datas, pw_binaries, pw_hidden = collect_all("playwright")
datas += pw_datas
binaries += pw_binaries
hiddenimports += pw_hidden

# Modules imported indirectly that PyInstaller's analysis can occasionally miss.
for hi in ("requests", "bs4", "lxml", "lxml.etree",
           "PIL", "PIL.Image",            # lazy import inside cm/outputs.py (WEBP conversion)
           "cm", "cm.config", "cm.db", "cm.listing", "cm.download", "cm.browser", "cm.engine", "cm.outputs"):
    if hi not in hiddenimports:
        hiddenimports.append(hi)

console = os.environ.get("MANGACOPY_CONSOLE", "0") == "1"

a = Analysis(
    ["app.py"],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,          # one-dir: shared binaries live in _internal/
    name="MangaCopy",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,                      # UPX can corrupt the Playwright driver — leave off
    console=console,                # windowed GUI by default; MANGACOPY_CONSOLE=1 for a terminal
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="MangaCopy",
)
