#!/usr/bin/env python3
"""Оценка: python3 eval/score.py <out_dir> <ref_dir> [--commit X] [--device Y] [--notes Z] [--no-csv]

Для каждого <name>.json в out_dir ищет <name>.json в ref_dir. Метрики:
  wer          — слова с пунктуацией и регистром (ё=е), токены по пробелам
  (числа → слова с обеих сторон, num2words)
  wer_no_punct — без пунктуации, нижний регистр, ё=е
  der          — покадровая (10 мс), спикеры сопоставлены перебором перестановок
  ts_error_ms  — средняя |Δstart|,|Δend| по эталонным репликам (против перекрывающихся гипотез)
  rtf          — из <name>.meta.json
Строки дописываются в artifacts/test-results/results.csv.
"""
import argparse, csv, datetime, itertools, json, os, re, subprocess, sys, wave

import jiwer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(ROOT, "artifacts", "test-results", "results.csv")
COLUMNS = "test_id,timestamp_utc,commit,file_name,duration_seconds,lang,num_speakers,wer,wer_no_punct,der,ts_error_ms,rtf,device,notes".split(",")


def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)["transcript"]


MONTHS = "января февраля марта апреля мая июня июля августа сентября октября ноября декабря".split()
UNITS = {"%": "процентов", "кг": "килограммов", "км": "километров", "м": "метров", "г": "граммов"}
_NUM = re.compile(r"(\d+)\s*(%|кг|км|м|г)?(?![\w])", re.I)
_DATE = re.compile(r"(\d+)(\s+(?:%s))" % "|".join(MONTHS), re.I)


def numwords(s):
    """Числа -> слова (ru, для латиницы en) с обеих сторон WER; в приложении не применяется."""
    from num2words import num2words
    lang = "ru" if re.search("[а-яА-ЯёЁ]", s) else "en"

    def date(m):
        return num2words(int(m.group(1)), lang="ru", to="ordinal") .replace("ый", "ого").replace("ий", "ого").replace("ой", "ого") + m.group(2)

    def num(m):
        w = num2words(int(m.group(1)), lang=lang)
        u = (m.group(2) or "").lower()
        return w + (" " + UNITS[u] if u and lang == "ru" else (" " + m.group(2) if u else ""))
    if lang == "ru":
        s = _DATE.sub(date, s)
    return _NUM.sub(num, s)


def norm_punct(s):  # с пунктуацией: только ё=е
    s = numwords(s)
    return re.sub(r"\s+", " ", s.replace("ё", "е").replace("Ё", "Е")).strip()


def norm_plain(s):
    s = numwords(s).lower().replace("ё", "е")
    s = re.sub(r"[^\w\s]|_", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def wer(ref, hyp, f):
    r, h = f(" ".join(u["text"] for u in ref)), f(" ".join(u["text"] for u in hyp))
    if not r:
        return 0.0 if not h else 1.0
    if not h:
        return 1.0
    return jiwer.wer(r, h)


def der(ref, hyp, step=10):
    end = max([u["end_ms"] for u in ref + hyp] + [0])
    n = end // step + 1
    rl, hl = [None] * n, [None] * n
    for lab, us in ((rl, ref), (hl, hyp)):
        for u in us:
            for i in range(u["start_ms"] // step, min(n, u["end_ms"] // step)):
                lab[i] = u["speaker_id"]
    rs, hs = sorted({x for x in rl if x}), sorted({x for x in hl if x})
    speech = sum(1 for x in rl if x)
    if speech == 0:
        return 0.0
    miss = sum(1 for a, b in zip(rl, hl) if a and not b)
    fa = sum(1 for a, b in zip(rl, hl) if b and not a)
    best = None
    if not hs:
        best = 0
    else:
        pool = list(rs) + [None] * len(hs)
        seen = set()
        for perm in itertools.permutations(pool, len(hs)):
            if perm in seen:
                continue
            seen.add(perm)
            m = dict(zip(hs, perm))
            ok = sum(1 for a, b in zip(rl, hl) if a and b and m[b] == a)
            best = ok if best is None else max(best, ok)
    both = sum(1 for a, b in zip(rl, hl) if a and b)
    conf = both - best
    return (miss + fa + conf) / speech


def ts_error(ref, hyp):
    errs = []
    for r in ref:
        ov = [h for h in hyp if min(h["end_ms"], r["end_ms"]) - max(h["start_ms"], r["start_ms"]) > 0]
        if not ov:
            continue
        errs.append((abs(min(h["start_ms"] for h in ov) - r["start_ms"]) + abs(max(h["end_ms"] for h in ov) - r["end_ms"])) / 2)
    return sum(errs) / len(errs) if errs else float("nan")


def git_commit():
    try:
        return subprocess.check_output(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"], text=True).strip()
    except Exception:
        return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out"); ap.add_argument("ref")
    ap.add_argument("--commit", default=git_commit()); ap.add_argument("--device", default="emulator-arm64")
    ap.add_argument("--notes", default=""); ap.add_argument("--no-csv", action="store_true")
    a = ap.parse_args()
    rows, now = [], datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    for fn in sorted(os.listdir(a.out)):
        if not fn.endswith(".json") or fn.endswith(".meta.json"):
            continue
        name = fn[:-5]
        rp = os.path.join(a.ref, fn)
        if not os.path.exists(rp):
            continue
        hyp, ref = load(os.path.join(a.out, fn)), load(rp)
        meta = {}
        mp = os.path.join(a.out, name + ".meta.json")
        if os.path.exists(mp):
            meta = json.load(open(mp))
        wp = os.path.join(ROOT, "test_audio", name + ".wav")
        dur = ""
        if os.path.exists(wp):
            with wave.open(wp) as w:
                dur = round(w.getnframes() / w.getframerate(), 1)
        elif meta:
            dur = round(meta["audio_ms"] / 1000, 1)
        row = dict(
            test_id=f"{now}_{name}", timestamp_utc=now, commit=a.commit, file_name=name + ".wav",
            duration_seconds=dur, lang="ru" if any(re.search("[а-яА-ЯёЁ]", u["text"]) for u in ref) else "en", num_speakers=len({u["speaker_id"] for u in ref}),
            wer=round(wer(ref, hyp, norm_punct), 4), wer_no_punct=round(wer(ref, hyp, norm_plain), 4),
            der=round(der(ref, hyp), 4), ts_error_ms=round(ts_error(ref, hyp), 0),
            rtf=round(meta["rtf"], 3) if "rtf" in meta else "", device=a.device, notes=a.notes,
        )
        rows.append(row)
        print(f"{name:28s} WER={row['wer']:.3f} WERnp={row['wer_no_punct']:.3f} DER={row['der']:.3f} ts={row['ts_error_ms']} RTF={row['rtf']}")
    if rows:
        for k in ("wer", "wer_no_punct", "der", "rtf"):
            vals = [r[k] for r in rows if r[k] != ""]
            if vals:
                print(f"mean {k}: {sum(vals)/len(vals):.3f}", end="  ")
        print()
    if rows and not a.no_csv:
        os.makedirs(os.path.dirname(CSV_PATH), exist_ok=True)
        new = not os.path.exists(CSV_PATH) or os.path.getsize(CSV_PATH) == 0
        with open(CSV_PATH, "a", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, COLUMNS)
            if new:
                w.writeheader()
            w.writerows(rows)


if __name__ == "__main__":
    main()
