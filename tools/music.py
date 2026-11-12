"""Ruby Wars' own music and jingles (Ogg Vorbis), synthesized like the weapon sounds in sounds.py.

Run from the project root: python tools/music.py   (needs numpy and soundfile)
Writes src/main/resources/assets/rubymod/sounds/music_*.ogg and adds them to sounds.json (leaving what's there alone).

  hub_theme     the plaza's tune: a calm 20-second loop (plucked arpeggios over a soft pad and bass)
  win_jingle    a rising fanfare for whoever won
  lose_jingle   a short falling phrase for everyone else
  round_start   the "go" sting when a round begins
  level_up      a quick sparkle up the scale
  golden        a dramatic hit: the Golden Blade, the last survivor
  boss_sting    low brass and a drum: a boss or a Warlord arrives
  alarm         a two-tone klaxon: the Core or the truck is in trouble
  pickup        a blip for pick-ups

Everything is built from three instruments (pluck, brass, pad), a drum, and the same little room echo.
"""
import json
import os

import numpy as np
import soundfile as sf

SR = 44100
OUT = "src/main/resources/assets/rubymod/sounds"


def hz(note):
    """MIDI note number to frequency (69 = the A above middle C)."""
    return 440.0 * 2 ** ((note - 69) / 12.0)


def tt(seconds):
    return np.arange(int(SR * seconds)) / SR


def lowpass(x, cutoff):
    """One-pole lowpass, vectorized through a cumulative trick being impractical: a short FIR does the job here."""
    n = max(3, int(SR / cutoff) | 1)
    kernel = np.hanning(n)
    kernel /= kernel.sum()
    return np.convolve(x, kernel, mode="same")


def pluck(note, seconds, bright=1.0):
    """A plucked string / music-box note: a few harmonics, each dying away faster than the last."""
    t = tt(seconds)
    f = hz(note)
    out = np.zeros_like(t)
    for k, amp in ((1, 1.0), (2, 0.45 * bright), (3, 0.22 * bright), (4, 0.1 * bright), (6, 0.04 * bright)):
        out += amp * np.sin(2 * np.pi * f * k * t) * np.exp(-t * (2.2 + k * 1.4))
    attack = int(SR * 0.004)
    out[:attack] *= np.linspace(0, 1, attack)
    return out


def brass(note, seconds, swell=0.06):
    """A brass-like tone: a stack of harmonics, softened, with a quick swell and a held body."""
    t = tt(seconds)
    f = hz(note)
    out = np.zeros_like(t)
    for k in range(1, 9):
        out += np.sin(2 * np.pi * f * k * t + 0.3 * k) / k
    out = lowpass(out, 2600)
    env = np.minimum(1.0, t / swell) * np.minimum(1.0, (seconds - t) / 0.12).clip(0, 1)
    vibrato = 1 + 0.004 * np.sin(2 * np.pi * 5.5 * t) * np.minimum(1.0, t / 0.3)
    return out * env * vibrato * 0.5


def pad(note, seconds):
    """A soft held chord tone: two slightly detuned sines that fade in and out slowly."""
    t = tt(seconds)
    f = hz(note)
    out = np.sin(2 * np.pi * f * t) + 0.6 * np.sin(2 * np.pi * f * 1.004 * t) + 0.25 * np.sin(2 * np.pi * f * 2 * t)
    env = np.minimum(1.0, t / 0.5) * np.minimum(1.0, (seconds - t) / 0.6).clip(0, 1)
    return out * env * 0.3


def drum(seconds=0.5, low=58.0):
    """A big soft drum: a falling thump with a puff of noise on top."""
    t = tt(seconds)
    rng = np.random.default_rng(7)
    thump = np.sin(2 * np.pi * (low * t + 40 * (1 - np.exp(-t * 18)) / 18)) * np.exp(-t * 7)
    hit = lowpass(rng.uniform(-1, 1, len(t)), 900) * np.exp(-t * 30) * 0.6
    return thump + hit


def put(track, sound, at, gain=1.0):
    """Mix a sound into the track, starting at `at` seconds."""
    i = int(at * SR)
    n = min(len(sound), len(track) - i)
    if n > 0:
        track[i:i + n] += sound[:n] * gain


def room(x, wet=0.25):
    """A small hall: a handful of echoes."""
    out = x.copy()
    for delay, gain in ((0.031, 0.5), (0.057, 0.38), (0.093, 0.3), (0.141, 0.22), (0.211, 0.15), (0.307, 0.1)):
        d = int(delay * SR)
        out[d:] += x[:-d] * gain * wet
    return out


def finish(x, peak=0.85, fade=0.02):
    x = room(x)
    n = int(fade * SR)
    x[-n:] *= np.linspace(1, 0, n)
    return (x / max(1e-9, np.abs(x).max()) * peak).astype(np.float32)


def hub_theme():
    """Am - F - C - G, twice round, 96 beats a minute: 8 bars of four."""
    beat = 60 / 96
    bars = 8
    length = bars * 4 * beat
    track = np.zeros(int((length + 1.0) * SR))
    chords = [(57, 60, 64), (53, 57, 60), (48, 52, 55), (55, 59, 62)]
    melody = [76, None, 72, 74, 76, None, 79, 76, 72, None, 69, 72, 74, None, 71, None,
              76, None, 72, 74, 76, 77, 79, None, 84, None, 79, 76, 74, None, 71, None]
    for bar in range(bars):
        root, third, fifth = chords[bar % 4]
        at = bar * 4 * beat
        for n in (root, third, fifth):
            put(track, pad(n, 4 * beat + 0.4), at, 0.5)
        put(track, pluck(root - 12, 2.0, 0.4), at, 0.9)
        put(track, pluck(root - 12, 1.2, 0.4), at + 2.5 * beat, 0.6)
        # Eighth-note arpeggio up and down the chord, an octave higher.
        pattern = [root, fifth, third + 12, fifth, root + 12, fifth, third + 12, fifth]
        for i, n in enumerate(pattern):
            put(track, pluck(n + 12, 0.9), at + i * 0.5 * beat, 0.32 if i % 2 else 0.42)
        for i in range(4):
            n = melody[(bar * 4 + i) % len(melody)]
            if n is not None:
                put(track, pluck(n, 1.6, 1.3), at + i * beat, 0.5)
    # So the loop joins up: fold the ring-out after the last bar back onto the start.
    loop = int(length * SR)
    track[:len(track) - loop] += track[loop:]
    out = room(track[:loop])
    return (out / np.abs(out).max() * 0.7).astype(np.float32)


def win_jingle():
    track = np.zeros(int(3.2 * SR))
    for i, (n, at, dur) in enumerate(((60, 0.0, 0.18), (64, 0.16, 0.18), (67, 0.32, 0.18), (72, 0.48, 0.5), (67, 0.92, 0.16), (72, 1.08, 1.5))):
        put(track, brass(n, dur + 0.1), at, 0.9)
        put(track, brass(n - 12, dur + 0.1), at, 0.5)
    for n in (64, 67, 76):
        put(track, brass(n, 1.6), 1.08, 0.45)
    for at in (0.48, 1.08):
        put(track, drum(0.7), at, 0.9)
    for i, n in enumerate((84, 88, 91, 96, 91, 96)):
        put(track, pluck(n, 0.8, 1.4), 1.1 + i * 0.09, 0.3)
    return finish(track)


def lose_jingle():
    track = np.zeros(int(2.6 * SR))
    for n, at, dur in ((67, 0.0, 0.3), (65, 0.3, 0.3), (63, 0.6, 0.3), (60, 0.9, 1.3)):
        put(track, brass(n, dur + 0.1, 0.1), at, 0.7)
        put(track, brass(n - 12, dur + 0.1, 0.1), at, 0.5)
    put(track, pad(48, 1.6), 0.9, 0.8)
    put(track, drum(0.8, 48), 0.9, 0.7)
    return finish(track, 0.75)


def round_start():
    track = np.zeros(int(1.4 * SR))
    for n, at in ((67, 0.0), (67, 0.14), (72, 0.28)):
        put(track, brass(n, 0.16 if at < 0.28 else 0.8), at, 0.9)
    put(track, brass(60, 0.8), 0.28, 0.5)
    put(track, drum(0.6), 0.28, 0.9)
    return finish(track)


def level_up():
    track = np.zeros(int(1.6 * SR))
    for i, n in enumerate((72, 76, 79, 84, 88, 91)):
        put(track, pluck(n, 1.0, 1.4), i * 0.075, 0.6)
    put(track, pad(72, 1.2), 0.3, 0.5)
    put(track, pad(79, 1.2), 0.3, 0.4)
    return finish(track, 0.8)


def golden():
    track = np.zeros(int(2.6 * SR))
    put(track, drum(1.0, 44), 0.0, 1.0)
    for n in (50, 57, 62, 65):
        put(track, brass(n, 2.0, 0.02), 0.0, 0.7)
    for i, n in enumerate((86, 81, 86, 89)):
        put(track, pluck(n, 1.2, 1.5), 0.25 + i * 0.12, 0.4)
    return finish(track)


def boss_sting():
    track = np.zeros(int(3.0 * SR))
    for at in (0.0, 0.45, 0.9):
        put(track, drum(0.9, 40), at, 1.0)
    for n, at, dur in ((38, 0.0, 0.45), (38, 0.45, 0.45), (41, 0.9, 1.8)):
        put(track, brass(n, dur + 0.1, 0.03), at, 0.9)
        put(track, brass(n + 12, dur + 0.1, 0.03), at, 0.6)
    put(track, brass(56, 1.8, 0.2), 0.9, 0.4)
    return finish(track)


def alarm():
    track = np.zeros(int(1.3 * SR))
    for i in range(4):
        n = 81 if i % 2 == 0 else 76
        t = tt(0.28)
        tone = np.sign(np.sin(2 * np.pi * hz(n) * t)) * 0.4 + np.sin(2 * np.pi * hz(n) * t) * 0.6
        env = np.minimum(1.0, t / 0.01) * np.minimum(1.0, (0.28 - t) / 0.03).clip(0, 1)
        put(track, lowpass(tone, 3200) * env, i * 0.3, 0.6)
    return finish(track, 0.7)


def pickup():
    track = np.zeros(int(0.6 * SR))
    put(track, pluck(84, 0.4, 1.5), 0.0, 0.7)
    put(track, pluck(91, 0.5, 1.5), 0.07, 0.7)
    return finish(track, 0.7)


SOUNDS = {"hub_theme": hub_theme, "win_jingle": win_jingle, "lose_jingle": lose_jingle, "round_start": round_start, "level_up": level_up,
          "golden": golden, "boss_sting": boss_sting, "alarm": alarm, "pickup": pickup}


def main():
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "..", "sounds.json")
    with open(path) as fh:
        index = json.load(fh)
    for name, make in SOUNDS.items():
        data = make()
        # In pieces: the Vorbis writer falls over when handed a long sound in one go.
        with sf.SoundFile(os.path.join(OUT, "music_" + name + ".ogg"), "w", samplerate=SR, channels=1, format="OGG", subtype="VORBIS") as out:
            for i in range(0, len(data), 8192):
                out.write(data[i:i + 8192])
        entry = {"name": "rubymod:music_" + name}
        if name == "hub_theme":
            # Long: streamed rather than loaded whole, and heard all over the plaza.
            entry.update({"stream": True, "attenuation_distance": 96})
        index[name] = {"sounds": [entry]}
        print(f"  {name}: {len(data) / SR:.1f}s")
    with open(path, "w") as fh:
        json.dump(index, fh, indent="\t")
    print("music written:", len(SOUNDS))


if __name__ == "__main__":
    main()
