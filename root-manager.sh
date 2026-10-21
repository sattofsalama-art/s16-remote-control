#!/data/data/com.termux/files/usr/bin/bash
# Manage adbd root over the receiver's localhost connection.
# No bootloader unlocking, firmware changes, system remount or file modification.
set -u
SERIAL="127.0.0.1:5555"
MODE="${1:-check}"

adb_local() { adb -s "$SERIAL" "$@"; }
connect() { adb connect "$SERIAL" 2>&1; }
status() {
  local result build debuggable
  result="$(adb_local shell id 2>&1)" || { echo "ADB unavailable: $result"; return 1; }
  build="$(adb_local shell getprop ro.build.type 2>/dev/null | tr -d '\r')"
  debuggable="$(adb_local shell getprop ro.debuggable 2>/dev/null | tr -d '\r')"
  echo "Build: $build | ro.debuggable: $debuggable"
  echo "ADB identity: $result"
  if [[ "$result" == *"uid=0(root)"* ]]; then
    echo "RESULT: ADB ROOT ENABLED"
  else
    echo "RESULT: ADB shell only (not root)"
  fi
}
reconnect() {
  local i
  for i in 1 2 3 4 5; do
    sleep 2
    connect >/dev/null 2>&1 || true
    if adb_local shell id >/dev/null 2>&1; then return 0; fi
  done
  echo "ADB did not reconnect on $SERIAL after adbd restarted."
  echo "Check the receiver's Network Debugging setting; do not flash firmware."
  return 1
}

if ! command -v adb >/dev/null 2>&1; then
  echo "Install ADB first: pkg install android-tools"
  exit 1
fi

case "$MODE" in
  check)
    connect >/dev/null 2>&1 || true
    status
    ;;
  enable)
    connect >/dev/null 2>&1 || true
    build="$(adb_local shell getprop ro.build.type 2>/dev/null | tr -d '\r')" || exit 1
    debuggable="$(adb_local shell getprop ro.debuggable 2>/dev/null | tr -d '\r')" || exit 1
    if [[ "$debuggable" != "1" || ( "$build" != "userdebug" && "$build" != "eng" ) ]]; then
      echo "This build is not eligible for adb root. No changes made."
      exit 1
    fi
    echo "WARNING: adb root may restart ADB, break the remote connection,"
    echo "and expose powerful privileges on TCP port 5555 to others on your LAN."
    echo "Use only on a private trusted network. NEVER port-forward ADB."
    read -r -p "Type ROOT to attempt enabling ADB root: " confirmation
    if [[ "$confirmation" != "ROOT" ]]; then
      echo "Cancelled."
      exit 1
    fi
    echo "Requesting root via ADB..."
    adb_local root 2>&1 || true
    reconnect || exit 1
    status
    echo "When finished, run: bash root-manager.sh disable"
    ;;
  disable)
    connect >/dev/null 2>&1 || true
    echo "Returning adbd to normal shell permissions..."
    adb_local unroot 2>&1 || true
    reconnect || exit 1
    status
    ;;
  *)
    echo "Usage: bash root-manager.sh [check|enable|disable]"
    exit 2
    ;;
esac
