#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
command -v python >/dev/null || { echo "Python missing. Run: pkg install python"; exit 1; }
command -v adb >/dev/null || { echo "ADB missing. Run: pkg install android-tools"; exit 1; }
python server.py
