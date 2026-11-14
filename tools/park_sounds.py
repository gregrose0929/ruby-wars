"""Synthesized sounds for the hub's theme park: island ambience and the Ghost Train's scares.

Run from the project root: python tools/park_sounds.py   (needs numpy and soundfile)
Writes src/main/resources/assets/rubymod/sounds/park_*.ogg and adds them to sounds.json (leaving the rest alone).

  park_carnival  a calliope waltz (bright organ pipes, oom-pah-pah), about 12 seconds
  park_waves     waves lapping and washing back, about 9 seconds
  park_gulls     a seagull's calls (three variants)
  park_crowd     a happy crowd murmuring, with the odd laugh, about 8 seconds
  park_spooky    a low haunted drone with wind, about 10 seconds
  park_ghost     a ghost's wail (two variants)
  park_boo       a sudden stinger for the Ghost Train's jump scares (two variants)
"""
import json
import os

import numpy as np
import soundfile as sf

SR = 44100
OUT = "src/main/resources/assets/rubymod/sounds"


def t(seconds):
    return np.arange(int(SR * seconds)) / SR


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.zeros_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def fast_lowpass(x, cutoff, passes=2):
    # A moving average approximates a low-pass and is quick in numpy.
    n = max(1, int(SR / cutoff / 2))
    k = np.ones(n) / n
    for _ in range(passes):
        x = np.convolve(x, k, mode="same")
    return x


def normalize(x, peak=0.85):
    m = np.max(np.abs(x)) or 1.0
    return x / m * peak


def fade(x, seconds=0.05):
    n = int(SR * seconds)
    x = x.copy()
    x[:n] *= np.linspace(0, 1, n)
    x[-n:] *= np.linspace(1, 0, n)
    return x


def note_hz(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def pipe(freq, seconds, rng):
    """A calliope pipe: a breathy fundamental with odd harmonics and a little vibrato."""
    tt = t(seconds)
    vib = 1 + 0.004 * np.sin(2 * np.pi * 5.5 * tt)
    ph = 2 * np.pi * freq * np.cumsum(vib) / SR
    tone = np.sin(ph) + 0.35 * np.sin(3 * ph) + 0.15 * np.sin(5 * ph) + 0.08 * np.sin(2 * ph)
    breath = fast_lowpass(rng.uniform(-1, 1, len(tt)), 3000) * 0.08
    envl = np.minimum(1, tt / 0.02) * np.minimum(1, (seconds - tt) / 0.06)
    return (tone + breath) * envl


def carnival():
    rng = np.random.default_rng(3)
    beat = 0.32
    # A little waltz: melody (MIDI notes, beats), then oom-pah-pah chords underneath.
    melody = [(72, 1), (76, 1), (79, 1), (84, 2), (79, 1), (81, 1), (79, 1), (77, 1), (76, 2), (72, 1),
              (74, 1), (77, 1), (81, 1), (79, 2), (77, 1), (76, 1), (74, 1), (72, 1), (71, 2), (67, 1),
              (72, 1), (76, 1), (79, 1), (84, 2), (81, 1), (79, 1), (77, 1), (76, 1), (74, 3)]
    total = sum(b for _, b in melody) * beat
    out = np.zeros(int(SR * (total + 0.5)))
    pos = 0.0
    for n, b in melody:
        s = pipe(note_hz(n), b * beat * 0.92, rng)
        i = int(pos * SR)
        out[i:i + len(s)] += s * 0.5
        pos += b * beat
    chords = [(48, [64, 67]), (53, [65, 69]), (55, [65, 71]), (48, [64, 67])]
    bars = int(total / (3 * beat)) + 1
    for bar in range(bars):
        root, upper = chords[(bar // 2) % len(chords)]
        for k in range(3):
            i = int((bar * 3 + k) * beat * SR)
            if k == 0:
                s = pipe(note_hz(root), beat * 0.8, rng) * 0.45
            else:
                s = sum(pipe(note_hz(u), beat * 0.45, rng) for u in upper) * 0.18
            out[i:i + len(s)] += s[:max(0, len(out) - i)]
    return fade(normalize(out, 0.7), 0.3)


def waves():
    rng = np.random.default_rng(5)
    n = rng.uniform(-1, 1, int(SR * 9))
    body = fast_lowpass(n, 700) * 3
    hiss = fast_lowpass(n, 4000) - fast_lowpass(n, 1200)
    tt = t(9)
    swell = (np.sin(2 * np.pi * tt / 4.5 - np.pi / 2) + 1) / 2
    swell = swell ** 1.6
    out = body * (0.3 + 0.7 * swell) + hiss * swell * 0.8
    return fade(normalize(out, 0.6), 0.6)


def gull(seed):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * 1.6))
    pos = 0.0
    for _ in range(rng.integers(2, 4)):
        dur = rng.uniform(0.18, 0.32)
        tt = t(dur)
        f0 = rng.uniform(1100, 1500)
        f = f0 * (1 + 0.35 * np.sin(np.pi * tt / dur)) * (1 - 0.25 * tt / dur)
        ph = 2 * np.pi * np.cumsum(f) / SR
        s = np.sign(np.sin(ph)) * 0.3 + np.sin(ph) * 0.7 + 0.3 * np.sin(2 * ph)
        s = fast_lowpass(s, 3500, 1) * np.sin(np.pi * tt / dur) ** 0.6
        i = int(pos * SR)
        out[i:i + len(s)] += s
        pos += dur + rng.uniform(0.05, 0.15)
    return fade(normalize(out, 0.5), 0.02)


def crowd():
    rng = np.random.default_rng(9)
    tt = t(8)
    out = np.zeros(len(tt))
    # Many "voices": noise shaped into vowel-ish bands that come and go.
    for v in range(14):
        f = rng.uniform(250, 900)
        n = rng.uniform(-1, 1, len(tt))
        band = fast_lowpass(n, f * 2) - fast_lowpass(n, f)
        talk = (np.sin(2 * np.pi * rng.uniform(2, 5) * tt + rng.uniform(0, 6)) + 1) / 2
        talk *= (np.sin(2 * np.pi * rng.uniform(0.1, 0.3) * tt + rng.uniform(0, 6)) + 1) / 2
        out += band * talk
    # A couple of laughs.
    for k in range(2):
        start = rng.uniform(1, 6)
        for h in range(5):
            dur = 0.09
            s_t = t(dur)
            s = np.sin(2 * np.pi * rng.uniform(380, 460) * s_t) * np.sin(np.pi * s_t / dur)
            i = int((start + h * 0.13) * SR)
            out[i:i + len(s)] += s * 0.25
    return fade(normalize(out, 0.5), 0.5)


def spooky():
    rng = np.random.default_rng(13)
    tt = t(10)
    drone = sum(np.sin(2 * np.pi * f * tt + p) * a for f, p, a in ((55, 0, 1), (55 * 1.5, 1, 0.5), (55 * 2.02, 2, 0.35), (58.3, 0.5, 0.6)))
    drone *= 0.6 + 0.4 * np.sin(2 * np.pi * 0.13 * tt)
    n = rng.uniform(-1, 1, len(tt))
    wind = (fast_lowpass(n, 900) - fast_lowpass(n, 300)) * 4
    wind *= (np.sin(2 * np.pi * 0.2 * tt) + 1.2) / 2.2
    out = drone * 0.5 + wind * 0.6
    return fade(normalize(out, 0.6), 0.8)


def ghost(seed):
    rng = np.random.default_rng(seed)
    dur = 2.2
    tt = t(dur)
    f = 420 + 180 * np.sin(np.pi * tt / dur * rng.uniform(1.2, 1.8)) + 25 * np.sin(2 * np.pi * 6 * tt)
    ph = 2 * np.pi * np.cumsum(f) / SR
    s = np.sin(ph) + 0.3 * np.sin(2 * ph) + 0.15 * np.sin(3.01 * ph)
    breath = (fast_lowpass(rng.uniform(-1, 1, len(tt)), 2500)) * 0.4
    envl = np.sin(np.pi * tt / dur) ** 0.8
    return normalize((s + breath) * envl, 0.6)


def boo(seed):
    rng = np.random.default_rng(seed)
    dur = 1.0
    tt = t(dur)
    # A loud dissonant hit (an orchestra-ish stab) with a quick shriek on top.
    stab = sum(np.sin(2 * np.pi * f * tt) for f in (110, 116.5, 220, 233, 330)) / 5
    stab += fast_lowpass(rng.uniform(-1, 1, len(tt)), 1500) * 0.8
    stab *= np.exp(-tt * 5)
    shriek_f = 1600 - 500 * tt
    shriek = np.sin(2 * np.pi * np.cumsum(shriek_f) / SR) * np.exp(-tt * 6) * 0.4
    return normalize(stab + shriek, 0.9)


SOUNDS = {
    "park_carnival": (lambda s: carnival(), 1, 48),
    "park_waves": (lambda s: waves(), 1, 24),
    "park_gulls": (gull, 3, 40),
    "park_crowd": (lambda s: crowd(), 1, 24),
    "park_spooky": (lambda s: spooky(), 1, 24),
    "park_ghost": (ghost, 2, 24),
    "park_boo": (boo, 2, 24),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "..", "sounds.json")
    index = json.load(open(path)) if os.path.exists(path) else {}
    for name, (make, variants, reach) in SOUNDS.items():
        files = []
        for v in range(variants):
            data = make(200 + v * 31).astype(np.float32)
            fname = f"{name}_{v}" if variants > 1 else name
            # In small blocks: one big write overflows libsndfile's stack on Windows.
            with sf.SoundFile(os.path.join(OUT, fname + ".ogg"), "w", SR, 1, format="OGG", subtype="VORBIS") as f:
                for i in range(0, len(data), 4096):
                    f.write(data[i:i + 4096])
            files.append({"name": f"rubymod:{fname}", "attenuation_distance": reach})
        index[name] = {"sounds": files}
    with open(path, "w") as fh:
        json.dump(index, fh, indent="\t")
    print("park sounds written:", len(SOUNDS))


if __name__ == "__main__":
    main()
