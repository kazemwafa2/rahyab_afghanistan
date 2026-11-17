#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ساخت «بسته نقشه اختصاصی کامل کشور» راه‌یاب — afghanistan_full.

منبع داده: نقشهٔ برداری کامل افغانستان (همهٔ ۳۴ ولایت، زوم ۰ تا ۱۴، طرح
لایه‌های OpenMapTiles) که از دادهٔ رسمی OpenStreetMap ساخته شده — همان
فایل basemap/af.pmtiles که portal «map.af» منتشر می‌کند (بدون هیچ API؛
فقط یک فایل عمومی با درخواست GET ساده).

محتوای بسته:
  - afghanistan_full.pmtiles  ← نقشهٔ کامل سراسری (کاشی z0-14 همهٔ ولایات)
  - afghanistan_full.rafgraph ← گراف مسیریابی کشوری (راه‌های اصلی — از خط تولید خود راه‌یاب)
  - afghanistan_full.rafidx   ← اندیس جست‌وجوی کشوری
  - style.json                ← قالب سبک برنامه (با __SRC_URL__/__FONTS__/__SPRITE__)
  - pkgmanifest.json          ← شناسنامهٔ بسته (dataDate تازه → دکمهٔ به‌روزرسانی در برنامه)

در برنامه: چون OfflineData.basePackage() بستهٔ «afghanistan_full» را بر
«afghanistan» ترجیح می‌دهد، پس از نصب، نقشهٔ کامل (کوچه تا ساختمان) در هر دو
حالت آنلاین و آفلاین از همین فایل محلی رندر می‌شود.

کاربرد:
  python3 tools/build_full_nationwide_package.py \
      --pmtiles /path/af.pmtiles --graph /path/afghanistan.rafgraph \
      --index /path/afghanistan.rafidx --out /path/dist_all
  python3 tools/build_full_nationwide_package.py --download   # خودش از map.af می‌گیرد
"""
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import time
import zipfile
from datetime import date, timezone, datetime

MAP_AF_TILES = "https://tiles.map.af/basemap/af.pmtiles"
BUILDER_VERSION = "1.2.0"

ROOT = os.environ.get("RAHYAB_PROJECT", os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DIST = os.environ.get("RAHYAB_DIST", "/home/z/my-project/workspace/dist_all")
NAME = "afghanistan_full"
REGION = "افغانستان (نقشهٔ اختصاصی کامل)"


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def pmtiles_bounds_zoom(path):
    """محدوده و بازهٔ زوم از سرآیند PMTiles v3 (طبق مشخصات رسمی — بایت ۱۰۰ تا ۱۲۶)."""
    with open(path, "rb") as f:
        h = f.read(127)
    if h[0:7] != b"PMTiles" or h[7] != 3:
        raise ValueError("فایل PMTiles نسخهٔ ۳ نیست")
    min_zoom, max_zoom = h[100], h[101]
    lon0, lat0, lon1, lat1 = struct.unpack("<iiii", h[102:118])
    cz = h[118]
    clon, clat = struct.unpack("<ii", h[119:127])
    return {
        "zoom": [min_zoom, max_zoom],
        "bounds": {"west": lon0 / 1e7, "south": lat0 / 1e7,
                   "east": lon1 / 1e7, "north": lat1 / 1e7},
        "center": {"zoom": cz, "lon": clon / 1e7, "lat": clat / 1e7},
    }


def download_map_af(out_path):
    """دانلود مستقیم فایل عمومی map.af — بدون کلید، بدون API."""
    log(f"دانلود نقشهٔ کامل از {MAP_AF_TILES} …")
    r = subprocess.run(["curl", "-sL", "--retry", "3", "--max-time", "1800",
                        "-o", out_path, MAP_AF_TILES])
    if r.returncode != 0 or not os.path.isfile(out_path) or os.path.getsize(out_path) < 10_000_000:
        log("خطا: دانلود ناموفق بود")
        sys.exit(1)


def graph_index_from_release(cache_dir, date_str="2026-11-15"):
    """گراف/اندیس کشوری از بستهٔ پایهٔ موجود در ریلیز map-data (اگر از قبل نبود دانلود می‌کند)."""
    base_url = ("https://github.com/kazemwafa2/rahyab_afghanistan/"
                f"releases/download/map-data/afghanistan-{date_str}.rahpkg.zip")
    zpath = os.path.join(cache_dir, f"afghanistan-{date_str}.rahpkg.zip")
    os.makedirs(cache_dir, exist_ok=True)
    if not os.path.isfile(zpath) or os.path.getsize(zpath) < 1_000_000:
        log(f"دانلود بستهٔ پایهٔ موجود ({base_url}) برای گراف و اندیس …")
        subprocess.run(["curl", "-sL", "--retry", "3", "-o", zpath, base_url], check=True)
    with zipfile.ZipFile(zpath) as z:
        z.extract("afghanistan.rafgraph", cache_dir)
        z.extract("afghanistan.rafidx", cache_dir)
    return (os.path.join(cache_dir, "afghanistan.rafgraph"),
            os.path.join(cache_dir, "afghanistan.rafidx"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pmtiles", help="مسیر فایل PMTiles کامل کشور (پیش‌فرض: ورودی map.af)")
    ap.add_argument("--download", action="store_true", help="دانلود af.pmtiles از tiles.map.af")
    ap.add_argument("--graph", help="گراف کشوری rafgraph (پیش‌فرض: از ریلیز map-data)")
    ap.add_argument("--index", help="اندیس کشوری rafidx (پیش‌فرض: از ریلیز map-data)")
    ap.add_argument("--out", default=DIST, help="پوشهٔ خروجی")
    ap.add_argument("--data-date", default=date.today().isoformat())
    ap.add_argument("--cache", default=os.path.join(os.path.dirname(DIST.rstrip("/")) or ".", "cache"),
                    help="پوشهٔ کش برای دانلود گراف/اندیس")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    work = os.path.join(args.out, "_work_full")
    os.makedirs(work, exist_ok=True)

    # ۱) کاشی کامل کشور
    pmt = args.pmtiles
    if pmt is None:
        pmt = os.path.join(work, "af.pmtiles")
        if args.download or not os.path.isfile(pmt):
            download_map_af(pmt)
    info = pmtiles_bounds_zoom(pmt)
    log(f"کاشی: {os.path.getsize(pmt)/1e6:.1f}MB زوم {info['zoom'][0]}-{info['zoom'][1]} "
        f"محدوده {info['bounds']['west']:.3f},{info['bounds']['south']:.3f} → "
        f"{info['bounds']['east']:.3f},{info['bounds']['north']:.3f}")
    if info["zoom"][1] < 13:
        log("هشدار: کاشی زوم کامل ندارد؛ برنامهٔ راه‌یاب این بسته را به‌عنوان نقشهٔ کامل نمی‌شناسد")
        sys.exit(1)

    # ۲) گراف و اندیس
    if args.graph and args.index:
        graph, index = args.graph, args.index
    else:
        graph, index = graph_index_from_release(args.cache)
    log(f"گراف: {os.path.getsize(graph)/1e6:.1f}MB | اندیس: {os.path.getsize(index)/1e6:.1f}MB")

    # ۳) سبک برنامه (قالب با جای‌گذاری‌ها)
    style_src = os.path.join(ROOT, "app", "src", "main", "assets", "style", "rahyab_light.json")
    style_dst = os.path.join(work, "style.json")
    shutil.copyfile(style_src, style_dst)

    # ۴) بستهٔ زیپ
    data_date = args.data_date
    zip_name = f"{NAME}-{data_date}.rahpkg.zip"
    zip_path = os.path.join(args.out, zip_name)

    tiles_inner = f"{NAME}.pmtiles"
    graph_inner = f"{NAME}.rafgraph"
    index_inner = f"{NAME}.rafidx"

    manifest = {
        "schema": 1,
        "id": NAME,
        "name": REGION,
        "region": REGION,
        "dataDate": data_date,
        "builderVersion": BUILDER_VERSION,
        "bounds": info["bounds"],
        "formats": {"graph": "RAFG1/1", "index": "RAFI1/1", "tiles": "openmaptiles pmtiles"},
        "license": "© OpenStreetMap contributors — ODbL (کاشی‌ها بازساختهٔ طرح OpenMapTiles، منبع دادهٔ نقشهٔ افغانستان map.af)",
        "files": [],
    }
    files = [
        (graph_inner, graph),
        (index_inner, index),
        (tiles_inner, pmt),
        ("style.json", style_dst),
    ]
    log("ساخت بستهٔ زیپ …")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as z:
        for inner, src in files:
            infoz = zipfile.ZipInfo(inner, date_time=time.gmtime()[:6])
            infoz.compress_type = zipfile.ZIP_STORED
            with open(src, "rb") as f, z.open(infoz, "w") as dst:
                shutil.copyfileobj(f, dst, 1 << 20)
            manifest["files"].append({"name": inner, "sizeBytes": os.path.getsize(src),
                                      "sha256": sha256_file(src)})
        mtmp = os.path.join(work, "pkgmanifest.json")
        with open(mtmp, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        z.write(mtmp, "pkgmanifest.json")

    total = os.path.getsize(zip_path)
    log(f"بستهٔ کامل کشور: {zip_path} ({total/1e6:.1f}MB)")
    log(f"SHA-256: {sha256_file(zip_path)}")
    return zip_path


if __name__ == "__main__":
    main()
