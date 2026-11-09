#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ساخت بسته‌های آفلاین راه‌یاب برای تمام ۳۴ ولایت افغانستان.

مراحل:
  ۱) یک گذر روی PBF کشور: گره‌ها به هر ولایتی که داخل محدوده‌اش باشد نوشته می‌شود؛
     ماسک ولایت هر گره در آرایه numpy ذخیره می‌شود (حافظه کم، بدون دیکشنری بزرگ).
  ۲) گذر دوم: هر راه به ولایتی نوشته می‌شود که همه گره‌هایش داخل آن باشد
     (دقیقاً همان معنای استخراج قبلی — بریدگی مرز ولسوالی‌ها مستند است).
  ۳) برای هر ولایت: planetiler (کاشی) + build_package.py (گراف + اندیس + بسته) + اعتبارسنجی.
  ۴) فایل‌های میانی حذف می‌شوند تا فضای دیسک حفظ شود.

کاربرد:
  python3 scripts/build_all_provinces.py --only kabul,herat   (اختیاری)
"""
import argparse
import array
import os
import shutil
import subprocess
import sys
import time
import zipfile

import numpy as np
import osmium

# شناسه، نام دری، محدوده (W,S,E,N) — محدوده‌ها با حاشیه امن تا همه ولسوالی‌ها را بپوشانند
PROVINCES = [
    ("kabul",         "کابل",          (68.80, 34.15, 69.70, 35.05)),
    ("herat",         "هرات",          (60.20, 32.85, 63.40, 34.95)),
    ("kandahar",      "قندهار",        (64.70, 29.95, 67.00, 32.15)),
    ("balkh",         "بلخ",           (65.60, 36.25, 67.60, 37.45)),
    ("nangarhar",     "ننگرهار",       (69.80, 33.65, 71.30, 35.05)),
    ("ghazni",        "غزنی",          (66.90, 32.25, 69.20, 34.15)),
    ("helmand",       "هلمند",         (61.70, 29.35, 65.30, 32.65)),
    ("bamyan",        "بامیان",        (66.20, 33.25, 68.30, 35.25)),
    ("kunduz",        "کندوز",         (68.30, 36.25, 69.30, 37.45)),
    ("takhar",        "تخار",          (69.30, 36.15, 71.00, 37.65)),
    ("badakhshan",    "بدخشان",        (70.25, 34.30, 75.15, 38.49)),
    ("baghlan",       "بغلان",         (67.60, 35.20, 69.45, 36.70)),
    ("parwan",        "پروان",         (68.40, 34.75, 69.60, 35.80)),
    ("logar",         "لوگر",          (68.50, 33.55, 69.50, 34.65)),
    ("maidan_wardak", "میدان وردک",    (67.30, 33.60, 69.00, 34.95)),
    ("kapisa",        "کاپیسا",        (69.20, 34.55, 70.00, 35.35)),
    ("panjshir",      "پنجشیر",        (68.90, 34.95, 69.90, 35.95)),
    ("laghman",       "لغمان",         (69.90, 34.35, 70.80, 35.05)),
    ("kunar",         "کنر",           (70.40, 34.35, 71.70, 35.60)),
    ("nuristan",      "نورستان",       (70.20, 34.55, 71.50, 35.95)),
    ("paktia",        "پکتیا",         (68.80, 33.25, 70.00, 34.15)),
    ("paktika",       "پکتیکا",        (67.70, 31.65, 69.40, 33.70)),
    ("khost",         "خوست",          (69.40, 33.05, 70.80, 34.10)),
    ("zabul",         "زابل",          (66.00, 30.75, 68.00, 32.55)),
    ("urozgan",       "ارزگان",        (65.20, 31.45, 67.20, 33.35)),
    ("farah",         "فراه",          (60.50, 31.85, 63.30, 33.75)),
    ("nimruz",        "نیمروز",        (60.70, 28.95, 63.50, 31.65)),
    ("badghis",       "بادغیس",        (61.90, 33.85, 64.10, 35.70)),
    ("ghor",          "غور",           (62.80, 32.85, 65.50, 35.05)),
    ("faryab",        "فاریاب",        (63.20, 35.35, 65.10, 37.25)),
    ("jowzjan",       "جوزجان",        (65.40, 36.45, 67.10, 37.55)),
    ("sar_e_pol",     "سرپل",          (65.10, 35.15, 66.70, 36.65)),
    ("samangan",      "سمنگان",        (65.90, 35.35, 67.70, 36.75)),
    ("daykundi",      "دایکندی",       (65.30, 33.35, 67.10, 34.80)),
]

# مسیرها — قابل تغییر با متغیرهای محیطی (برای GitHub Actions)
ROOT = os.environ.get("RAHYAB_WORKSPACE", "/home/z/my-project/workspace")
PROJ = os.environ.get("RAHYAB_PROJECT", os.path.join(ROOT, "rahyab-afghanistan"))
PBF = os.environ.get("RAHYAB_PBF", os.path.join(ROOT, "data", "afghanistan-latest.osm.pbf"))
DIST = os.environ.get("RAHYAB_DIST", os.path.join(ROOT, "dist_all"))
WORK = os.environ.get("RAHYAB_WORK", os.path.join(ROOT, "work_prov"))
PLANETILER = os.environ.get("RAHYAB_PLANETILER", "/home/z/my-project/.tools/planetiler/planetiler.jar")


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


CHUNK = 8


def province_groups():
    return [PROVINCES[i:i + CHUNK] for i in range(0, len(PROVINCES), CHUNK)]


def split_group(gi):
    """تقسیم یک گروه ۸ ولایتی (دو گذر روی PBF کشور). قابل ازسرگیری: فایل‌های موجود رد می‌شوند."""
    group = [p for p in province_groups()[gi] if not os.path.isfile(os.path.join(WORK, f"{p[0]}.pbf"))]
    if not group:
        log(f"گروه {gi}: همه PBFها از قبل موجود است")
        return
    os.makedirs(WORK, exist_ok=True)
    ids = [p[0] for p in group]
    bbox_py = [tuple(float(x) for x in p[2]) for p in group]

    writers = {pid: osmium.SimpleWriter(os.path.join(WORK, f"{pid}.pbf")) for pid in ids}
    node_ids = array.array("q")
    node_mask = array.array("Q")
    n_written = [0]

    t0 = time.time()
    log(f"گروه {gi}: گره‌های {', '.join(ids)}…")

    class NodePass(osmium.SimpleHandler):
        def node(hh, nd):
            lat = nd.location.lat
            lon = nd.location.lon
            mask = 0
            bit = 1
            for i in range(len(bbox_py)):
                w, s, e, n = bbox_py[i]
                if s <= lat <= n and w <= lon <= e:
                    mask |= bit
                    writers[ids[i]].add_node(nd)
                    n_written[0] += 1
                bit <<= 1
            if mask:
                node_ids.append(nd.id)
                node_mask.append(mask)

    NodePass().apply_file(PBF)
    log(f"  گره‌ها: {len(node_ids):,} داخل گروه، {n_written[0]:,} نوشته ({time.time()-t0:.0f} ثانیه)")

    keys = np.frombuffer(node_ids, dtype=np.int64)
    masks = np.frombuffer(node_mask, dtype=np.uint64)
    order = np.argsort(keys, kind="stable")
    keys_s = keys[order]
    masks_s = masks[order]
    del keys, masks, order, node_ids, node_mask

    t1 = time.time()
    stats = {"ways": 0, "written": 0}

    class WayPass(osmium.SimpleHandler):
        def way(hh, w):
            stats["ways"] += 1
            refs = np.fromiter((nd.ref for nd in w.nodes), dtype=np.int64,
                               count=len(w.nodes))
            if refs.size < 2:
                return
            pos = np.searchsorted(keys_s, refs)
            np.clip(pos, 0, keys_s.size - 1, out=pos)
            if not np.all(keys_s[pos] == refs):
                return  # گره‌ای خارج از همه ولایت‌های این گروه
            common = np.bitwise_and.reduce(masks_s[pos])
            if common == 0:
                return
            stats["written"] += 1
            m = int(common)
            i = 0
            while m:
                if m & 1:
                    writers[ids[i]].add_way(w)
                m >>= 1
                i += 1

    WayPass().apply_file(PBF)
    for pid in ids:
        writers[pid].close()
    log(f"  راه‌ها: {stats['ways']:,} خوانده، {stats['written']:,} نوشته ({time.time()-t1:.0f} ثانیه)")
    del keys_s, masks_s


def ensure_water_source(cache):
    """اگر چندضلعی‌های آب موجود نباشد، یک مجموعه خالی معتبر می‌سازد
    (افغانستان ساحل ندارد؛ دریاچه‌ها و رودخانه‌ها از خود داده OSM می‌آیند)."""
    wp = os.path.join(cache, "water-polygons-split-3857.zip")
    if os.path.isfile(wp):
        return wp
    try:
        import shapefile
        os.makedirs(cache, exist_ok=True)
        base = os.path.join(cache, "water_empty_3857")
        w = shapefile.Writer(base, shapeType=shapefile.POLYGON)
        w.field("featurecla", "C", 40)
        w.close()
        prj = ('PROJCS["WGS 84 / Pseudo-Mercator",GEOGCS["WGS 84",DATUM["WGS_1984",'
               'SPHEROID["WGS 84",6378137,298.257223563]],PRIMEM["Greenwich",0],'
               'UNIT["degree",0.0174532925199433]],PROJECTION["Mercator_1SP"],'
               'UNIT["metre",1],AUTHORITY["EPSG","3857"]]')
        with open(base + ".prj", "w") as f:
            f.write(prj)
        with zipfile.ZipFile(wp, "w") as z:
            for ext in (".shp", ".shx", ".dbf", ".prj"):
                z.write(base + ext, "water_empty_3857" + ext)
        for ext in (".shp", ".shx", ".dbf", ".prj"):
            os.remove(base + ext)
        return wp
    except Exception as e:
        return None


def build_one(pid, name, bbox):
    """کاشی + گراف + اندیس + بسته نهایی برای یک ولایت"""
    pbf = os.path.join(WORK, f"{pid}.pbf")
    if not os.path.isfile(pbf):
        log(f"هشدار: {pid}.pbf نیست؛ رد شد")
        return False
    bstr = ",".join(str(x) for x in bbox)

    # ۱) کاشی‌های برداری با planetiler — منابع کش‌شده اگر باشند، وگرنه دانلود
    t0 = time.time()
    pmt = os.path.join(WORK, f"{pid}.pmtiles")
    env = dict(os.environ)
    cache = os.path.join(ROOT, "work", "data", "sources")
    pcargs = []
    need_dl = False
    ne = os.path.join(cache, "natural_earth_vector.sqlite.zip")
    lc = os.path.join(cache, "lake_centerline.shp.zip")
    if os.path.isfile(ne):
        pcargs.append(f"--natural_earth_path={ne}")
    else:
        need_dl = True
    if os.path.isfile(lc):
        pcargs.append(f"--lake_centerline_path={lc}")
    else:
        need_dl = True
    wp = ensure_water_source(cache)
    if wp:
        pcargs.append(f"--water_polygons_path={wp}")
    else:
        need_dl = True
    if need_dl:
        pcargs.append("--download")
    r = subprocess.run(
        ["java", "-Xmx1350m", "-jar", PLANETILER,
         "--area=" + pid, f"--osm_path={pbf}", f"--bounds={bstr}",
         f"--output={pmt}", "--force"] + pcargs,
        cwd=WORK, env=env, capture_output=True, text=True)
    if r.returncode != 0 or not os.path.isfile(pmt):
        log(f"خطای planetiler برای {pid}: {r.stdout[-1500:]} {r.stderr[-1500:]}")
        return False
    log(f"کاشی {pid}: {os.path.getsize(pmt)/1e6:.1f}MB ({time.time()-t0:.0f} ثانیه)")

    # ۲) گراف + اندیس + بسته
    t0 = time.time()
    r = subprocess.run(
        [sys.executable, os.path.join(PROJ, "tools", "build_package.py"),
         "--name", pid, "--region", name, "--pbf", pbf, "--bbox", bstr,
         "--out", DIST, "--work", WORK, "--tiles", pmt, "--skip-fonts"],
        cwd=PROJ, capture_output=True, text=True)
    if r.returncode != 0:
        log(f"خطای build_package برای {pid}: {r.stdout[-1500:]} {r.stderr[-1500:]}")
        return False
    tail = [l for l in r.stdout.strip().splitlines() if l][-3:]
    for l in tail:
        log(f"  {pid}: {l}")

    # ۳) اعتبارسنجی
    zips = [f for f in os.listdir(DIST) if f.startswith(pid + "-") and f.endswith(".rahpkg.zip")]
    if not zips:
        log(f"خطا: بسته {pid} ساخته نشد")
        return False
    zpath = os.path.join(DIST, zips[0])
    r = subprocess.run(
        [sys.executable, os.path.join(PROJ, "tools", "validate_package.py"), zpath],
        cwd=PROJ, capture_output=True, text=True)
    log(f"اعتبارسنجی {pid}: {r.stdout.strip().splitlines()[-1] if r.stdout.strip() else r.returncode}")
    if r.returncode != 0:
        log(f"خطای اعتبارسنجی {pid}:\n{r.stdout[-800:]}")
        return False

    # ۴) پاک‌سازی فایل‌های میانی برای حفظ فضای دیسک
    for f in (pbf, pmt, os.path.join(DIST, f"{pid}.rafgraph"), os.path.join(DIST, f"{pid}.rafidx")):
        if os.path.isfile(f):
            os.remove(f)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--split-group", type=int, default=None,
                    help="تقسیم فقط این گروه (۰ تا ۴) — قابل ازسرگیری")
    ap.add_argument("--shard", type=int, default=None,
                    help="تقسیم گروه N و سپس ساخت همه ولایت‌های همان گروه (برای CI موازی)")
    ap.add_argument("--build", default="", help="ساخت بسته برای شناسه‌های جدا با کاما")
    ap.add_argument("--all-pbf-groups", action="store_true",
                    help="تقسیم همه گروه‌ها به‌ترتیب (برای اجرای طولانی)")
    ap.add_argument("--status", action="store_true", help="نمایش وضعیت بسته‌ها و PBFها")
    args = ap.parse_args()

    os.makedirs(DIST, exist_ok=True)
    if not os.path.isfile(PBF):
        log(f"خطا: PBF کشور نیست: {PBF}")
        sys.exit(1)

    if args.status:
        pbf_ok = [p[0] for p in PROVINCES if os.path.isfile(os.path.join(WORK, f"{p[0]}.pbf"))]
        zips = sorted(f for f in os.listdir(DIST) if f.endswith(".rahpkg.zip"))
        print(f"PBF آماده: {len(pbf_ok)}/34 → {','.join(pbf_ok)}")
        print(f"بسته‌ها: {len(zips)} → {','.join(z.split('-')[0] for z in zips)}")
        return

    if args.split_group is not None:
        split_group(args.split_group)
        return

    if args.shard is not None:
        split_group(args.shard)
        want = ",".join(p[0] for p in province_groups()[args.shard])
        # ادامه با همان مسیر ساخت
        args.build = want

    if args.all_pbf_groups:
        for gi in range(len(province_groups())):
            split_group(gi)
        return

    if args.build:
        want = [w.strip() for w in args.build.split(",") if w.strip()]
        by_id = {p[0]: p for p in PROVINCES}
        ok, failed = [], []
        for wid in want:
            if wid not in by_id:
                log(f"شناسه ناشناخته: {wid}")
                failed.append(wid)
                continue
            pid, name, bbox = by_id[wid]
            zips = [f for f in os.listdir(DIST) if f.startswith(pid + "-") and f.endswith(".rahpkg.zip")]
            if zips:
                log(f"پرش از {pid} (بسته موجود: {zips[0]})")
                ok.append(pid)
                continue
            if not os.path.isfile(os.path.join(WORK, f"{pid}.pbf")):
                log(f"خطا: {pid}.pbf نیست؛ اول تقسیم را اجرا کنید")
                failed.append(pid)
                continue
            log(f"=== ساخت {name} ({pid}) ===")
            if build_one(pid, name, bbox):
                ok.append(pid)
            else:
                failed.append(pid)
            df = shutil.disk_usage(ROOT)
            log(f"فضای آزاد: {df.free/1e9:.2f}GB")
        log(f"تمام شد. موفق: {len(ok)}، ناموفق: {len(failed)} {failed if failed else ''}")
        sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
