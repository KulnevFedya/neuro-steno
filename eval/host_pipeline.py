#!/usr/bin/env python3
"""Хост-прототип пайплайна приложения (те же модели sherpa-onnx) для быстрого подбора параметров.
python3 -I eval/host_pipeline.py --models <dir> --out <dir> [--asr ctc|td] [--emb file] [--thr 0.5] [--nspk -1] [--no-asr]
Логика сегментов = Kotlin (Segmenter.kt): склейка <300 мс, паддинг 150 мс, >30 с режем, склейка реплик одного спикера при паузе <1 с."""
import argparse, glob, json, os, sys, time, wave
import numpy as np, sherpa_onnx as so

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read(p):
    with wave.open(p) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def segmenter(raw, min_ms=300, gap_ms=1000):
    """raw: [(start_ms,end_ms,spk)] -> сегменты: <300 мс клеим с соседом, соседние одного спикера при паузе <gap_ms клеим."""
    segs = [list(s) for s in sorted(raw)]
    out = []
    for s in segs:
        if out and out[-1][2] == s[2] and s[0] - out[-1][1] < gap_ms:
            out[-1][1] = max(out[-1][1], s[1])
        else:
            out.append(s)
    # короткие -> к ближайшему соседу (по паузе); спикер соседа
    i = 0
    while i < len(out):
        s = out[i]
        if s[1] - s[0] < min_ms and len(out) > 1:
            prev = out[i - 1] if i > 0 else None
            nxt = out[i + 1] if i + 1 < len(out) else None
            gp = s[0] - prev[1] if prev else 10**9
            gn = nxt[0] - s[1] if nxt else 10**9
            tgt = prev if gp <= gn else nxt
            if min(gp, gn) < gap_ms:
                tgt[0] = min(tgt[0], s[0]); tgt[1] = max(tgt[1], s[1])
                out.pop(i); i = max(i - 1, 0)
                continue
        i += 1
    merged = []
    for s in out:
        if merged and merged[-1][2] == s[2] and s[0] - merged[-1][1] < gap_ms:
            merged[-1][1] = max(merged[-1][1], s[1])
        else:
            merged.append(s)
    return merged


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--asr", default="ctc"); ap.add_argument("--emb", default="3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx")
    ap.add_argument("--thr", type=float, default=0.5); ap.add_argument("--nspk", type=int, default=-1)
    ap.add_argument("--no-asr", action="store_true"); ap.add_argument("--files", default="*.wav")
    ap.add_argument("--min-off", type=float, default=0.5)
    a = ap.parse_args()
    M = a.models
    os.makedirs(a.out, exist_ok=True)
    if not a.no_asr:
        if a.asr == "ctc":
            rec = so.OfflineRecognizer.from_nemo_ctc(model=f"{M}/ctc/model.int8.onnx", tokens=f"{M}/ctc/tokens.txt", num_threads=4)
        else:
            rec = so.OfflineRecognizer.from_transducer(encoder=f"{M}/td/encoder.int8.onnx", decoder=f"{M}/td/decoder.onnx",
                                                       joiner=f"{M}/td/joiner.onnx", tokens=f"{M}/td/tokens.txt", num_threads=4,
                                                       model_type="nemo_transducer")
    vad_cfg = so.VadModelConfig(); vad_cfg.silero_vad.model = f"{M}/silero_vad.onnx"; vad_cfg.silero_vad.max_speech_duration = 30
    for p in sorted(glob.glob(os.path.join(ROOT, "test_audio", a.files))):
        name = os.path.basename(p)[:-4]
        x = read(p)
        t0 = time.time()
        cfg = so.OfflineSpeakerDiarizationConfig(
            segmentation=so.OfflineSpeakerSegmentationModelConfig(pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(model=f"{M}/diar/seg.int8.onnx"), num_threads=2),
            embedding=so.SpeakerEmbeddingExtractorConfig(model=f"{M}/diar/{a.emb}", num_threads=2),
            clustering=so.FastClusteringConfig(num_clusters=a.nspk, threshold=a.thr),
            min_duration_on=0.2, min_duration_off=a.min_off)
        sd = so.OfflineSpeakerDiarization(cfg)
        res = sd.process(x).sort_by_start_time()
        t_d = time.time() - t0
        raw = [(int(r.start * 1000), int(r.end * 1000), r.speaker) for r in res]
        segs = segmenter(raw)
        order = {}
        utts = []
        t1 = time.time()
        for s, e, spk in segs:
            order.setdefault(spk, len(order) + 1)
            text = ""
            if not a.no_asr:
                chunks = []
                S, E = max(0, s - 150), min(len(x) * 1000 // 16000, e + 150)
                if E - S > 30000:
                    k = -(-(E - S) // 25000); step = (E - S) // k
                    chunks = [(S + i * step, S + (i + 1) * step) for i in range(k)]
                else:
                    chunks = [(S, E)]
                parts = []
                for cs, ce in chunks:
                    st = rec.create_stream(); st.accept_waveform(16000, x[cs * 16: ce * 16]); rec.decode_stream(st)
                    parts.append(st.result.text.strip())
                text = " ".join(t for t in parts if t)
                if not text:
                    continue
            utts.append({"speaker_id": f"speaker_{order[spk]}", "start_ms": s, "end_ms": e, "text": text})
        t_a = time.time() - t1
        dur = len(x) / 16000
        json.dump({"transcript": utts}, open(f"{a.out}/{name}.json", "w"), ensure_ascii=False, indent=1)
        json.dump({"audio_ms": int(dur * 1000), "proc_ms": int((t_d + t_a) * 1000), "rtf": (t_d + t_a) / dur}, open(f"{a.out}/{name}.meta.json", "w"))
        print(f"{name}: spk={len(order)} segs={len(utts)} diar={t_d:.1f}s asr={t_a:.1f}s", file=sys.stderr)


main()
