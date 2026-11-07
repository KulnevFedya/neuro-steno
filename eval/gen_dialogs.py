#!/usr/bin/env python3
"""
Генерирует синтетические тестовые диалоги голосами macOS (`say`) + эталонный JSON по формату ТЗ.

Запуск (из корня проекта):
    python3 eval/gen_dialogs.py            # все диалоги
    python3 eval/gen_dialogs.py --noise    # плюс версии с фоновым шумом

Результат:
    test_audio/<name>.wav   — 16 kHz, mono, 16-bit
    eval/ref/<name>.json    — {"transcript":[{speaker_id,start_ms,end_ms,text}]}

Голоса: посмотреть доступные — `say -v '?' | grep -E "ru_RU|en_US|en_GB"`.
Дополнительные русские голоса (Yuri, Katya и т.п.) можно скачать:
System Settings → Accessibility → Spoken Content → System voice → Manage Voices.
Если голоса нет, скрипт подставит первый доступный для языка и предупредит.
"""
import argparse, json, os, subprocess, sys, tempfile, wave, random, struct

SR = 16000

# Голоса-кандидаты по ролям (первый найденный будет использован)
VOICES = {
    "ru": [["Milena (Premium)", "Milena (Enhanced)", "Milena"],
           ["Yuri (Premium)", "Yuri (Enhanced)", "Yuri"],
           ["Katya (Premium)", "Katya (Enhanced)", "Katya"]],
    "en": [["Samantha (Enhanced)", "Samantha"], ["Daniel (Enhanced)", "Daniel"],
           ["Karen (Enhanced)", "Karen"], ["Alex"]],
}

DIALOGS = {
    # имя: (язык, [(роль 0..N, текст, пауза_до_реплики_мс)])  отрицательная пауза = перебивание/наложение
    "ru_meeting_2spk": ("ru", [
        (0, "Коллеги, добрый день! Начинаем планёрку по проекту Котовице.", 300),
        (1, "Добрый день. Я подготовил отчёт за прошлую неделю.", 500),
        (0, "Отлично. Сколько протоколов удалось обработать?", 400),
        (1, "Двести сорок три протокола, это на пятнадцать процентов больше плана.", 450),
        (0, "Да.", 200),
        (1, "Но есть проблема: котята не успевают проверять расшифровки вручную.", 350),
        (0, "Понятно. Значит, нам нужна автоматическая стенография. Когда будет прототип?", 500),
        (1, "К пятнице, седьмого ноября.", 400),
        (0, "Хорошо, договорились.", 300),
    ]),
    "ru_meeting_3spk": ("ru", [
        (0, "Итак, у нас три вопроса на повестке.", 300),
        (1, "Можно я начну? У меня короткий доклад.", 400),
        (0, "Конечно.", 250),
        (1, "Мы закупили сорок килограммов корма и двенадцать новых когтеточек.", 400),
        (2, "А бюджет на это согласовали?", 300),
        (1, "Да, ещё в октябре.", 200),
        (2, "Хорошо. Тогда у меня вопрос по графику дежурств: кто сторожит амбар ночью?", 500),
        (0, "Предлагаю по очереди, начиная с понедельника.", 350),
        (2, "Угу.", -150),   # короткое поддакивание с наложением
        (1, "Я согласен.", 300),
        (0, "Отлично, тогда все свободны. Спасибо!", 400),
    ]),
    "en_call_2spk": ("en", [
        (0, "Hi everyone, can you hear me?", 300),
        (1, "Yes, loud and clear.", 400),
        (0, "Great. Let's review the release plan for version two point one.", 450),
        (1, "The build is ready, but we still have three open bugs.", 400),
        (0, "Which one is the most critical?", 300),
        (1, "The crash on import of long audio files.", 350),
        (0, "Okay, let's fix that first and ship on Monday.", 400),
    ]),
}


def have_say():
    return subprocess.run(["which", "say"], capture_output=True).returncode == 0


def available_voices():
    out = subprocess.run(["say", "-v", "?"], capture_output=True, text=True).stdout
    return {line.split("  ")[0].strip(): line for line in out.splitlines() if line.strip()}


def pick_voices(lang, n, avail):
    lang_tag = "ru_RU" if lang == "ru" else "en_"
    chosen = []
    base = lambda v: v.split(" (")[0]
    for cands in VOICES[lang]:
        for v in cands:
            if v in avail and base(v) not in {base(c) for c in chosen}:
                chosen.append(v)
                break
        if len(chosen) == n:
            break
    # добор любыми голосами нужного языка
    for v, line in avail.items():
        if len(chosen) >= n:
            break
        if lang_tag in line and base(v) not in {base(c) for c in chosen}:
            chosen.append(v)
    if len(chosen) < n:
        print(f"[!] Для '{lang}' найдено голосов: {len(chosen)} из {n}. "
              f"Повторяю голоса с изменённой скоростью — диаризация будет сложнее.", file=sys.stderr)
        while len(chosen) < n:
            chosen.append(chosen[len(chosen) % max(1, len(chosen))] if chosen else "")
    return chosen


def tts_to_pcm(text, voice, rate, tmpdir):
    aiff = os.path.join(tmpdir, "t.aiff")
    wav = os.path.join(tmpdir, "t.wav")
    cmd = ["say", "-o", aiff, "-r", str(rate)]
    if voice:
        cmd += ["-v", voice]
    subprocess.run(cmd + [text], check=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", aiff, "-ar", str(SR), "-ac", "1",
                    "-sample_fmt", "s16", wav], check=True)
    with wave.open(wav) as w:
        data = w.readframes(w.getnframes())
    samples = list(struct.unpack("<%dh" % (len(data) // 2), data))
    # обрезаем тишину по краям, чтобы таймкоды эталона были точными
    thr = 300
    s = next((i for i, x in enumerate(samples) if abs(x) > thr), 0)
    e = len(samples) - next((i for i, x in enumerate(reversed(samples)) if abs(x) > thr), 0)
    return samples[s:e]


def mix(track, seg, start):
    end = start + len(seg)
    if end > len(track):
        track.extend([0] * (end - len(track)))
    for i, x in enumerate(seg):
        v = track[start + i] + x
        track[start + i] = max(-32768, min(32767, v))


def add_noise(track, level, seed=0):
    rnd = random.Random(seed)
    return [max(-32768, min(32767, int(x + rnd.gauss(0, level)))) for x in track]


def write_wav(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(struct.pack("<%dh" % len(samples), *samples))


def build(name, lang, lines, avail, out_audio, out_ref, noise_level=None):
    n_spk = max(r for r, _, _ in lines) + 1
    voices = pick_voices(lang, n_spk, avail)
    rates = [175 + 15 * i for i in range(n_spk)]  # чуть разная скорость речи
    track, transcript, cursor = [0] * int(0.5 * SR), [], int(0.5 * SR)
    with tempfile.TemporaryDirectory() as td:
        for role, text, pause in lines:
            seg = tts_to_pcm(text, voices[role], rates[role], td)
            start = max(0, cursor + int(pause * SR / 1000))
            mix(track, seg, start)
            end = start + len(seg)
            transcript.append({"speaker_id": f"speaker_{role + 1}",
                               "start_ms": round(start * 1000 / SR),
                               "end_ms": round(end * 1000 / SR),
                               "text": text})
            cursor = max(cursor, end)
    track.extend([0] * int(0.5 * SR))
    if noise_level:
        track = add_noise(track, noise_level)
        name = name + "_noisy"
    transcript.sort(key=lambda r: r["start_ms"])
    write_wav(os.path.join(out_audio, name + ".wav"), track)
    with open(os.path.join(out_ref, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"transcript": transcript}, f, ensure_ascii=False, indent=2)
    dur = len(track) / SR
    print(f"[ok] {name}: {dur:.1f} c, спикеров {n_spk}, голоса {voices}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audio-dir", default="test_audio")
    ap.add_argument("--ref-dir", default="eval/ref")
    ap.add_argument("--noise", action="store_true", help="дополнительно сделать зашумлённые версии")
    args = ap.parse_args()
    if not have_say():
        sys.exit("Нужна macOS (команда `say`).")
    os.makedirs(args.audio_dir, exist_ok=True)
    os.makedirs(args.ref_dir, exist_ok=True)
    avail = available_voices()
    for name, (lang, lines) in DIALOGS.items():
        build(name, lang, lines, avail, args.audio_dir, args.ref_dir)
        if args.noise:
            build(name, lang, lines, avail, args.audio_dir, args.ref_dir, noise_level=900)


if __name__ == "__main__":
    main()
