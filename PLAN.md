# PLAN.md — Нейро-стенографист

Проверено 2026-11-07 по релизам k2-fsa/sherpa-onnx (v2.0.1) и ggml-org/llama.cpp.

## 1. Модули (`src/mobile/android/`)
| Модуль | Задача |
|---|---|
| `app` | Compose UI: импорт (SAF), запись с микрофона, список реплик, плеер с переходом к реплике, экспорт в SAF/Downloads, кнопка «Резюме» |
| `audio` | MediaExtractor+MediaCodec → 16 кГц mono float, потоковое декодирование чанками |
| `asr` | Silero VAD → сегменты ≤30 с → OfflineRecognizer (RU: GigaAM v3, EN: Whisper base.en); язык: Авто/RU/EN |
| `diar` | OfflineSpeakerDiarization (pyannote-3.0 + CAM++), `numClusters` из UI или порог кластеризации |
| `merge` | спикер ASR-сегмента по максимальному перекрытию; склейка соседних реплик одного спикера при паузе <1 с; `speaker_N` по первому появлению |
| `llm` | llama.cpp JNI: пунктуация с защитой «слова не изменились» + резюме |
| `export` | JSON строго по формату CLAUDE.md; резюме отдельным файлом `<имя>.summary.json` |
| `pipeline` | foreground service, прогресс, логи `STENO` (время этапов, RTF) |
| `debug` | интент `--es in <path>` → JSON в `/sdcard/Download/steno/` для eval |

## 2. Модели
| Роль | Модель | Размер | Источник |
|---|---|---|---|
| ASR RU | `sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16` (`model.int8.onnx`+`tokens.txt`), с пунктуацией и регистром | 214 МБ | release `asr-models` k2-fsa/sherpa-onnx (162/155 МБ в архиве); HF `csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16` |
| ASR RU запасной | `…transducer-punct-giga-am-v3…` (обычно точнее) | ~214 МБ | тот же релиз |
| ASR EN | `sherpa-onnx-whisper-base.en` int8 (enc 27 + dec 124); запас: tiny.en (12+85) | 151 МБ | HF `csukuangfj/sherpa-onnx-whisper-base.en` |
| VAD | `silero_vad.onnx` | ~2 МБ | release `asr-models` |
| Сегментация | `sherpa-onnx-pyannote-segmentation-3-0` | 6 МБ | release `speaker-segmentation-models` |
| Эмбеддинги | `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx` (альт.: `nemo_en_titanet_small` 38 МБ, `wespeaker_en_voxceleb_resnet34_LM` 25 МБ) | 28 МБ | release `speaker-recongition-models` |
| LLM | `qwen2.5-0.5b-instruct-q4_0.gguf` (альт.: Qwen3-0.6B Q4_0, 364 МБ) | 408 МБ | HF `Qwen/Qwen2.5-0.5B-Instruct-GGUF` |
| Нативные библиотеки | sherpa-onnx AAR 2.0.1 (47 МБ, в APK arm64 ~30) + llama.cpp .so (~5) | ~35 МБ | GitHub release v2.0.1 |

**Итого APK ≈ 845 МБ** (модели в assets без сжатия, `androidResources.noCompress`). Урезать при необходимости: Whisper → tiny.en (−54), LLM → Qwen3-0.6B (−44).

Поддержка Android API подтверждена: Kotlin API sherpa-onnx содержит `OfflineRecognizer` (`OfflineNemoEncDecCtcModelConfig`, whisper), `Vad`, `OfflineSpeakerDiarization` (pyannote + embedding + `FastClusteringConfig(numClusters, threshold)`), `OfflinePunctuation`. Релизный экспорт GigaAM v3 CTC/transducer, в том числе `punct`, создаётся workflow sherpa-onnx.

## 3. Память (4 ГБ)
Последовательные этапы с `release()` между ними: декод → VAD+ASR → release → диаризация → release → LLM (пунктуация по необходимости; резюме по кнопке) → free.
Оценка пика: PCM 1 ч float ≈ 230 МБ, ASR ~400 МБ, диаризация ~100 МБ, LLM ~600 МБ (n_ctx=2048). Для длинных записей PCM держим в файле/mmap и читаем окнами. Модели из assets копируем в `filesDir` при первом запуске; llama грузит через mmap.

## 4. llama.cpp под arm64 Android
Официальный пример `examples/llama.android` (модуль `lib`): CMake `add_subdirectory(llama.cpp)` + JNI `ai_chat.cpp`; для arm64 включает `GGML_CPU_KLEIDIAI`, `GGML_OPENMP`, `GGML_NATIVE=OFF`; у примера minSdk 33, NDK 29.
Наш вариант: llama.cpp как git submodule с зафиксированным тегом, свой JNI (~150 строк: load/generate/free), `abiFilters arm64-v8a` (+x86_64 для эмулятора), `-DLLAMA_CURL=OFF -DGGML_NATIVE=OFF -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release`, minSdk 26. Если не соберётся при minSdk 26, поднимаем до 28/33 и пишем в NOTES.md.
Параметры: n_threads=4, n_ctx=2048, greedy. Пунктуация: чанки ~200 слов, принимаем только если `normalize(слова)` не изменилась. Резюме: map-reduce по чанкам.
Локально есть NDK 30.0.16248370 и arm64 эмулятор (`emulator-5554`); JDK 25 слишком новый для AGP, нужен JDK 17/21.

## 5. Eval
- `eval/score.py <out> <ref>`: WER (jiwer; ё→е, регистр; с пунктуацией и без), DER (покадровая, спикеры сопоставляются венгерским алгоритмом), ошибка таймкодов (средняя |Δstart|,|Δend| по сопоставленным репликам), RTF из `<name>.meta.json`; строки в `artifacts/test-results/results.csv`.
- `scripts/run_eval.sh`: install → push `test_audio/*.wav` → запуск debug-интента → ожидание → `adb pull` в `eval/out/` → score.
- Данные: 6 синтетических файлов в `test_audio/`, эталоны `eval/ref/*.json` (генератор `eval/gen_dialogs.py`).

## 6. Скрипты
`scripts/download_models.sh` качает и распаковывает модели в `app/src/main/assets/models/` (в git не попадают).

## 7. Риски и запасные пути
- GigaAM CTC медленный или неточный → transducer-punct, либо без пунктуации + LLM.
- llama.cpp не собирается при minSdk 26 → поднять minSdk.
- APK >1 ГБ → whisper tiny.en.
- Диаризация плохо работает на коротких репликах → ручной `numClusters`, уменьшение `minDurationOff`.

## 8. Порядок работ
Фазы 1–5 из KICKOFF.md: скелет → диаризация → LLM → длинные файлы/EN/RTF → документация и релиз. После 22:00 новых функций нет.
