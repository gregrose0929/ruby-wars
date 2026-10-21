"""Synthesized weapon sounds (Ogg Vorbis) for Ruby Wars.

Run from the project root: python tools/sounds.py   (needs numpy and soundfile)
Writes src/main/resources/assets/rubymod/sounds/*.ogg and sounds.json.

Every shot is layered like a real recording: a sharp supersonic crack, the
muzzle blast "body" (filtered noise with a fast decay), a low thump, the
mechanical clack of the action, and a room/field tail. Variants differ in
seed and pitch so repeated fire doesn't sound like a loop.
"""
import json
import os

import numpy as np
import soundfile as sf

SR = 44100
OUT = "src/main/resources/assets/rubymod/sounds"


def t(seconds):
    return np.arange(int(SR * seconds)) / SR


def noise(seconds, rng):
    return rng.uniform(-1, 1, int(SR * seconds))


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def lowpass3(x, cutoff):
    """Steeper (18 dB/octave) lowpass: three one-pole stages."""
    return lowpass(lowpass(lowpass(x, cutoff), cutoff), cutoff)


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def bandpass(x, lo, hi):
    return lowpass(highpass(x, lo), hi)


def env(n, attack, decay):
    """Exponential decay envelope with a short linear attack (seconds)."""
    tt = np.arange(n) / SR
    e = np.exp(-tt / decay)
    a = int(SR * attack)
    if a > 0:
        e[:a] *= np.linspace(0, 1, a)
    return e


def pad(x, n):
    return np.pad(x, (0, max(0, n - len(x))))[:n]


def mix(length, *layers):
    n = int(SR * length)
    out = np.zeros(n)
    for offset, layer in layers:
        o = int(SR * offset)
        seg = layer[: max(0, n - o)]
        out[o:o + len(seg)] += seg
    return out


def tail(x, delays=((0.06, 0.35), (0.13, 0.22), (0.21, 0.14), (0.34, 0.08))):
    """Cheap outdoor reflections: a few lowpassed echoes."""
    n = len(x) + int(SR * (delays[-1][0] + 0.05))
    y = pad(x, n)
    soft = lowpass3(pad(x, n), 1400)
    for d, g in delays:
        o = int(SR * d)
        y[o:] += soft[: n - o] * g
    return y


def normalize(x, peak=0.92):
    m = np.max(np.abs(x))
    return x / m * peak if m > 0 else x


def gunshot(seed, crack, body_cut, body_decay, thump_hz, thump_decay, length, mech=0.25, echo=True, gain_crack=0.35):
    rng = np.random.default_rng(seed)
    crack_layer = highpass(noise(0.012, rng), 2500) * env(int(SR * 0.012), 0.0003, 0.0025) * gain_crack * crack
    body = lowpass3(noise(length, rng), body_cut) * env(int(SR * length), 0.0008, body_decay)
    body = body / max(1e-6, np.max(np.abs(body)))
    tt = t(thump_decay * 5)
    thump = np.sin(2 * np.pi * thump_hz * tt * (1 - 0.3 * tt / tt[-1])) * np.exp(-tt / thump_decay) * 1.3
    clack = bandpass(noise(0.03, rng), 1800, 6000) * env(int(SR * 0.03), 0.0005, 0.006) * mech
    x = mix(length + 0.1, (0, crack_layer), (0.0005, body), (0.0, thump), (0.045, clack))
    if echo:
        x = tail(x)
    return normalize(x)


def sniper(seed):
    x = gunshot(seed, crack=1.4, body_cut=2400, body_decay=0.09, thump_hz=55, thump_decay=0.09, length=0.6, mech=0.0)
    return normalize(tail(x, ((0.25, 0.45), (0.55, 0.3), (0.9, 0.18), (1.3, 0.1))))


def blaster(seed):
    rng = np.random.default_rng(seed)
    tt = t(0.32)
    f = 2400 * np.exp(-tt / 0.06) + 260
    phase = 2 * np.pi * np.cumsum(f) / SR
    zap = np.sin(phase) * np.sin(2 * np.pi * 55 * tt) ** 2 * np.exp(-tt / 0.09)
    fizz = bandpass(noise(0.32, rng), 3000, 9000) * np.exp(-tt / 0.03) * 0.35
    hum = np.sin(2 * np.pi * 110 * tt) * np.exp(-tt / 0.12) * 0.3
    return normalize(tail(zap + fizz + hum, ((0.05, 0.25), (0.11, 0.12))))


def whoosh(seed, seconds=1.4):
    rng = np.random.default_rng(seed)
    n = int(SR * seconds)
    blast = gunshot(seed, 0.6, 1200, 0.07, 45, 0.08, 0.35, mech=0.0, echo=False)
    tt = np.arange(n) / SR
    roar = lowpass3(highpass(noise(seconds, rng), 250), 1800) * np.minimum(1, tt / 0.05) * np.exp(-tt / 0.6) * 0.7
    return normalize(tail(mix(seconds, (0, blast), (0.02, roar))))


def cannon(seed):
    x = gunshot(seed, crack=1.0, body_cut=900, body_decay=0.16, thump_hz=38, thump_decay=0.18, length=1.0, mech=0.0)
    return normalize(tail(x, ((0.2, 0.5), (0.45, 0.32), (0.8, 0.2), (1.3, 0.12))))


def metal_click(seed, freqs, decay=0.012, length=0.08, gain=1.0):
    rng = np.random.default_rng(seed)
    tt = t(length)
    x = bandpass(noise(length, rng), 1500, 9000) * np.exp(-tt / 0.003) * 0.6
    for f in freqs:
        x += np.sin(2 * np.pi * f * tt + rng.uniform(0, 6)) * np.exp(-tt / decay) * 0.3
    return x * gain


def mag_out(seed):
    return normalize(mix(0.35, (0, metal_click(seed, [1900, 3100], 0.01)), (0.06, metal_click(seed + 1, [900, 1400], 0.02, 0.12, 0.7)),
                         (0.08, lowpass(noise(0.2, np.random.default_rng(seed)), 900) * env(int(SR * 0.2), 0.02, 0.05) * 0.4)))


def mag_in(seed):
    return normalize(mix(0.3, (0, metal_click(seed, [1200, 2300, 3600], 0.015, 0.1, 1.0)),
                         (0.09, metal_click(seed + 3, [2600, 4100], 0.008, 0.06, 0.8))))


def bolt(seed):
    rng = np.random.default_rng(seed)
    slide = bandpass(noise(0.09, rng), 1200, 5000) * np.linspace(0.6, 0.2, int(SR * 0.09))
    return normalize(mix(0.4, (0, metal_click(seed, [1500, 2900], 0.01)), (0.01, slide),
                         (0.16, metal_click(seed + 5, [1100, 2400, 3700], 0.02, 0.12, 1.1))))


def shell_in(seed):
    return normalize(mix(0.2, (0, metal_click(seed, [700, 1600], 0.02, 0.1, 0.8)), (0.05, metal_click(seed + 2, [2400], 0.008, 0.05, 0.6))))


def casing(seed):
    rng = np.random.default_rng(seed)
    x = np.zeros(int(SR * 0.6))
    o = 0.0
    for i in range(4):
        f0 = rng.uniform(4200, 6200)
        ping = metal_click(seed + i, [f0, f0 * 1.52, f0 * 2.3], 0.03 * (0.8 ** i), 0.15, 0.7 * (0.6 ** i))
        s = int(SR * o)
        x[s:s + len(ping)] += ping[: len(x) - s]
        o += rng.uniform(0.07, 0.14) * (0.8 ** i)
    return normalize(x, 0.6)


def dry_fire(seed):
    return normalize(metal_click(seed, [2800, 4400], 0.006, 0.06), 0.7)


SOUNDS = {
    "rifle_shot": lambda s: gunshot(s, 1.0, 2600, 0.05, 70, 0.05, 0.35),
    "pistol_shot": lambda s: gunshot(s, 0.8, 3400, 0.035, 95, 0.035, 0.28, mech=0.35),
    "shotgun_shot": lambda s: gunshot(s, 0.9, 1600, 0.09, 50, 0.09, 0.55, mech=0.0),
    "sniper_shot": sniper,
    "blaster_shot": blaster,
    "silenced_shot": lambda s: normalize(lowpass(gunshot(s, 0.0, 1800, 0.02, 120, 0.02, 0.15, mech=0.6, echo=False), 2500), 0.6),
    "rocket_launch": whoosh,
    "tank_cannon": cannon,
    "mag_out": mag_out,
    "mag_in": mag_in,
    "bolt": bolt,
    "shell_in": shell_in,
    "casing": casing,
    "dry_fire": dry_fire,
}
VARIANTS = {"rifle_shot": 3, "pistol_shot": 3, "shotgun_shot": 2, "sniper_shot": 2, "blaster_shot": 3, "casing": 4}


def main():
    os.makedirs(OUT, exist_ok=True)
    index = {}
    for name, make in SOUNDS.items():
        files = []
        for v in range(VARIANTS.get(name, 1)):
            data = make(1000 + v * 17 + hash(name) % 100).astype(np.float32)
            fname = f"{name}_{v}" if VARIANTS.get(name, 1) > 1 else name
            sf.write(os.path.join(OUT, fname + ".ogg"), data, SR, format="OGG", subtype="VORBIS")
            files.append(f"rubymod:{fname}")
        index[name] = {"sounds": [{"name": f, "attenuation_distance": 96} if "shot" in name or name in ("rocket_launch", "tank_cannon")
                                  else f for f in files]}
    with open(os.path.join(OUT, "..", "sounds.json"), "w") as fh:
        json.dump(index, fh, indent="\t")
    print("sounds written:", len(SOUNDS))


if __name__ == "__main__":
    main()
