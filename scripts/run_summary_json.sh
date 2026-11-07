#!/usr/bin/env bash
# Резюме по готовой расшифровке (JSON из eval/out): scripts/run_summary_json.sh <transcript.json> [--no-install]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.nuclearhack.steno; D=/sdcard/Android/data/$PKG/files
[ "${2:-}" = "--no-install" ] || adb install -r "$ROOT/src/mobile/android/app/build/outputs/apk/${VARIANT:-release}/app-${VARIANT:-release}.apk" | tail -1
adb shell am force-stop $PKG; adb shell am start -n $PKG/.DebugActivity >/dev/null; sleep 4; adb shell am force-stop $PKG
adb shell "rm -f $D/in/*; rm -rf $D/out/*"
adb push "$1" $D/in/ | tail -1
adb logcat -c
adb shell am start -n $PKG/.DebugActivity >/dev/null
sleep 5
until adb shell "ls $D/out/DONE" >/dev/null 2>&1; do sleep 3; done
adb logcat -d -s STENO | grep -E "llm loaded|summary|map |final|failed|Exception" | cut -c19- | tail -${LOGLINES:-12}
adb shell cat $D/out/$(basename "$1" .json).summary.json
