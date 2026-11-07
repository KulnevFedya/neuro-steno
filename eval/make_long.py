#!/usr/bin/env python3
"""Длинные тестовые файлы: склейка test_audio/*.wav (RU) с паузами, эталон со сдвинутыми таймкодами.
python3 -I eval/make_long.py [--minutes 10 30] -> test_audio_long/long_<N>min.wav + eval/ref_long/long_<N>min.json
(и звук, и референс детерминированы; test_audio_long/ не коммитим)."""
import argparse, json, os, wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = ["ru_meeting_2spk", "ru_meeting_3spk", "ru_meeting_2spk_noisy", "ru_meeting_3spk_noisy"]
PAUSES_MS = [1500, 2500, 1000, 3000]


def build(minutes):
    target = minutes * 60 * 1000
    pcm = bytearray(); ref = []; t = 0; i = 0
    spk_off = 0
    while t < target:
        name = SRC[i % len(SRC)]
        with wave.open(os.path.join(ROOT, "test_audio", name + ".wav")) as w:
            assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2
            data = w.readframes(w.getnframes())
        r = json.load(open(os.path.join(ROOT, "eval", "ref", name + ".json"), encoding="utf-8"))["transcript"]
        for u in r:
            ref.append({"speaker_id": u["speaker_id"], "start_ms": u["start_ms"] + t, "end_ms": u["end_ms"] + t, "text": u["text"]})
        pcm += data
        t += len(data) // 2 * 1000 // 16000
        pause = PAUSES_MS[i % len(PAUSES_MS)]
        pcm += b"\x00\x00" * (16 * pause)
        t += pause; i += 1
    return bytes(pcm), ref


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--minutes", type=int, nargs="+", default=[10, 30])
    a = ap.parse_args()
    os.makedirs(os.path.join(ROOT, "test_audio_long"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "eval", "ref_long"), exist_ok=True)
    for m in a.minutes:
        pcm, ref = build(m)
        n = f"long_{m}min"
        with wave.open(os.path.join(ROOT, "test_audio_long", n + ".wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(pcm)
        with open(os.path.join(ROOT, "eval", "ref_long", n + ".json"), "w", encoding="utf-8") as f:
            json.dump({"transcript": ref}, f, ensure_ascii=False, indent=1)
        print(n, len(pcm) / 32000, "s", len(ref), "utterances")
