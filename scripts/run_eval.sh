#!/usr/bin/env bash
# install -> push test_audio -> DebugActivity -> pull в eval/out -> score. Флаг --no-install пропускает установку.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.nuclearhack.steno
D=/sdcard/Android/data/$PKG/files
APK="$ROOT/src/mobile/android/app/build/outputs/apk/debug/app-debug.apk"
[ "${1:-}" = "--no-install" ] || adb install -r "$APK" | tail -1
# приложение должно само создать каталоги (иначе нет доступа)
adb shell am force-stop $PKG
adb shell am start -n $PKG/.DebugActivity >/dev/null; sleep 4
adb shell am force-stop $PKG
adb shell "rm -f $D/in/*; rm -rf $D/out/*"
adb push "$ROOT"/test_audio/*.wav $D/in/ | tail -1
adb logcat -c
adb shell am start -n $PKG/.DebugActivity >/dev/null
sleep 5
until adb shell "ls $D/out/DONE" >/dev/null 2>&1; do sleep 3; done
rm -rf "$ROOT/eval/out"; mkdir -p "$ROOT/eval/out"
adb pull $D/out/. "$ROOT/eval/out/" | tail -1
adb logcat -d -s STENO | grep -E "RTF|failed" | tail -10 || true
"$ROOT/.venv-eval/bin/python" -I "$ROOT/eval/score.py" "$ROOT/eval/out" "$ROOT/eval/ref" "${@:2}"
