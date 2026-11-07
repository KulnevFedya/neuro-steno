#!/usr/bin/env bash
# install -> push test_audio -> DebugActivity -> pull в eval/out -> score. Флаг --no-install пропускает установку.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.nuclearhack.steno
AUDIO="${AUDIO_DIR:-$ROOT/test_audio}"   # AUDIO_DIR/REF_DIR/MEM=1: длинные файлы и пик памяти
REF="${REF_DIR:-$ROOT/eval/ref}"
D=/sdcard/Android/data/$PKG/files
VARIANT="${VARIANT:-release}"   # VARIANT=debug — отладочная сборка
APK="$ROOT/src/mobile/android/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ "${1:-}" = "--no-install" ] || adb install -r "$APK" | tail -1
# приложение должно само создать каталоги (иначе нет доступа)
adb shell am force-stop $PKG
adb shell am start -n $PKG/.DebugActivity >/dev/null; sleep 4
adb shell am force-stop $PKG
adb shell "rm -f $D/in/*; rm -rf $D/out/*"
adb push "$AUDIO"/*.wav $D/in/ | tail -1
adb logcat -c
adb shell am start -n $PKG/.DebugActivity >/dev/null
sleep 5
if [ "${MEM:-0}" = 1 ]; then  # пик PSS приложения каждые 5 с -> eval/mem.log
  ( while :; do echo "$(date +%T) $(adb shell dumpsys meminfo $PKG | grep -E 'TOTAL PSS|TOTAL:' | head -1 | tr -s ' ')"; sleep 5; done ) > "$ROOT/eval/mem.log" 2>&1 &
  MEMPID=$!
fi
until adb shell "ls $D/out/DONE" >/dev/null 2>&1; do sleep 3; done
[ -z "${MEMPID:-}" ] || kill $MEMPID
rm -rf "$ROOT/eval/out"; mkdir -p "$ROOT/eval/out"
adb pull $D/out/. "$ROOT/eval/out/" | tail -1
adb logcat -d -s STENO | grep -E "RTF|failed" | tail -10 || true
"$ROOT/.venv-eval/bin/python" -I "$ROOT/eval/score.py" "$ROOT/eval/out" "$REF" "${@:2}"
