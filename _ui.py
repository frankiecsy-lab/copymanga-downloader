import re, subprocess, sys
# 臨時 E2E helper：dump 目前畫面，輸出所有非空 text ＋ 中心座標（用完即刪）
ADB = r"D:\android-tools\sdk\platform-tools\adb.exe"
subprocess.run([ADB, "-s", "WOK7DIR46L65XGZH", "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
               capture_output=True)
xml = subprocess.run([ADB, "-s", "WOK7DIR46L65XGZH", "shell", "cat", "/sdcard/ui.xml"],
                     capture_output=True, text=True, encoding="utf-8").stdout
pat = r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
for m in re.finditer(pat, xml):
    t = m.group(1).strip()
    if t:
        cx = (int(m.group(2)) + int(m.group(4))) // 2
        cy = (int(m.group(3)) + int(m.group(5))) // 2
        print(f"{t} | {cx} {cy}")
