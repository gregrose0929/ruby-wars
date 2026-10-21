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


def gunshot(seed, crack, body_cut, body_decay, thump_hz, thump_decay, length, mech=0.25, echo=True, gain_crack=0.35, thump_gain=1.3):
    rng = np.random.default_rng(seed)
    crack_layer = highpass(noise(0.012, rng), 2500) * env(int(SR * 0.012), 0.0003, 0.0025) * gain_crack * crack
    body = lowpass3(noise(length, rng), body_cut) * env(int(SR * length), 0.0008, body_decay)
    body = body / max(1e-6, np.max(np.abs(body)))
    tt = t(thump_decay * 5)
    thump = np.sin(2 * np.pi * thump_hz * tt * (1 - 0.3 * tt / tt[-1])) * np.exp(-tt / thump_decay) * thump_gain
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
    x = gunshot(seed, crack=1.4, body_cut=1700, body_decay=0.16, thump_hz=52, thump_decay=0.16, length=1.0, mech=0.0, thump_gain=0.35)
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


def bullet_whiz(seed):
    """A supersonic crack and a short whine that dopplers down as the round goes past."""
    rng = np.random.default_rng(seed)
    n = int(SR * 0.35)
    tt = np.arange(n) / SR
    crack = highpass(noise(0.008, rng), 3000) * env(int(SR * 0.008), 0.0002, 0.002)
    f = 3200 * np.exp(-tt / 0.08) + 900
    whine = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.07) * np.minimum(1, tt / 0.004) * 0.25
    air = bandpass(noise(0.35, rng), 1500, 7000) * np.exp(-tt / 0.05) * 0.4
    return normalize(mix(0.35, (0, crack), (0.002, whine), (0.0, air)), 0.8)


def explosion(seed):
    rng = np.random.default_rng(seed)
    blast = gunshot(seed, crack=1.2, body_cut=1400, body_decay=0.22, thump_hz=48, thump_decay=0.25, length=1.6, mech=0.0, echo=False,
                    thump_gain=0.6)
    rumble = lowpass3(noise(3.0, rng), 260) * env(int(SR * 3.0), 0.05, 0.9)
    rumble = rumble / np.max(np.abs(rumble))
    debris = bandpass(noise(1.2, rng), 1500, 6000) * env(int(SR * 1.2), 0.1, 0.35) * 0.15
    return normalize(tail(mix(3.0, (0, blast), (0.0, rumble * 0.7), (0.25, debris)), ((0.3, 0.4), (0.7, 0.25), (1.2, 0.15))))


def explosion_far(seed):
    rng = np.random.default_rng(seed)
    boom = lowpass3(noise(2.5, rng), 180) * env(int(SR * 2.5), 0.03, 0.7)
    thud = np.sin(2 * np.pi * 50 * t(1.0)) * np.exp(-t(1.0) / 0.25)
    return normalize(mix(2.5, (0, boom), (0, thud * 0.5)), 0.9)


# ----- Engine loops (2 s, seamless: every tone has a whole number of cycles, noise is filtered periodically) -----

LOOP = 2.0


def periodic_noise(rng, shape):
    """Filtered noise that loops cleanly: filter three copies end to end and keep the middle one."""
    n = int(SR * LOOP)
    base = rng.uniform(-1, 1, n)
    y = shape(np.concatenate([base, base, base]))
    return y[n:2 * n]


def tone(freq, amp=1.0):
    f = round(freq * LOOP) / LOOP
    return np.sin(2 * np.pi * f * t(LOOP)) * amp


def jet_engine(seed):
    rng = np.random.default_rng(seed)
    roar = periodic_noise(rng, lambda x: lowpass3(x, 900)) * 3.0
    hiss = periodic_noise(rng, lambda x: bandpass(x, 2500, 7000)) * 0.4
    whine = tone(3150, 0.12) + tone(6300, 0.05) + tone(1575, 0.08)
    wobble = 1 + 0.08 * np.sin(2 * np.pi * 1.5 * t(LOOP))
    return normalize((roar + hiss + whine) * wobble, 0.8)


def heli_rotor(seed):
    rng = np.random.default_rng(seed)
    n = int(SR * LOOP)
    slap = np.zeros(n)
    rate = 5.5
    beats = int(rate * LOOP)
    period = n // beats
    burst = lowpass3(rng.uniform(-1, 1, period), 700) * env(period, 0.004, 0.035)
    burst = burst / np.max(np.abs(burst))
    for i in range(beats):
        slap[i * period:i * period + period] += burst
    turbine = periodic_noise(rng, lambda x: bandpass(x, 1500, 5000)) * 0.25 + tone(2400, 0.06)
    rumble = periodic_noise(rng, lambda x: lowpass3(x, 200)) * 2.5
    return normalize(slap * 0.9 + turbine + rumble * 0.6, 0.85)


def tank_engine(seed):
    rng = np.random.default_rng(seed)
    tt = t(LOOP)
    firing = sum(tone(42 * k, 1.0 / k ** 0.5) for k in range(1, 13)) * (1 + 0.3 * np.sin(2 * np.pi * 14 * tt))
    grit = periodic_noise(rng, lambda x: lowpass3(x, 900)) * 1.5
    clank = np.zeros(int(SR * LOOP))
    for i in range(16):
        c = metal_click(seed + i, [900 + 100 * (i % 3), 1700], 0.02, 0.06, 0.35)
        o = int(i * len(clank) / 16)
        clank[o:o + len(c)] += c[: len(clank) - o]
    return normalize(firing * 0.5 + grit + clank, 0.85)


def jeep_engine(seed):
    rng = np.random.default_rng(seed)
    tt = t(LOOP)
    engine = sum(tone(46 * k, 1.0 / (k ** 0.8)) for k in range(1, 12)) * (1 + 0.2 * np.sin(2 * np.pi * 23 * tt))
    intake = periodic_noise(rng, lambda x: bandpass(x, 300, 1800)) * 0.8
    return normalize(engine * 0.4 + intake, 0.8)


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
    "bullet_whiz": bullet_whiz,
    "explosion": explosion,
    "explosion_far": explosion_far,
    "jet_engine": jet_engine,
    "heli_rotor": heli_rotor,
    "tank_engine": tank_engine,
    "jeep_engine": jeep_engine,
}
ENGINE_RANGE = {"jet_engine": 96, "heli_rotor": 80, "tank_engine": 48, "jeep_engine": 32}
VARIANTS = {"bullet_whiz": 3, "explosion": 3, "explosion_far": 2, "rifle_shot": 3, "pistol_shot": 3, "shotgun_shot": 2, "sniper_shot": 2, "blaster_shot": 3, "casing": 4}


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
        if name in ENGINE_RANGE:
            index[name] = {"sounds": [{"name": f, "attenuation_distance": ENGINE_RANGE[name], "stream": False} for f in files]}
            continue
        index[name] = {"sounds": [{"name": f, "attenuation_distance": 96} if "shot" in name or name in ("rocket_launch", "tank_cannon", "explosion")
                                  else f for f in files]}
    with open(os.path.join(OUT, "..", "sounds.json"), "w") as fh:
        json.dump(index, fh, indent="\t")
    print("sounds written:", len(SOUNDS))


if __name__ == "__main__":
    main()
