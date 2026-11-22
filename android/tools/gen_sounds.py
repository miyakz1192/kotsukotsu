"""九九ゲームの効果音を合成して game-kuku/src/main/res/raw/ に書き出す（外部素材を使わないのでライセンス不要）。
   python3 tools/gen_sounds.py"""
import math, struct, wave
from pathlib import Path

SR = 22050
OUT = Path(__file__).resolve().parents[1] / "game-kuku/src/main/res/raw"


def tone(freq, dur, vol=0.5, shape="sine", attack=0.005, decay=None, f_end=None):
    n = int(SR * dur)
    out = []
    ph = 0.0
    for i in range(n):
        t = i / SR
        f = freq if f_end is None else freq + (f_end - freq) * (i / n)
        ph += 2 * math.pi * f / SR
        if shape == "sine":
            s = math.sin(ph) + 0.3 * math.sin(2 * ph) + 0.1 * math.sin(3 * ph)
        else:  # square-ish soft
            s = math.tanh(3 * math.sin(ph))
        env = min(1.0, t / attack) if attack else 1.0
        env *= math.exp(-t / decay) if decay else max(0.0, 1 - t / dur)
        out.append(s * env * vol)
    return out


def seq(*parts, gap=0.0):
    out = []
    for p in parts:
        out += p + [0.0] * int(SR * gap)
    return out


def mix(a, b, offset=0.0):
    o = int(SR * offset)
    out = a + [0.0] * max(0, o + len(b) - len(a))
    for i, v in enumerate(b):
        out[o + i] += v
    return out


def write(name, samples):
    peak = max(1e-9, max(abs(s) for s in samples))
    k = 0.85 / peak
    with wave.open(str(OUT / f"{name}.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(s * k * 32767)) for s in samples))


OUT.mkdir(parents=True, exist_ok=True)
# 正解：爽快な上昇2音（ピコーン）
write("snd_correct", mix(tone(1318.5, 0.09, decay=0.08), tone(1760.0, 0.28, decay=0.12), 0.07))
# 不正解：少し残念な下降音（ぶぶー、やわらかめ）
write("snd_wrong", tone(330, 0.42, vol=0.35, shape="square", decay=0.25, f_end=220))
# モード結果のファンファーレ（ド・ミ・ソ・ド）
write("snd_fanfare", seq(tone(523.3, 0.12, decay=0.1), tone(659.3, 0.12, decay=0.1), tone(784.0, 0.12, decay=0.1),
                         mix(tone(1046.5, 0.6, decay=0.3), tone(1318.5, 0.6, vol=0.3, decay=0.3))))
# 上限到達・自己ベストのきらきら音
write("snd_sparkle", seq(*[tone(f, 0.08, vol=0.4, decay=0.06) for f in (1568, 1976, 2349, 2637, 3136)], tone(3520, 0.35, vol=0.4, decay=0.15)))
# テンキーのタップ音
write("snd_tap", tone(880, 0.03, vol=0.25, decay=0.01))
print("ok", sorted(p.name for p in OUT.glob("*.wav")))
