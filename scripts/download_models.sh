#!/usr/bin/env bash
# Скачивает модели и sherpa-onnx AAR (в git не коммитятся).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/src/mobile/android/app"
MODELS="$APP/src/main/assets/models"
LIBS="$APP/libs"
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
mkdir -p "$MODELS" "$LIBS"

[ -f "$LIBS/sherpa-onnx.aar" ] || curl -fL -o "$LIBS/sherpa-onnx.aar" "$GH/v2.0.1/sherpa-onnx-2.0.1.aar"
[ -f "$MODELS/silero_vad.onnx" ] || curl -fL -o "$MODELS/silero_vad.onnx" "$GH/asr-models/silero_vad.onnx"

RU=sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16
if [ ! -f "$MODELS/ru/model.int8.onnx" ]; then
  tmp="$(mktemp -d)"
  curl -fL -o "$tmp/ru.tar.bz2" "$GH/asr-models/$RU.tar.bz2"
  tar -xjf "$tmp/ru.tar.bz2" -C "$tmp"
  mkdir -p "$MODELS/ru"
  cp "$tmp/$RU/model.int8.onnx" "$tmp/$RU/tokens.txt" "$MODELS/ru/"
  rm -rf "$tmp"
fi
ls -la "$MODELS" "$MODELS/ru" "$LIBS"
