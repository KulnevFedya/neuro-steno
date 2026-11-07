#!/usr/bin/env python3
"""Хост-подбор пересчёта кластеров поверх sherpa-onnx (логика = Kotlin Recluster.kt).
python3 -I eval/tune_diar.py --models <assets/models> [--cache <dir>] [--grid] [--thr 0.5 --merge 0.6 ...]
Без ASR: DER по сегментам диаризации после Segmenter (как в приложении)."""
import argparse, glob, itertools, json, os, pickle, sys
import numpy as np, sherpa_onnx as so

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "eval"))
from host_pipeline import read, segmenter  # noqa: E402
from score import der  # noqa: E402

MIN_EMB_MS, EDGE_MS, MAX_EMB_MS = 1000, 200, 10_000
BRIDGE_MS = int(os.environ.get("BRIDGE_MS", "2000"))
FILL_NB = tuple(int(v) for v in os.environ.get("FILL_NB", "2000").split(","))  # добор короче — метка соседа
REASSIGN = tuple(None if v == "none" else float(v) for v in os.environ.get("REASSIGN", "none").split(","))
FILLS = tuple(int(v) for v in os.environ.get("FILLS", "500,1000").split(","))  # мин. длина добора VAD; 0 в сетке — без добора


def diarize(M, x, thr, nspk=-1):
    cfg = so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(model=f"{M}/diar/seg.int8.onnx"), num_threads=4),
        embedding=so.SpeakerEmbeddingExtractorConfig(model=f"{M}/diar/titanet_small.onnx", num_threads=4),
        clustering=so.FastClusteringConfig(num_clusters=nspk, threshold=thr), min_duration_on=0.2, min_duration_off=0.5)
    return [(int(r.start * 1000), int(r.end * 1000), r.speaker) for r in so.OfflineSpeakerDiarization(cfg).process(x).sort_by_start_time()]


def vad_segments(M, x):
    """Silero VAD с параметрами приложения (Models.kt)."""
    cfg = so.VadModelConfig(); cfg.silero_vad.model = f"{M}/silero_vad.onnx"; cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.3; cfg.silero_vad.min_speech_duration = 0.25; cfg.silero_vad.max_speech_duration = 30
    cfg.sample_rate = 16000; cfg.num_threads = 1
    vad = so.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
    out = []

    def drain():
        while not vad.empty():
            f = vad.front; out.append((f.start * 1000 // 16000, (f.start + len(f.samples)) * 1000 // 16000)); vad.pop()
    for i in range(0, len(x) - 511, 512):
        vad.accept_waveform(x[i:i + 512]); drain()
    vad.flush(); drain()
    return out


def uncovered(vad, raw, min_ms, bridge_ms=None):
    """Куски VAD-речи, не покрытые сегментами диаризации, длиной >= min_ms. «Мостики» между двумя сегментами
    диаризации короче bridge_ms пропускаем: это пауза на смене реплик, которую VAD склеил."""
    bridge_ms = BRIDGE_MS if bridge_ms is None else bridge_ms
    cov = sorted((s, e) for s, e, _ in raw)
    res = []
    for vs, ve in vad:
        cur, bounded = vs, False
        for s, e in cov:
            if e <= cur or s >= ve:
                continue
            if s - cur >= min_ms and (not bounded or s - cur >= bridge_ms):
                res.append((cur, s))
            cur, bounded = max(cur, e), True
        if ve - cur >= min_ms:
            res.append((cur, ve))
    return res


def emb_window(s, e):
    """Центральная часть сегмента без краёв по 0.2 с, не длиннее 10 с; None — сегмент < 1 с."""
    if e - s < MIN_EMB_MS:
        return None
    s, e = s + EDGE_MS, e - EDGE_MS
    if e - s > MAX_EMB_MS:
        c = (s + e) // 2
        s, e = c - MAX_EMB_MS // 2, c + MAX_EMB_MS // 2
    return s, e


def embed(ex, x, w):
    st = ex.create_stream(); st.accept_waveform(16000, x[w[0] * 16: w[1] * 16]); st.input_finished()
    v = np.array(ex.compute(st), dtype=np.float32)
    return v / (np.linalg.norm(v) + 1e-9)


def recluster(raw, embs, merge, min_share, min_ms, kmin=2, kmax=6, short="neighbor", reassign=None):
    """raw: [(s,e,spk)], embs: {i: unit vec} для сегментов >= 1 с. spk=None — добор VAD (не участвует в центроидах,
    идёт к ближайшему итоговому центроиду). -> [(s,e,label)]"""
    fill = {i for i, r in enumerate(raw) if r[2] is None}
    dur = {}
    for s, e, k in raw:
        if k is None:
            continue
        dur[k] = dur.get(k, 0) + e - s
    members = {}
    for i, v in embs.items():
        if i in fill:
            continue
        members.setdefault(raw[i][2], []).append(i)
    if not members:
        return [r for r in raw if r[2] is not None]
    # кластер -> (сумма взвешенных эмбеддингов, длительность речи); кластеры без эмбеддингов — метки соседа
    cl = {k: [sum(embs[i] * (raw[i][1] - raw[i][0]) for i in m), dur[k]] for k, m in members.items()}
    parent = {k: k for k in cl}

    def cos(a, b):
        return float(cl[a][0] @ cl[b][0] / (np.linalg.norm(cl[a][0]) * np.linalg.norm(cl[b][0]) + 1e-9))

    def join(a, b):  # b -> a
        cl[a][0] = cl[a][0] + cl[b][0]; cl[a][1] += cl[b][1]; del cl[b]
        for k in parent:
            if parent[k] == b:
                parent[k] = a

    # 1. слияние близких центроидов (и обязательно, пока > kmax)
    while len(cl) > kmin:
        a, b = max(itertools.combinations(cl, 2), key=lambda p: cos(*p))
        if len(cl) <= kmax and cos(a, b) <= merge:
            break
        join(*((a, b) if cl[a][1] >= cl[b][1] else (b, a)))
    # 2. мелкие кластеры -> к ближайшему
    while len(cl) > kmin:
        total = sum(c[1] for c in cl.values())
        sm = [k for k in cl if cl[k][1] < min_ms or cl[k][1] < min_share * total]
        if not sm:
            break
        k = min(sm, key=lambda k: cl[k][1])
        join(max((o for o in cl if o != k), key=lambda o: cos(k, o)), k)
    # 3. один кластер при >= 2 эмбеддингах -> делим на 2 (средняя связь по сегментам)
    lab = {i: parent[raw[i][2]] for i in embs if i not in fill}
    if len(cl) < kmin and len(lab) >= 2:
        groups = [[i] for i in lab]
        while len(groups) > kmin:
            a, b = max(itertools.combinations(range(len(groups)), 2),
                       key=lambda p: np.mean([embs[i] @ embs[j] for i in groups[p[0]] for j in groups[p[1]]]))
            groups[a] += groups[b]; del groups[b]
        lab = {i: g for g, grp in enumerate(groups) for i in grp}
        cl = {g: [sum(embs[i] for i in grp), 0] for g, grp in enumerate(groups)}
        parent = {}
    cen = {k: c[0] / (np.linalg.norm(c[0]) + 1e-9) for k, c in cl.items()}
    for i in fill:
        if i in embs:
            lab[i] = max(cen, key=lambda k: float(embs[i] @ cen[k]))
    if reassign is not None:  # сегмент -> ближайший центроид, если он ближе своего на > reassign
        for i in list(lab):
            if i in fill or lab[i] not in cen:
                continue
            b = max(cen, key=lambda k: float(embs[i] @ cen[k]))
            if float(embs[i] @ cen[b]) - float(embs[i] @ cen[lab[i]]) > reassign:
                lab[i] = b
    # 4. короткие сегменты -> метка ближайшего по времени сегмента с эмбеддингом
    out = []
    keys = sorted(lab)
    for i, (s, e, k) in enumerate(raw):
        if i in lab:
            out.append((s, e, lab[i])); continue
        if short == "keep" and k in parent and len(cl) >= kmin and parent[k] in cl:
            out.append((s, e, parent[k])); continue
        j = min(keys, key=lambda j: max(raw[j][0] - e, s - raw[j][1], 0))
        out.append((s, e, lab[j]))
    return out


def to_utts(segs):
    order = {}
    res = []
    for s, e, k in segmenter(segs):
        order.setdefault(k, len(order) + 1)
        res.append({"speaker_id": f"speaker_{order[k]}", "start_ms": s, "end_ms": e})
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=os.path.join(ROOT, "src/mobile/android/app/src/main/assets/models"))
    ap.add_argument("--cache", required=True)
    ap.add_argument("--thrs", default="0.5,0.55,0.6,0.65,0.7,0.75,0.8,0.85,0.9")
    ap.add_argument("--merges", default="0.3,0.35,0.4,0.45,0.5,0.55,0.6,0.65,0.7,2")
    ap.add_argument("--min-share", type=float, default=0.08); ap.add_argument("--min-ms", type=int, default=4000)
    ap.add_argument("--pen", type=float, default=0.1)
    ap.add_argument("--top", type=int, default=15)
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)
    files = sorted(glob.glob(os.path.join(ROOT, "test_audio", "*.wav")))
    ex = so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(model=f"{a.models}/diar/titanet_small.onnx", num_threads=4))
    data = {}
    for p in files:
        name = os.path.basename(p)[:-4]
        ref = json.load(open(os.path.join(ROOT, "eval/ref", name + ".json")))["transcript"]
        cp = os.path.join(a.cache, name + ".pkl")
        c = pickle.load(open(cp, "rb")) if os.path.exists(cp) else {"raw": {}, "emb": {}}
        x = None
        for thr in map(float, a.thrs.split(",")):
            if thr not in c["raw"]:
                x = read(p) if x is None else x
                c["raw"][thr] = diarize(a.models, x, thr)
            for s, e, _ in c["raw"][thr]:
                w = emb_window(s, e)
                if w and w not in c["emb"]:
                    x = read(p) if x is None else x
                    c["emb"][w] = embed(ex, x, w)
        if "vad" not in c:
            x = read(p) if x is None else x
            c["vad"] = vad_segments(a.models, x)
        for thr in map(float, a.thrs.split(",")):
            for fm in FILLS:
                for s, e in uncovered(c["vad"], c["raw"][thr], fm):
                    w = emb_window(s, e)
                    if w and w not in c["emb"]:
                        x = read(p) if x is None else x
                        c["emb"][w] = embed(ex, x, w)
        if x is not None:
            pickle.dump(c, open(cp, "wb"))
        data[name] = (ref, c, len({u["speaker_id"] for u in ref}))
    ru = [n for n in data if "_en" not in n and not n.startswith("en_")]

    def evaluate(thr, merge, short, min_share=a.min_share, min_ms=a.min_ms, post=True, fill=0, reassign=None, fill_nb=0):
        r = {}
        for n, (ref, c, nref) in data.items():
            raw = c["raw"][thr]
            if fill:
                raw = sorted(raw + [(s, e, None) for s, e in uncovered(c["vad"], raw, fill)], key=lambda t: t[0])
            if post:
                embs = {i: c["emb"][emb_window(s, e)] for i, (s, e, k) in enumerate(raw)
                        if emb_window(s, e) and (k is not None or e - s >= fill_nb)}
                segs = recluster(raw, embs, merge, min_share, min_ms, short=short, reassign=reassign)
            else:
                segs = raw
            hyp = to_utts(segs)
            r[n] = (der(ref, hyp), len({u["speaker_id"] for u in hyp}), nref)
        return r

    def line(tag, r):
        cost = sum(r[n][0] + a.pen * abs(r[n][1] - r[n][2]) for n in ru)
        cells = " ".join(f"{n[:14]:>14s}={r[n][0]:.3f}/{r[n][1]}" for n in sorted(r))
        return cost, f"{tag:34s} cost={cost:.3f} | {cells}"

    base = evaluate(0.5, 2, "keep", 0.03, 2000)
    print(line("baseline thr0.5 small3%/2s", base)[1])
    res = []
    for thr in map(float, a.thrs.split(",")):
        print(line(f"sherpa-only thr{thr}", evaluate(thr, 0, "", post=False))[1])
        for merge in map(float, a.merges.split(",")):
            for short, fill, ra, nb in itertools.product(("neighbor", "keep"), (0,) + FILLS, REASSIGN, FILL_NB):
                r = evaluate(thr, merge, short, fill=fill, reassign=ra, fill_nb=nb)
                synth_ok = all(r[n][0] <= base[n][0] + 0.02 for n in data if not n.startswith("real_"))
                cost, txt = line(f"thr{thr} m{merge} {short} fill{fill}/{nb} ra{ra}", r)
                res.append((cost, synth_ok, txt))
                print(("ok " if synth_ok else "-- ") + txt)
    print("--- top (synth_ok) ---")
    for cost, ok, txt in sorted(res)[: a.top * 3]:
        if ok:
            print(txt)


if __name__ == "__main__":
    main()
