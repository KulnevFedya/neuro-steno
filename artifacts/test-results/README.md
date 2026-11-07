# Артефакты тестирования

`results.csv` — все прогоны eval (по строке на файл), дописывается `eval/score.py`.
Колонки: `test_id,timestamp_utc,commit,file_name,duration_seconds,lang,num_speakers,wer,wer_no_punct,der,ts_error_ms,rtf,device,notes`.
Методика и итоговые таблицы — [docs/TEST_RESULTS.md](../../docs/TEST_RESULTS.md). RTF сравнивать только
внутри одной сессии эмулятора (см. колонку notes); итоговые — строки `clean RTF` и `LID auto`.
