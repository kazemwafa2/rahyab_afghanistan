# -*- coding: utf-8 -*-
"""Generate Rahyab voice packs with edge-tts (Azure neural).

Packs:
  - latifa: REPAIR of the Pashto female pack (was generated ~25% too fast and
    ~10 dB too hot — user complaint "صدای زن خرابه"). Regenerated at the natural
    pace and default level, ps only, same root layout.
  - sahar/nazira/najib/rahmat: NEW multi-language personas (fa + ps + en each),
    two female + two male, selectable regardless of the app language.

Resume-safe: finished clips are skipped; failures retried 3x.
Output staging: /home/z/my-project/scripts/voice_staging/<pack>/...
"""
import asyncio, json, os, subprocess, sys, time
import edge_tts
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
# پیش‌فرض: staging کنار همین اسکریپت؛ با متغیر محیطی RAHYAB_STAGING قابل تغییر
STAGE = os.environ.get("RAHYAB_STAGING", os.path.join(HERE, "voice_staging"))
REPO_VOICE = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "voice"))
TEXTS = json.load(open(os.path.join(HERE, "voice_texts.json"), encoding="utf-8"))

# lang -> (edge-tts voice, rate, pitch)
PACKS = {
    "latifa": {  # repair, single language (ps), root layout like other legacy packs
        "name": "لطیفه", "gender": "female", "layout": "root",
        "note": "صدای عصبی واقعی — بازضبط‌شده با آهنگ طبیعی گفتار (نسخهٔ ۱٫۱٫۰)",
        "clips": {"ps": ("ps-AF-LatifaNeural", "-4%", "+0Hz")},
    },
    "sahar": {
        "name": "سحر", "gender": "female", "layout": "multi",
        "note": "صدای عصبی واقعی — برای همهٔ زبان‌های برنامه (دری/پشتو/انگلیسی)",
        "clips": {
            "fa": ("fa-IR-DilaraNeural", "-2%", "+10Hz"),
            "ps": ("ps-AF-LatifaNeural", "-6%", "+8Hz"),
            "en": ("en-US-AvaNeural", "-2%", "+6Hz"),
        },
    },
    "nazira": {
        "name": "نظیره", "gender": "female", "layout": "multi",
        "note": "صدای عصبی واقعی — برای همهٔ زبان‌های برنامه (دری/پشتو/انگلیسی)",
        "clips": {
            "fa": ("fa-IR-DilaraNeural", "-8%", "-14Hz"),
            "ps": ("ps-AF-LatifaNeural", "-10%", "-10Hz"),
            "en": ("en-US-EmmaNeural", "-6%", "-8Hz"),
        },
    },
    "najib": {
        "name": "نجیب", "gender": "male", "layout": "multi",
        "note": "صدای عصبی واقعی — برای همهٔ زبان‌های برنامه (دری/پشتو/انگلیسی)",
        "clips": {
            "fa": ("fa-IR-FaridNeural", "+0%", "+10Hz"),
            "ps": ("ps-AF-GulNawazNeural", "-6%", "+6Hz"),
            "en": ("en-US-AndrewNeural", "+0%", "+4Hz"),
        },
    },
    "rahmat": {
        "name": "رحمت", "gender": "male", "layout": "multi",
        "note": "صدای عصبی واقعی — برای همهٔ زبان‌های برنامه (دری/پشتو/انگلیسی)",
        "clips": {
            "fa": ("fa-IR-FaridNeural", "-8%", "-16Hz"),
            "ps": ("ps-AF-GulNawazNeural", "-10%", "-12Hz"),
            "en": ("en-US-BrianNeural", "-6%", "-10Hz"),
        },
    },
}


async def tts_clip(text, voice, rate, pitch, mp3path):
    c = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
    await c.save(mp3path)


def one_clip(job):
    (name, text, src_mp3, dst_ogg, voice, rate, pitch) = job
    if os.path.isfile(dst_ogg) and os.path.getsize(dst_ogg) > 800:
        return ("skip", name)
    for attempt in range(3):
        try:
            asyncio.run(tts_clip(text, voice, rate, pitch, src_mp3))
            break
        except Exception as e:
            if attempt == 2:
                return ("err", f"{name}: tts {e}")
            time.sleep(1.5 * (attempt + 1))
    p = subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", src_mp3, "-af", "aresample=24000",
         "-ac", "1", "-c:a", "libvorbis", "-q:a", "4", dst_ogg],
        capture_output=True, text=True)
    if p.returncode != 0 or not os.path.isfile(dst_ogg) or os.path.getsize(dst_ogg) < 800:
        return ("err", f"{name}: encode {p.stderr[-150:]}")
    m = subprocess.run(["ffmpeg", "-i", dst_ogg, "-af", "volumedetect", "-f", "null", "/dev/null"],
                       capture_output=True, text=True)
    mean = 0.0
    for line in m.stderr.splitlines():
        if "mean_volume" in line:
            mean = float(line.split(":")[1].replace("dB", "").strip())
    if mean > -12:
        return ("err", f"{name}: too loud ({mean:.1f}dB)")
    return ("ok", name)


def build_jobs(pack, spec):
    jobs = []
    for lang, (voice, rate, pitch) in spec["clips"].items():
        sub = "" if spec["layout"] == "root" else lang
        outdir = os.path.join(STAGE, pack, sub)
        os.makedirs(outdir, exist_ok=True)
        tmpdir = os.path.join(STAGE, "_mp3", pack, sub or "ps")
        os.makedirs(tmpdir, exist_ok=True)
        for key, entry in TEXTS.items():
            jobs.append((f"{pack}/{sub or 'ps'}/{key}", entry[lang],
                         os.path.join(tmpdir, f"{key}.mp3"),
                         os.path.join(outdir, f"{key}.ogg"),
                         voice, rate, pitch))
    return jobs


def main():
    pack = sys.argv[1]
    spec = PACKS[pack]
    jobs = build_jobs(pack, spec)
    print(f"[{pack}] {len(jobs)} clips", flush=True)

    done = err = 0
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=6) as ex:
        for i, (status, msg) in enumerate(ex.map(one_clip, jobs)):
            if status == "err":
                err += 1
                print(f"  ERR {msg}", flush=True)
            else:
                done += 1
            if (i + 1) % 25 == 0:
                print(f"  [{pack}] {i+1}/{len(jobs)} ok={done} err={err} {time.time()-t0:.0f}s", flush=True)

    print(f"[{pack}] FINISHED ok/skip={done} err={err} in {time.time()-t0:.0f}s", flush=True)
    return err


if __name__ == "__main__":
    sys.exit(0 if main() == 0 else 1)
