#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ساخت «بسته پایه کشوری» راه‌یاب — یک بسته برای تمام افغانستان:
  - کاشی‌های کشوری (زوم ۰ تا ۸) با planetiler از PBF کامل کشور
  - گراف مسیریابی فقط راه‌های اصلی (موتربان/اصلی/فرعی — رتبه ≥ ۶۳)
  - اندیس کامل مکان‌ها و مکان‌های عمومی سراسر کشور
این بسته در APK گنجانده می‌شود تا برنامه از نخستین اجرا، بدون دانلود،
نقشه کل کشور، مسیریابی بین‌ولایتی و جست‌وجوی سراسری داشته باشد.

کاربرد:
  python3 tools/build_base_package.py            # ساخت کامل
  python3 tools/build_base_package.py --skip-tiles   # استفاده از کاشی موجود
"""
import argparse
import os
import subprocess
import sys
import time

ROOT = os.environ.get("RAHYAB_WORKSPACE", "/home/z/my-project/workspace")
PROJ = os.environ.get("RAHYAB_PROJECT", os.path.join(ROOT, "rahyab-afghanistan"))
PBF = os.environ.get("RAHYAB_PBF", os.path.join(ROOT, "data", "afghanistan-latest.osm.pbf"))
DIST = os.environ.get("RAHYAB_DIST", os.path.join(ROOT, "dist_all"))
WORK = os.environ.get("RAHYAB_WORK", os.path.join(ROOT, "work_base"))
PLANETILER = os.environ.get("RAHYAB_PLANETILER", "/home/z/my-project/.tools/planetiler/planetiler.jar")

# محدوده کل افغانستان (با حاشیه امن برای گذرگاه‌های مرزی)
BBOX = (60.30, 28.90, 75.25, 38.55)
NAME = "afghanistan"
REGION = "افغانستان (کل کشور)"


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-tiles", action="store_true")
    ap.add_argument("--min-rank", type=int, default=63)
    args = ap.parse_args()

    os.makedirs(WORK, exist_ok=True)
    os.makedirs(DIST, exist_ok=True)
    bstr = ",".join(str(x) for x in BBOX)

    # ۱) کاشی‌های کشوری زوم ۰-۸
    pmt = os.path.join(WORK, f"{NAME}.pmtiles")
    if args.skip_tiles and os.path.isfile(pmt):
        log("کاشی موجود استفاده شد")
    else:
        cache = os.path.join(ROOT, "work", "data", "sources")
        ne = os.path.join(cache, "natural_earth_vector.sqlite.zip")
        lc = os.path.join(cache, "lake_centerline.shp.zip")
        wp = os.path.join(cache, "water-polygons-split-3857.zip")
        pcargs = []
        need_dl = False
        if os.path.isfile(ne):
            pcargs.append(f"--natural_earth_path={ne}")
        else:
            need_dl = True
        if os.path.isfile(lc):
            pcargs.append(f"--lake_centerline_path={lc}")
        else:
            need_dl = True
        if os.path.isfile(wp):
            pcargs.append(f"--water_polygons_path={wp}")
        else:
            need_dl = True
        if need_dl:
            pcargs.append("--download")
        t0 = time.time()
        log("planetiler: کاشی‌های کشوری زوم ۰-۸ …")
        r = subprocess.run(
            ["java", "-Xmx1600m", "-jar", PLANETILER,
             "--area=afghanistan", f"--osm_path={PBF}", f"--bounds={bstr}",
             "--maxzoom=8", f"--output={pmt}", "--force"] + pcargs,
            cwd=WORK, capture_output=True, text=True)
        if r.returncode != 0 or not os.path.isfile(pmt):
            log(f"خطای planetiler: {r.stdout[-1200:]} {r.stderr[-1200:]}")
            sys.exit(1)
        log(f"کاشی کشوری: {os.path.getsize(pmt)/1e6:.1f}MB ({time.time()-t0:.0f} ثانیه)")

    # ۲) گراف اصلی + اندیس کامل + بسته
    t0 = time.time()
    r = subprocess.run(
        [sys.executable, os.path.join(PROJ, "tools", "build_package.py"),
         "--name", NAME, "--region", REGION, "--pbf", PBF, "--bbox", bstr,
         "--out", DIST, "--work", WORK, "--tiles", pmt, "--skip-fonts",
         "--min-rank", str(args.min_rank)],
        cwd=PROJ)
    if r.returncode != 0:
        log("خطای build_package")
        sys.exit(1)
    log(f"بسته پایه در {time.time()-t0:.0f} ثانیه ساخته شد")


if __name__ == "__main__":
    main()
