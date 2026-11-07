# Нейро-стенографист

Android-приложение, которое полностью офлайн превращает запись диалога в текст:
распознавание речи (RU, EN), определение говорящих (диаризация), реплики с таймкодами,
локальная LLM (пунктуация с защитой и резюме). Экспорт в JSON.

- Сборка и установка: [src/README.md](src/README.md)
- План и выбранные модели: [PLAN.md](PLAN.md)
- Архитектура: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Ограничения: [docs/LIMITATIONS.md](docs/LIMITATIONS.md)
- Результаты тестов: [docs/TEST_RESULTS.md](docs/TEST_RESULTS.md), [artifacts/test-results/results.csv](artifacts/test-results/results.csv)
- Демо: [artifacts/demo/README.md](artifacts/demo/README.md)
- APK: GitHub Releases (ссылка будет добавлена)

Модели в git не хранятся: `scripts/download_models.sh`.
