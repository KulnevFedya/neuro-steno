# NOTES — принятые решения
- ASR RU: GigaAM v3 CTC с пунктуацией (int8, 214 МБ): пунктуация из модели, LLM остаётся для резюме и защитной правки.
- ASR EN: Whisper base.en int8, запас tiny.en по размеру/RTF.
- Диаризация: pyannote-3.0 + 3D-Speaker CAM++ (28 МБ); кластеризация с порогом, `numClusters` из UI.
- LLM: Qwen2.5-0.5B-Instruct Q4_0 (быстрее на ARM, чем Q4_K_M).
- llama.cpp: submodule + свой JNI вместо модуля `llama.android` (у него minSdk 33).
- Репозиторий: `config/example.env` и `docs/PROTOCOL.md` из шаблона не переносим (чужой кейс).
- `test_audio/*.wav` коммитим (по ~1 МБ, нужны для eval), модели и APK нет.
