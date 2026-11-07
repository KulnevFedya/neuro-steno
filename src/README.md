# Сборка и установка

Платформа: `mobile/android/` (Kotlin, Jetpack Compose, minSdk 26, targetSdk 36, только arm64-v8a).

## Требования
- macOS или Linux, ~6 ГБ свободного места (модели ~1 ГБ, сборка llama.cpp и APK).
- **JDK 17** (проверено на Zulu 17; JDK 25 из свежего Android Studio Gradle не поддерживает).
  Указать Gradle: `org.gradle.java.home=/path/to/jdk17` в `~/.gradle/gradle.properties` или `JAVA_HOME`.
- Android SDK (platform 36), **NDK 30.0.16248370**, **CMake 4.1.2** (ставятся через SDK Manager).
- `curl`, `tar`, `bzip2` для скачивания моделей.

## Сборка с нуля
```bash
git clone https://github.com/zzzatoox/neuro-steno.git && cd neuro-steno
git submodule update --init                 # llama.cpp (тег b12183)
scripts/download_models.sh                  # модели → src/mobile/android/app/src/main/assets/models/, AAR sherpa-onnx → app/libs/
cd src/mobile/android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # путь к Android SDK
./gradlew assembleRelease                   # app/build/outputs/apk/release/app-release.apk (~964 МБ)
./gradlew testDebugUnitTest                 # JVM-тесты: формат JSON, склейка реплик, нормализация, Recluster
adb install -r app/build/outputs/apk/release/app-release.apk
```
Первая сборка ~5–10 мин (llama.cpp компилируется статически в `libsteno_llama.so`).

### Подпись
Release подписывается ключом `mobile/android/keystore/release.jks`; пароль и алиас лежат прямо в
`app/build.gradle.kts`. Это допущение хакатона (ключ не секретный, нужен для воспроизводимой сборки), см.
[docs/LIMITATIONS.md](../docs/LIMITATIONS.md). Для своего ключа замените `signingConfigs.release`.
Debug-сборка (`assembleDebug`) подписана debug-ключом: при смене варианта нужен `adb uninstall com.nuclearhack.steno`.

## Запуск eval
Нужны Python 3.11+ и venv с зависимостями оценки:
```bash
python3 -m venv .venv-eval && .venv-eval/bin/pip install jiwer numpy scipy num2words
```
Пакетный прогон на подключённом устройстве/эмуляторе (adb):
```bash
scripts/run_eval.sh                                   # установка release APK + все test_audio/*.wav, язык Авто
GLOB='en_*.wav' EVAL_LANG=en scripts/run_eval.sh      # только EN, язык задан вручную
SPK=3 scripts/run_eval.sh --no-install                # заданное число спикеров, без переустановки
VARIANT=debug scripts/run_eval.sh                     # отладочная сборка
```
Скрипт кладёт аудио в `/sdcard/Android/data/com.nuclearhack.steno/files/in`, запускает
`DebugActivity` (`adb shell am start -n com.nuclearhack.steno/.DebugActivity --es lang auto --ei spk -1`),
ждёт `out/DONE`, забирает JSON в `eval/out/` и вызывает `eval/score.py eval/out eval/ref`, который
печатает WER / WER без пунктуации / DER / ошибку таймкодов / RTF и дописывает строки в
`artifacts/test-results/results.csv`. Методика — [docs/TEST_RESULTS.md](../docs/TEST_RESULTS.md).

`DebugActivity` защищена разрешением `android.permission.DUMP`: его имеет adb shell, сторонние
приложения — нет.

Логи этапов и RTF: `adb logcat -s STENO`.
