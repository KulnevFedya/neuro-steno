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

RU=sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
if [ ! -f "$MODELS/ru/encoder.int8.onnx" ]; then
  tmp="$(mktemp -d)"
  curl -fL -o "$tmp/ru.tar.bz2" "$GH/asr-models/$RU.tar.bz2"
  tar -xjf "$tmp/ru.tar.bz2" -C "$tmp"
  mkdir -p "$MODELS/ru"
  cp "$tmp/$RU/"{encoder.int8.onnx,decoder.onnx,joiner.onnx,tokens.txt} "$MODELS/ru/"
  rm -rf "$tmp"
fi

mkdir -p "$MODELS/en"
for f in base.en-encoder.int8.onnx base.en-decoder.int8.onnx base.en-tokens.txt; do
  [ -f "$MODELS/en/$f" ] || curl -fL -o "$MODELS/en/$f" "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en/resolve/main/$f"
done

mkdir -p "$MODELS/diar"
if [ ! -f "$MODELS/diar/seg.int8.onnx" ]; then
  tmp="$(mktemp -d)"
  curl -fL -o "$tmp/seg.tar.bz2" "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
  tar -xjf "$tmp/seg.tar.bz2" -C "$tmp"
  cp "$tmp/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx" "$MODELS/diar/seg.int8.onnx"
  rm -rf "$tmp"
fi
[ -f "$MODELS/diar/titanet_small.onnx" ] || curl -fL -o "$MODELS/diar/titanet_small.onnx" "$GH/speaker-recongition-models/nemo_en_titanet_small.onnx"
mkdir -p "$MODELS/llm"
[ -f "$MODELS/llm/qwen2.5-0.5b-instruct-q4_0.gguf" ] || curl -fL -o "$MODELS/llm/qwen2.5-0.5b-instruct-q4_0.gguf" \
  https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf
ls -la "$MODELS" "$MODELS/ru" "$MODELS/diar" "$LIBS"
