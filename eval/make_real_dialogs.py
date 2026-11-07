#!/usr/bin/env python3
"""Тестовые диалоги из ЖИВОЙ речи: test_audio/real_*.wav + eval/ref/real_*.json (формат ТЗ).

python3 -I eval/make_real_dialogs.py --emb <titanet_small.onnx> [--cache <dir>] [--n-clips 300] [--seed 7]

RU (real_ru_2spk, real_ru_3spk): клипы bond005/sova_rudevices (test), speaker_id в датасете нет →
  эмбеддинги titanet, группы одного голоса (косинус к центроиду > 0.75, ≥6 клипов), выбор групп с
  минимальным попарным косинусом центроидов (< 0.3), по возможности разный пол (оценка F0).
  Диалог: чередование, паузы 200–800 мс, 2–3 перебивания с наложением 200–400 мс, 1–2 короткие реплики,
  громкость ±3 дБ. Эталон: текст датасета (заглавная + точка), таймкоды по построению (речь без тишины по краям).
EN (real_en_ami): edinburghcstr/ami, sdm, test — клипы сегментов кладутся на исходную временную ось
  (begin_time), реальные наложения сохраняются; окно ~150 с; speaker_id → speaker_N по первому появлению.
"""
import argparse, io, json, os, re, sys, wave

import numpy as np, pyarrow.parquet as pq, sherpa_onnx as so, soundfile as sf
from huggingface_hub import hf_hub_download
from scipy.signal import resample_poly

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SR = 16000
SOVA = [("bond005/sova_rudevices", "data/test-00000-of-00002-e3baf0fee2116c5f.parquet"),
        ("bond005/sova_rudevices", "data/test-00001-of-00002-70b0ab0d98d48e85.parquet")]
AMI = ("edinburghcstr/ami", "sdm/test-00000-of-00004.parquet")
BAD = re.compile(r"ху[йеёия]|пизд|бля|[её]б[аеу]|сука|мудак")


def decode(b):
    x, sr = sf.read(io.BytesIO(b), dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    if sr != SR:
        g = np.gcd(sr, SR); x = resample_poly(x, SR // g, sr // g).astype(np.float32)
    return x


def trim(x, db=-35, margin=0.05):
    """Срезать тишину по краям: кадры 20 мс с RMS ниже пика-35 дБ."""
    f = 320
    n = len(x) // f
    if n < 3:
        return x
    rms = np.sqrt((x[: n * f].reshape(n, f) ** 2).mean(axis=1) + 1e-12)
    on = np.where(20 * np.log10(rms / rms.max()) > db)[0]
    m = int(margin * SR)
    return x[max(0, on[0] * f - m): min(len(x), (on[-1] + 1) * f + m)]


def f0(x):
    """Грубая медиана F0 (автокорреляция по озвученным кадрам 40 мс), Гц."""
    f, res = 640, []
    for i in range(0, len(x) - f, f):
        fr = x[i:i + f] - x[i:i + f].mean()
        if np.sqrt((fr ** 2).mean()) < 0.02 * np.abs(x).max():
            continue
        ac = np.correlate(fr, fr, "full")[f - 1:]
        lo, hi = SR // 350, SR // 70
        k = lo + int(np.argmax(ac[lo:hi]))
        if ac[k] > 0.4 * ac[0]:
            res.append(SR / k)
    return float(np.median(res)) if res else 0.0


def ref_text(t):
    t = re.sub(r"\s+", " ", t).strip()
    t = re.sub(r"\bi\b", "I", t) if re.search("[a-z]", t) else t
    return (t[:1].upper() + t[1:]).rstrip(".") + "."


def rms_db(x):
    return 20 * np.log10(np.sqrt((x ** 2).mean()) + 1e-9)


def write(name, y, utts):
    y = np.clip(y, -1, 1)
    with wave.open(os.path.join(ROOT, "test_audio", name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((y * 32767).astype(np.int16).tobytes())
    utts = sorted(utts, key=lambda u: (u["start_ms"], u["end_ms"]))
    order = {}
    for u in utts:
        order.setdefault(u["speaker_id"], f"speaker_{len(order) + 1}")
    out = [{"speaker_id": order[u["speaker_id"]], "start_ms": u["start_ms"], "end_ms": u["end_ms"], "text": u["text"]} for u in utts]
    with open(os.path.join(ROOT, "eval", "ref", name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"transcript": out}, f, ensure_ascii=False, indent=1)
    print(f"{name}: {len(y) / SR:.1f} c, {len(out)} реплик, {len(order)} спикеров", file=sys.stderr)


# ---------------- RU ----------------

def load_sova(cache, n_clips):
    clips = []
    for repo, fn in SOVA:
        p = hf_hub_download(repo, fn, repo_type="dataset", local_dir=cache)
        pf = pq.ParquetFile(p)
        for g in range(pf.num_row_groups):
            for r in pf.read_row_group(g).to_pylist():
                t = (r["transcription"] or "").strip()
                if not t or BAD.search(t):
                    continue
                x = trim(decode(r["audio"]["bytes"]))
                d = len(x) / SR
                if 0.7 <= d <= 8:
                    clips.append({"x": x, "text": t, "dur": d})
            if sum(c["dur"] >= 2 for c in clips) >= n_clips:
                return clips
    return clips


def embed(ext, x):
    s = ext.create_stream(); s.accept_waveform(SR, x); s.input_finished()
    e = np.array(ext.compute(s)); return e / np.linalg.norm(e)


def find_groups(E, thr=0.75, min_n=6):
    """Жадно: клип с наибольшим числом соседей (cos > thr) → группа; уточняем по центроиду; удаляем."""
    free = np.ones(len(E), bool)
    S = E @ E.T
    groups = []
    while True:
        cnt = ((S > thr) & free[None, :] & free[:, None]).sum(1)
        i = int(np.argmax(cnt))
        if cnt[i] < min_n:
            break
        g = np.where((S[i] > thr) & free)[0]
        for _ in range(3):
            c = E[g].mean(0); c /= np.linalg.norm(c)
            g = np.where((E @ c > thr) & free)[0]
        if len(g) < min_n:
            free[i] = False; continue
        free[g] = False
        groups.append(g)
    return groups


def pick(groups, cents, gender, k, max_cos=0.3):
    import itertools
    best = None
    for comb in itertools.combinations(range(len(groups)), k):
        mc = max(float(cents[a] @ cents[b]) for a, b in itertools.combinations(comb, 2))
        if mc >= max_cos:
            continue
        mixed = len({gender[c] for c in comb}) > 1
        score = (mixed, sum(min(len(groups[c]), 14) for c in comb), -mc)
        if best is None or score > best[0]:
            best = (score, comb, mc)
    return best


def build_dialog(rng, spk_clips, short_clips, target_s=120):
    """spk_clips[s]: список клипов. Чередование, паузы, наложения, короткие реплики, громкость ±3 дБ."""
    k = len(spk_clips)
    pools = [list(rng.permutation(len(c))) for c in spk_clips]
    gain_spk = rng.uniform(-2, 2, k)
    n_short = int(rng.integers(1, 3))
    turns, last, total = [], -1, 0.0
    while total < target_s:
        cand = [s for s in range(k) if s != last and pools[s]]
        if not cand:
            break
        s = int(rng.choice(cand)) if k > 2 else cand[0]
        turns.append((s, spk_clips[s][pools[s].pop()]))
        total += turns[-1][1]["dur"] + 0.5
        last = s
    # короткие реплики вставляем в середину (между разными спикерами)
    for _ in range(n_short):
        pos = int(rng.integers(2, len(turns) - 2))
        a, b = turns[pos - 1][0], turns[pos][0]
        cand = [s for s in range(k) if s not in (a, b) and short_clips[s]] or \
               [s for s in range(k) if s != a and short_clips[s]]
        if not cand:
            continue
        s = int(rng.choice(cand))
        turns.insert(pos, (s, short_clips[s].pop()))
    n_ov = int(rng.integers(2, 4))
    ov_at = set(rng.choice(np.arange(3, len(turns)), n_ov, replace=False).tolist())
    y = np.zeros(int((total + 30) * SR), np.float32)
    t, utts = 0.5, []
    prev_end = 0.5
    for i, (s, c) in enumerate(turns):
        if i in ov_at:
            st = prev_end - rng.uniform(0.2, 0.4)
        else:
            st = prev_end + (rng.uniform(0.2, 0.8) if i else 0)
        x = c["x"] / (10 ** (rms_db(c["x"]) / 20)) * 10 ** ((-24 + gain_spk[s] + rng.uniform(-1.5, 1.5)) / 20)
        a = int(st * SR)
        y[a:a + len(x)] += x
        end = st + len(x) / SR
        utts.append({"speaker_id": str(s), "start_ms": int(round(st * 1000)), "end_ms": int(round(end * 1000)),
                     "text": ref_text(c["text"])})
        prev_end = end
    y = y[: int((prev_end + 0.5) * SR)]
    y += rng.normal(0, 10 ** (-60 / 20), len(y)).astype(np.float32)  # фон −60 дБ вместо цифровой тишины
    return y, utts


def make_ru(a, ext):
    n = a.n_clips
    while True:
        clips = load_sova(a.cache, n)
        long_ = [c for c in clips if c["dur"] >= 2]
        E = np.stack([embed(ext, c["x"]) for c in long_])
        groups = find_groups(E)
        cents = []
        for g in groups:
            c = E[g].mean(0); cents.append(c / np.linalg.norm(c))
        gender = [("m" if np.median([f0(long_[i]["x"]) for i in g[:6]]) < 165 else "f") for g in groups]
        print(f"RU: {len(long_)} клипов 2–8 с → {len(groups)} групп: " +
              ", ".join(f"{len(g)}{gd}" for g, gd in zip(groups, gender)), file=sys.stderr)
        b2, b3 = pick(groups, cents, gender, 2), (pick(groups, cents, gender, 3) if len(groups) >= 3 else None)
        if b2 and b3 or n >= 3000:
            break
        n *= 3
        print(f"RU: мало групп, расширяю до {n} клипов", file=sys.stderr)
    shorts = [c for c in clips if c["dur"] < 2]
    Es = np.stack([embed(ext, c["x"]) for c in shorts]) if shorts else np.zeros((0, E.shape[1]))
    for name, best, seed in [("real_ru_2spk", b2, a.seed), ("real_ru_3spk", b3, a.seed + 1)]:
        if not best:
            print(f"{name}: не нашлось групп с косинусом центроидов < 0.3", file=sys.stderr); continue
        (_, _, _), comb, mc = best
        rng = np.random.default_rng(seed)
        spk, sh = [], []
        for gi in comb:
            spk.append([long_[i] for i in groups[gi]])
            sc = [shorts[j] for j in np.where(Es @ cents[gi] > 0.7)[0]] if len(Es) else []
            if not sc:  # нет коротких клипов этого голоса — берём самый короткий длинный
                spk[-1].sort(key=lambda c: c["dur"]); sc = [spk[-1].pop(0)]
            sh.append(sc)
        print(f"{name}: группы {[len(groups[g]) for g in comb]} пол {[gender[g] for g in comb]} max cos {mc:.2f}", file=sys.stderr)
        y, utts = build_dialog(rng, spk, sh)
        write(name, y, utts)


# ---------------- EN ----------------

def make_en(a):
    p = hf_hub_download(*AMI, repo_type="dataset", local_dir=a.cache)
    t = pq.read_table(p, columns=["meeting_id", "begin_time", "end_time", "speaker_id", "text"]).to_pylist()
    meet = a.ami_meeting
    rows = sorted([(r["begin_time"], r["end_time"], i) for i, r in enumerate(t) if r["meeting_id"] == meet])
    # окно: максимум покрытия речью и ≥3 спикеров среди стартов с шагом по сегментам, в первой трети не берём (вступление)
    best = None
    for b0, _, _ in rows[len(rows) // 3:]:
        seg = [(b, e, i) for b, e, i in rows if b >= b0 and e <= b0 + a.ami_len]
        spk = {t[i]["speaker_id"] for _, _, i in seg}
        cov = np.zeros(int(a.ami_len * 100), bool)
        for b, e, _ in seg:
            cov[int((b - b0) * 100): int((e - b0) * 100)] = True
        sc = (len(spk) >= 3, cov.mean())
        if best is None or sc > best[0]:
            best = (sc, b0, seg)
    (_, cov), b0, seg = best
    need = {i for _, _, i in seg}
    pf = pq.ParquetFile(p)
    audio, k = {}, 0
    for g in range(pf.num_row_groups):
        rg = pf.read_row_group(g, columns=["audio"]).column("audio").to_pylist()
        for j, r in enumerate(rg):
            if k + j in need:
                audio[k + j] = decode(r["bytes"])
        k += len(rg)
    end = max(e for _, e, _ in seg) - b0
    y = np.zeros(int((end + 0.5) * SR), np.float32)
    filled = np.zeros(len(y), bool)
    utts = []
    for b, e, i in seg:
        x = audio[i]
        s0 = int(round((b - b0) * SR))
        x = x[: max(0, len(y) - s0)]
        m = ~filled[s0:s0 + len(x)]  # одна и та же дальняя микрофонная дорожка: наложение уже есть в первом клипе
        y[s0:s0 + len(x)][m] = x[m]
        filled[s0:s0 + len(x)] = True
        text = t[i]["text"].lower()
        utts.append({"speaker_id": t[i]["speaker_id"], "start_ms": int(round((b - b0) * 1000)),
                     "end_ms": int(round((e - b0) * 1000)), "text": ref_text(text)})
    y += np.random.default_rng(a.seed).normal(0, 10 ** (-60 / 20), len(y)).astype(np.float32)
    y *= 10 ** ((-26 - rms_db(y[filled[: len(y)]])) / 20)
    print(f"EN: {meet} окно {b0:.1f}–{b0 + end:.1f} с, покрытие {cov:.0%}", file=sys.stderr)
    write("real_en_ami", y, utts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--emb", required=True, help="nemo_en_titanet_small.onnx")
    ap.add_argument("--cache", default=os.path.expanduser("~/.cache/steno_hf"))
    ap.add_argument("--n-clips", type=int, default=300)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--ami-meeting", default="EN2002b")
    ap.add_argument("--ami-len", type=float, default=150)
    ap.add_argument("--only", choices=["ru", "en"])
    a = ap.parse_args()
    if a.only != "en":
        ext = so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(model=a.emb, num_threads=4))
        make_ru(a, ext)
    if a.only != "ru":
        make_en(a)


main()
