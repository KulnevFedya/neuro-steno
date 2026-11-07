# Методика и результаты тестирования

## Методика
`python3 eval/score.py eval/out eval/ref` сравнивает JSON приложения с эталоном:
WER (ё=е, без регистра; с пунктуацией и без), DER, ошибка таймкодов, RTF.
Тестовые записи: `test_audio/` (RU 2/3 спикера, EN 2 спикера, чистые и с шумом).

## Результаты
Таблица: [artifacts/test-results/results.csv](../artifacts/test-results/results.csv).
