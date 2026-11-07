#!/usr/bin/env python3
"""Монолог одного спикера > 30 с (ветка VAD в recognizeLong): реплики speaker_1 из ru_meeting_2spk, 4 круга с паузой 0.3 с.
python3 -I eval/make_mono.py -> test_audio_long/mono_60s.wav + eval/ref_long/mono_60s.json"""
import json, os, wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
with wave.open(os.path.join(ROOT, "test_audio", "ru_meeting_2spk.wav")) as w:
    pcm = w.readframes(w.getnframes())
ref = json.load(open(os.path.join(ROOT, "eval", "ref", "ru_meeting_2spk.json"), encoding="utf-8"))["transcript"]
mine = [u for u in ref if u["speaker_id"] == "speaker_1"]
out = bytearray(); res = []; t = 0
for _ in range(4):
    for u in mine:
        a, b = u["start_ms"] * 32, u["end_ms"] * 32  # 16 кГц * 2 байта = 32 байта/мс
        out += pcm[a:b]
        res.append({"speaker_id": "speaker_1", "start_ms": t, "end_ms": t + u["end_ms"] - u["start_ms"], "text": u["text"]})
        t += u["end_ms"] - u["start_ms"]
        out += b"\x00\x00" * 4800; t += 300
os.makedirs(os.path.join(ROOT, "test_audio_long"), exist_ok=True)
with wave.open(os.path.join(ROOT, "test_audio_long", "mono_60s.wav"), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(bytes(out))
json.dump({"transcript": res}, open(os.path.join(ROOT, "eval", "ref_long", "mono_60s.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(out) / 32000, "s", len(res), "utterances")
