# Сборка и установка

Платформа: `mobile/android/` (Kotlin, Jetpack Compose, minSdk 26, arm64-v8a).

## Требования
- Android SDK, NDK 30.0.16248370, JDK 17 или 21, CMake из SDK.
- ~1 ГБ свободного места под модели и сборку.

## Шаги
1. `git submodule update --init` (llama.cpp).
2. `scripts/download_models.sh` скачивает модели в `src/mobile/android/app/src/main/assets/models/`.
3. `cd src/mobile/android && ./gradlew assembleDebug`.
4. `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

_Раздел будет уточнён после появления проекта._
