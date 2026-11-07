#!/usr/bin/env bash
# Транскрипция + резюме одного файла на эмуляторе/устройстве: scripts/run_summary.sh <wav> [--no-install]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.nuclearhack.steno; D=/sdcard/Android/data/$PKG/files
[ "${2:-}" = "--no-install" ] || adb install -r "$ROOT/src/mobile/android/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell am force-stop $PKG; adb shell am start -n $PKG/.DebugActivity >/dev/null; sleep 4; adb shell am force-stop $PKG
adb shell "rm -f $D/in/*; rm -rf $D/out/*"
adb push "$1" $D/in/ | tail -1
adb logcat -c
adb shell am start -n $PKG/.DebugActivity --ez summary true >/dev/null
sleep 5
until adb shell "ls $D/out/DONE" >/dev/null 2>&1; do sleep 3; done
adb logcat -d -s STENO | grep -E "llm loaded|summary|map |final|RTF|failed|Exception" | cut -c19- | tail -40
n=$(basename "$1" .wav)
adb shell cat $D/out/$n.summary.json
