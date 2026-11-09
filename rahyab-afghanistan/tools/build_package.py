#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ابزار ساخت بسته آفلاین «راه‌یاب افغانستان».

خروجی‌ها (برای هر منطقه):
  <name>.rafgraph  — گراف مسیریابی دودویی (قالب RAFG1 — هماهنگ با OfflineRouter.kt)
  <name>.rafidx    — اندیس جست‌وجوی دودویی (قالب RAFI1 — هماهنگ با OfflineGeocoder.kt)
  <name>.pmtiles   — کاشی‌های برداری (با planetiler، اختیاری)
  style.json       — سبک نقشه آفلاین (با جای‌نگهدار __PMTILES__ و __FONTS__)
  fonts/           — فونت‌های گلیف (Noto Sans از openmaptiles — SIL OFL)
  pkgmanifest.json — شناسنامه بسته (نسخه، تاریخ، فایل‌ها، checksum)
  <name>-<date>.rahpkg.zip — بسته نهایی برای انتشار در GitHub Releases

نکات مهم:
  - نرمال‌سازی متن دقیقاً مطابق TextNorm.kt برنامه است.
  - پیمایش راه‌ها: هر پاره‌راه یک رکورد «دوسویهِ منطقی» با پرچم‌های دسترسی است؛
    در write_graph به یال‌های جهت‌دار باز می‌شود (رعایت oneway فقط برای موتر،
    پیاده همیشه دوسویه، دوربرگردان به‌صورت یک‌طرفه).
  - مجوز داده: © OpenStreetMap contributors — ODbL (الزامات انتساب در NOTICE.md)
"""
import argparse
import hashlib
import json
import math
import os
import shutil
import struct
import sys
import time
import urllib.request
import zipfile
from array import array
from datetime import datetime, timezone

try:
    import numpy as np
except ImportError:
    np = None
    print("هشدار: numpy نصب نیست؛ برای عملکرد بهتر نصب کنید: pip install numpy")

try:
    import osmium
except ImportError:
    print("خطا: pyosmium لازم است: pip install osmium")
    sys.exit(1)

BUILDER_VERSION = "1.1.0"

# ============================================================
# نرمال‌سازی متن — آینه دقیق TextNorm.kt
# ============================================================
_CHAR_MAP = {
    0x064A: 0x06CC,  # ي → ی
    0x0643: 0x06A9,  # ك → ک
    0x0629: 0x0647,  # ة → ه
    0x0623: 0x0627, 0x0625: 0x0627, 0x0671: 0x0627, 0x0622: 0x0627,
    0x0672: 0x0627, 0x0673: 0x0627,
    0x0624: 0x0648,  # ؤ → و
    0x0626: 0x06CC,  # ئ → ی
    0x06C0: 0x0647, 0x06C2: 0x0647, 0x06C1: 0x0647, 0x06BE: 0x0647,
    0x06BA: 0x0646,  # ں → ن
}


def _dropped(cp: int) -> bool:
    return (0x0610 <= cp <= 0x061A) or (0x064B <= cp <= 0x065F) or cp == 0x0670 \
        or (0x06D6 <= cp <= 0x06ED) or cp == 0x0640 or (0x200B <= cp <= 0x200F) \
        or cp == 0x00AD or cp == 0xFEFF


def norm_text(s: str) -> str:
    if not s:
        return ""
    out = []
    for ch in s:
        cp = ord(ch)
        if _dropped(cp):
            continue
        if cp in (0x200C, 0x200D):
            out.append(" ")
            continue
        cp = _CHAR_MAP.get(cp, cp)
        if 0x06F0 <= cp <= 0x06F9:
            cp = cp - 0x06F0 + 48
        if 0x0660 <= cp <= 0x0669:
            cp = cp - 0x0660 + 48
        if 65 <= cp <= 90:
            cp += 32
        if chr(cp).isalnum() or cp == 32:
            out.append(chr(cp))
        else:
            out.append(" ")
    return " ".join("".join(out).split())


# ============================================================
# پروفایل راه‌ها
# ============================================================
# highway → (سرعت kmh برای موتر، قابل موتر، قابل پیاده)
# نکته: پیاده روی کنار جاده اصلی/فرعی در افغانستان عادی است؛ فقط موتربان (motorway)
# و مسیرهای ممنوع مطلقاً برای پیاده بسته‌اند تا شبکه پیاده تکه‌تکه نشود.
HIGHWAY_PROFILE = {
    "motorway": (80, True, False),
    "motorway_link": (60, True, False),
    "trunk": (70, True, True),
    "trunk_link": (55, True, True),
    "primary": (60, True, True),
    "primary_link": (50, True, True),
    "secondary": (50, True, True),
    "secondary_link": (45, True, True),
    "tertiary": (40, True, True),
    "tertiary_link": (35, True, True),
    "unclassified": (35, True, True),
    "residential": (30, True, True),
    "living_street": (15, True, True),
    "service": (20, True, True),
    "track": (15, True, True),
    "road": (25, True, True),
    "pedestrian": (0, False, True),
    "footway": (0, False, True),
    "path": (0, False, True),
    "steps": (0, False, True),
    "bridleway": (0, False, True),
}

STREET_RANK = {
    "motorway": 80, "trunk": 75, "primary": 70, "secondary": 65, "tertiary": 60,
    "motorway_link": 78, "trunk_link": 73, "primary_link": 68, "secondary_link": 63,
    "tertiary_link": 58,
    "unclassified": 52, "residential": 50, "living_street": 45, "service": 35,
    "track": 30, "pedestrian": 40, "footway": 30, "path": 28, "road": 45,
}

# کمینه رتبه راه برای «بسته پایه کشوری» — موتربان، اصلی و فرعی (بدون خیابان‌های محلی)
BASE_MIN_RANK = 63

PLACE_KIND = {"city": 0, "town": 1, "village": 2, "hamlet": 3, "suburb": 4, "neighbourhood": 5}
PLACE_BASE_RANK = {0: 100, 1: 85, 2: 70, 3: 55, 4: 65, 5: 60}

POI_AMENITY = {
    "hospital": 55, "clinic": 40, "doctors": 30, "pharmacy": 35,
    "school": 38, "college": 40, "university": 45, "kindergarten": 28,
    "mosque": 50, "shrine": 40, "church": 38, "temple": 38,
    "police": 42, "fire_station": 38,
    "bank": 38, "fuel": 45, "marketplace": 48, "bus_station": 45,
    "taxi": 30, "post_office": 35, "library": 32, "townhall": 38,
    "stadium": 40,
}
POI_SHOP = {"supermarket": 40, "mall": 42, "bakery": 25, "butcher": 22, "car_repair": 20,
            "car": 25, "convenience": 25, "clothes": 22, "hardware": 22, "tailor": 20}
POI_TOURISM = {"hotel": 40, "guest_house": 35, "museum": 38, "attraction": 40,
               "viewpoint": 28, "zoo": 30}

# دسته‌بندی مکان‌ها (POI) — بایت cat در اندیس RAFI1 نسخه ۲.
# به برنامه امکان می‌دهد جست‌وجو بر اساس دسته (مسجد، شفاخانه، دواخانه و…) انجام دهد.
CAT_NONE = 0
CAT_RELIGION = 1      # مسجد، زیارت، معبد
CAT_HEALTH = 2        # شفاخانه، کلینیک، داکتر
CAT_PHARMACY = 3      # دواخانه
CAT_EDUCATION = 4     # مکتب، پوهنتون، کتابخانه
CAT_FUEL = 5          # پمپ‌بنزین
CAT_BANK = 6          # بانک، ATM
CAT_MARKET = 7        # بازار، دوکان، سوپرمارکت
CAT_FOOD = 8          # رستورانت، تیه‌خانه، کافه
CAT_HOTEL = 9         # هوتل، مهمان‌خانه
CAT_PARK = 10         # پارک، باغ، ساحه سبز
CAT_SERVICES = 11     # پولیس، آتش‌نشانی
CAT_TRANSPORT = 12    # ایستگاه، ترمینال، میدان هوایی
CAT_GOV = 13          # اداره، شهرداری، پست
CAT_PARKING = 14      # پارکینگ
CAT_CULTURE = 15      # موزیم، اثر تاریخی، ورزشی

AMENITY_CAT = {
    "place_of_worship": CAT_RELIGION, "mosque": CAT_RELIGION, "shrine": CAT_RELIGION,
    "church": CAT_RELIGION, "temple": CAT_RELIGION,
    "hospital": CAT_HEALTH, "clinic": CAT_HEALTH, "doctors": CAT_HEALTH, "dentist": CAT_HEALTH,
    "pharmacy": CAT_PHARMACY,
    "school": CAT_EDUCATION, "kindergarten": CAT_EDUCATION, "college": CAT_EDUCATION,
    "university": CAT_EDUCATION, "library": CAT_EDUCATION,
    "fuel": CAT_FUEL,
    "bank": CAT_BANK, "atm": CAT_BANK,
    "marketplace": CAT_MARKET,
    "restaurant": CAT_FOOD, "cafe": CAT_FOOD, "fast_food": CAT_FOOD, "food_court": CAT_FOOD,
    "hotel": CAT_HOTEL,
    "police": CAT_SERVICES, "fire_station": CAT_SERVICES,
    "bus_station": CAT_TRANSPORT, "taxi": CAT_TRANSPORT, "ferry_terminal": CAT_TRANSPORT,
    "townhall": CAT_GOV, "post_office": CAT_GOV, "courthouse": CAT_GOV, "public_building": CAT_GOV,
    "parking": CAT_PARKING,
}
SHOP_CAT = CAT_MARKET
TOURISM_CAT = {"hotel": CAT_HOTEL, "guest_house": CAT_HOTEL, "hostel": CAT_HOTEL, "motel": CAT_HOTEL,
               "museum": CAT_CULTURE, "attraction": CAT_CULTURE, "viewpoint": CAT_CULTURE,
               "gallery": CAT_CULTURE, "zoo": CAT_CULTURE}
LEISURE_CAT = {"park": CAT_PARK, "garden": CAT_PARK, "playground": CAT_PARK,
               "stadium": CAT_CULTURE, "sports_centre": CAT_CULTURE}

NAME_TAGS = ["name", "name:fa", "name:ps", "name:en", "alt_name", "old_name",
             "int_name", "official_name", "short_name", "loc_name"]

# پرچم‌های پاره‌راه منطقی
F_CAR = 1
F_FOOT = 2
F_ROUND = 4
F_CAR_FWD_ONLY = 8    # oneway مثبت یا دوربرگردان
F_CAR_BWD_ONLY = 16   # oneway=-1


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


# ============================================================
# ساخت گراف و اندیس
# ============================================================
class Builder:
    def __init__(self, name, bbox, work_dir, min_rank=0):
        self.name = name
        self.w, self.s, self.e, self.n = bbox  # W,S,E,N
        self.work = work_dir
        # اگر > ۰ باشد فقط راه‌های با رتبه ≥ این مقدار در گراف و اندیس می‌آیند
        # (برای بسته پایه کشوری — راه‌های اصلی بین‌ولایتی)
        self.min_rank = min_rank
        os.makedirs(work_dir, exist_ok=True)

        self.node_ids = array("q")
        self.node_lat = array("i")
        self.node_lon = array("i")
        self.ids_np = None
        self.lat_np = None
        self.lon_np = None

        self.used = {}       # (lat7,lon7) → idx
        self.nodes_x = []    # [(lat7, lon7)]
        # یال‌ها در آرایه‌های موازی نگه‌داری می‌شوند (نه تاپل) تا حافظه برای گراف کشوری
        # با میلیون‌ها یال قابل مدیریت بماند
        self.e_u = array("I")      # گره مبدأ
        self.e_v = array("I")      # گره مقصد
        self.e_len = array("H")    # طول متر
        self.e_speed = array("B")  # سرعت kmh
        self.e_flags = array("B")  # پرچم‌ها
        self.e_name_off = array("I")
        self.e_name_len = array("B")
        self.names_blob = bytearray()
        self.names_map = {}  # bytes → (off, len)
        self.records = []    # (name, lat7, lon7, kind, rank)

    def in_bbox(self, lat, lon):
        return self.s <= lat <= self.n and self.w <= lon <= self.e

    # ---------- گذر اول: گره‌ها ----------
    def collect_nodes(self, pbf):
        t0 = time.time()
        stats = {"total": 0, "inside": 0}

        class H(osmium.SimpleHandler):
            def node(hh, nd):
                stats["total"] += 1
                if stats["total"] % 5_000_000 == 0:
                    print(f"  … {stats['total']:,} گره خوانده شد، {stats['inside']:,} داخل محدوده")
                lat = nd.location.lat
                lon = nd.location.lon
                if self.in_bbox(lat, lon):
                    self.node_ids.append(nd.id)
                    self.node_lat.append(int(round(lat * 1e7)))
                    self.node_lon.append(int(round(lon * 1e7)))
                    stats["inside"] += 1

        H().apply_file(pbf)
        print(f"گذر اول: {stats['total']:,} گره، {stats['inside']:,} داخل محدوده ({time.time()-t0:.0f} ثانیه)")

        if np is not None:
            order = np.argsort(np.frombuffer(self.node_ids, dtype=np.int64))
            self.ids_np = np.frombuffer(self.node_ids, dtype=np.int64)[order].copy()
            self.lat_np = np.frombuffer(self.node_lat, dtype=np.int32)[order].copy()
            self.lon_np = np.frombuffer(self.node_lon, dtype=np.int32)[order].copy()
        else:
            triples = sorted(zip(self.node_ids, self.node_lat, self.node_lon))
            self.ids_np, self.lat_np, self.lon_np = (np.array(x) for x in zip(*triples)) if triples else (np.array([]), np.array([]), np.array([]))
        # حافظه خام آزاد شود
        del self.node_ids, self.node_lat, self.node_lon

    def loc_of(self, node_id):
        pos = np.searchsorted(self.ids_np, node_id)
        if pos >= len(self.ids_np) or self.ids_np[pos] != node_id:
            return None
        return int(self.lat_np[pos]), int(self.lon_np[pos])

    # ---------- نام‌ها ----------
    def name_id(self, name):
        if not name:
            return 0xFFFFFFFF, 0
        b = name.encode("utf-8")
        hit = self.names_map.get(b)
        if hit is not None:
            return hit
        off = len(self.names_blob)
        self.names_blob.extend(b)
        val = (off, len(b))
        self.names_map[b] = val
        return val

    # ---------- گذر دوم: راه‌ها، مکان‌ها، ساحه‌ها ----------
    def collect_ways(self, pbf):
        t0 = time.time()
        stats = {"ways": 0, "kept": 0, "cut": 0, "street_recs": 0, "place_recs": 0, "poi_recs": 0}
        used = self.used
        nodes_x = self.nodes_x
        e_u, e_v, e_len, e_speed, e_flags = self.e_u, self.e_v, self.e_len, self.e_speed, self.e_flags
        e_name_off, e_name_len = self.e_name_off, self.e_name_len
        name_id_fn = self.name_id
        records = self.records

        class H(osmium.SimpleHandler):
            def way(hh, w):
                stats["ways"] += 1
                if stats["ways"] % 1_000_000 == 0:
                    print(f"  … {stats['ways']:,} راه خوانده شد")
                tags = w.tags
                hw = tags.get("highway")
                if not hw or hw not in HIGHWAY_PROFILE:
                    return
                speed, car_ok, foot_ok = HIGHWAY_PROFILE[hw]
                # فیلتر بسته پایه: فقط راه‌های اصلی (بین‌ولایتی)
                if self.min_rank > 0 and STREET_RANK.get(hw, 40) < self.min_rank:
                    return

                acc = tags.get("access")
                if acc in ("no", "private"):
                    car_ok = False
                    foot_ok = tags.get("foot") in ("yes", "designated")
                if tags.get("motor_vehicle") in ("no", "private"):
                    car_ok = False
                if tags.get("vehicle") in ("no", "private"):
                    car_ok = False
                if tags.get("foot") in ("no", "private"):
                    foot_ok = False
                if not car_ok and not foot_ok:
                    return

                is_round = tags.get("junction") == "roundabout" or tags.get("roundabout") == "yes"
                oneway = tags.get("oneway")
                fwd_only = car_ok and (oneway in ("yes", "1", "true") or is_round)
                bwd_only = car_ok and oneway in ("-1", "reverse")

                refs = [nd.ref for nd in w.nodes]
                if len(refs) < 2:
                    return
                pts = []
                ok = True
                for r in refs:
                    p = self.loc_of(r)
                    if p is None:
                        ok = False
                        break
                    pts.append(p)
                if not ok or len(pts) < 2:
                    stats["cut"] += 1
                    return

                primary_name = tags.get("name", "") or tags.get("name:fa") or tags.get("name:ps") or ""

                idxs = []
                for (la, lo) in pts:
                    key = (la, lo)
                    ix = used.get(key)
                    if ix is None:
                        ix = len(nodes_x)
                        used[key] = ix
                        nodes_x.append(key)
                    idxs.append(ix)

                seg_flags = 0
                if car_ok:
                    seg_flags |= F_CAR
                if foot_ok:
                    seg_flags |= F_FOOT
                if is_round:
                    seg_flags |= F_ROUND
                if fwd_only:
                    seg_flags |= F_CAR_FWD_ONLY
                if bwd_only:
                    seg_flags |= F_CAR_BWD_ONLY

                coslat = math.cos(math.radians((pts[0][0] + pts[-1][0]) / 2 / 1e7))
                nid_off, nid_len = name_id_fn(primary_name)
                for i in range(len(idxs) - 1):
                    la1, lo1 = pts[i]
                    la2, lo2 = pts[i + 1]
                    dy = (la2 - la1) / 1e7 * 110540.0
                    dx = (lo2 - lo1) / 1e7 * 111320.0 * coslat
                    length = int(min(65535, max(1, round(math.hypot(dx, dy)))))
                    e_u.append(idxs[i])
                    e_v.append(idxs[i + 1])
                    e_len.append(length)
                    e_speed.append(speed)
                    e_flags.append(seg_flags)
                    e_name_off.append(nid_off)
                    e_name_len.append(nid_len)
                stats["kept"] += 1

                mid = pts[len(pts) // 2]
                if primary_name:
                    rank = STREET_RANK.get(hw, 40)
                    for tagname in NAME_TAGS:
                        val = tags.get(tagname)
                        if not val:
                            continue
                        kind = 6 if (tagname == "name" or val == primary_name) else (6 | 128)
                        records.append((val, mid[0], mid[1], kind, rank, CAT_NONE))
                        stats["street_recs"] += 1

            def node(hh, nd):
                tags = nd.tags
                if not tags:
                    return
                lat = nd.location.lat
                lon = nd.location.lon
                if not self.in_bbox(lat, lon):
                    return
                la7, lo7 = int(round(lat * 1e7)), int(round(lon * 1e7))
                place = tags.get("place")
                if place in PLACE_KIND:
                    kind = PLACE_KIND[place]
                    name = tags.get("name") or tags.get("name:fa") or tags.get("name:ps") or ""
                    if name:
                        pt = tags.get("population", "")
                        digits = "".join(ch for ch in pt if ch.isdigit())
                        pop = int(digits) if digits else 0
                        rank = PLACE_BASE_RANK[kind]
                        if pop > 0:
                            rank = min(100, rank + 5 + int(math.log10(pop)))
                        for tagname in NAME_TAGS:
                            val = tags.get(tagname)
                            if not val:
                                continue
                            k = kind if (tagname == "name" or val == name) else (kind | 128)
                            records.append((val, la7, lo7, k, rank, CAT_NONE))
                            stats["place_recs"] += 1
                    return
                # ---- مکان‌های عمومی (POI) با دسته‌بندی برای جست‌وجوی دسته‌ای ----
                kind_rank = None
                cat = CAT_NONE
                am = tags.get("amenity")
                shop = tags.get("shop")
                tourism = tags.get("tourism")
                le = tags.get("leisure")
                if am and am in POI_AMENITY:
                    kind_rank = POI_AMENITY[am]
                elif shop and shop in POI_SHOP:
                    kind_rank = POI_SHOP[shop]
                elif tourism and tourism in POI_TOURISM:
                    kind_rank = POI_TOURISM[tourism]
                elif le and le in LEISURE_CAT:
                    kind_rank = 30 if le in ("park", "garden") else 35
                elif shop:
                    # هر دوکان نام‌دار دیگر (در حد داده موجود) هم ثبت می‌شود
                    kind_rank = 25
                # تعیین دسته (مستقل از رتبه)
                if am and am in AMENITY_CAT:
                    cat = AMENITY_CAT[am]
                elif shop:
                    cat = SHOP_CAT
                elif tourism and tourism in TOURISM_CAT:
                    cat = TOURISM_CAT[tourism]
                elif le and le in LEISURE_CAT:
                    cat = LEISURE_CAT[le]
                elif tags.get("aeroway") == "aerodrome":
                    cat = CAT_TRANSPORT
                    if kind_rank is None:
                        kind_rank = 55
                if kind_rank is not None or cat:
                    name = tags.get("name") or tags.get("name:fa") or tags.get("name:ps") or ""
                    if name:
                        for tagname in NAME_TAGS:
                            val = tags.get(tagname)
                            if not val:
                                continue
                            k = 7 if (tagname == "name" or val == name) else (7 | 128)
                            records.append((val, la7, lo7, k, kind_rank or 25, cat))
                            stats["poi_recs"] += 1

        H().apply_file(pbf)
        print(f"گذر دوم: راه‌ها {stats['ways']:,} (حفظ {stats['kept']:,}، بریده {stats['cut']:,})، "
              f"رکوردها: خیابان {stats['street_recs']:,}، مکان {stats['place_recs']:,}، ساحه {stats['poi_recs']:,} "
              f"({time.time()-t0:.0f} ثانیه)")

    # ---------- نوشتن گراف ----------
    def write_graph(self, path):
        """نوشتن گراف دودویی با numpy — بهینه برای گراف کشوری با میلیون‌ها یال."""
        t0 = time.time()
        if np is None:
            print("خطا: numpy لازم است (pip install numpy)")
            sys.exit(1)

        n_raw = len(self.e_u)
        u = np.frombuffer(self.e_u, dtype=np.uint32).astype(np.int64)
        v = np.frombuffer(self.e_v, dtype=np.uint32).astype(np.int64)
        length = np.frombuffer(self.e_len, dtype=np.uint16)
        speed = np.frombuffer(self.e_speed, dtype=np.uint8)
        flags = np.frombuffer(self.e_flags, dtype=np.uint8)
        noff = np.frombuffer(self.e_name_off, dtype=np.uint32)
        nlen = np.frombuffer(self.e_name_len, dtype=np.uint8)
        del self.e_u, self.e_v, self.e_len, self.e_speed, self.e_flags, self.e_name_off, self.e_name_len

        # بازشماری فشرده گره‌ها (فقط گره‌های مصرف‌شده، مرتب)
        touched = np.unique(np.concatenate([u, v]))
        nu = np.searchsorted(touched, u)
        nv = np.searchsorted(touched, v)
        del u, v
        n_nodes = touched.size

        lat_np = np.asarray(self.nodes_x, dtype=np.int64)[:, 0] if self.nodes_x else np.empty(0, dtype=np.int64)
        lon_np = np.asarray(self.nodes_x, dtype=np.int64)[:, 1] if self.nodes_x else np.empty(0, dtype=np.int64)
        final_lat = lat_np[touched].astype(np.int32)
        final_lon = lon_np[touched].astype(np.int32)
        del lat_np, lon_np, touched

        # ساخت یال‌های جهت‌دار با ماسک‌های برداری
        f_car = (flags & F_CAR) != 0
        f_foot = (flags & F_FOOT) != 0
        f_round = flags & F_ROUND
        f_fwd = (flags & F_CAR_FWD_ONLY) != 0
        f_bwd = (flags & F_CAR_BWD_ONLY) != 0

        src_parts, dst_parts, len_parts, spd_parts, flg_parts, off_parts, ln_parts = [], [], [], [], [], [], []

        car_fwd = f_car & ~f_bwd          # موتر رو به جلو
        src_parts.append(nu[car_fwd]); dst_parts.append(nv[car_fwd])
        len_parts.append(length[car_fwd]); spd_parts.append(speed[car_fwd])
        flg_parts.append(np.bitwise_or(np.ones(int(car_fwd.sum()), dtype=np.uint8), f_round[car_fwd]) if car_fwd.any() else np.empty(0, np.uint8))
        off_parts.append(noff[car_fwd]); ln_parts.append(nlen[car_fwd])

        car_bwd = f_car & ~f_fwd          # موتر رو به عقب (یال معکوس)
        src_parts.append(nv[car_bwd]); dst_parts.append(nu[car_bwd])
        len_parts.append(length[car_bwd]); spd_parts.append(speed[car_bwd])
        flg_parts.append(np.bitwise_or(np.ones(int(car_bwd.sum()), dtype=np.uint8), f_round[car_bwd]) if car_bwd.any() else np.empty(0, np.uint8))
        off_parts.append(noff[car_bwd]); ln_parts.append(nlen[car_bwd])

        foot_any = f_foot                  # پیاده همیشه دوسویه
        src_parts.append(nu[foot_any]); dst_parts.append(nv[foot_any])
        len_parts.append(length[foot_any]); spd_parts.append(np.zeros(int(foot_any.sum()), dtype=np.uint8))
        flg_parts.append(np.full(int(foot_any.sum()), F_FOOT, dtype=np.uint8) | f_round[foot_any])
        off_parts.append(noff[foot_any]); ln_parts.append(nlen[foot_any])

        src_parts.append(nv[foot_any]); dst_parts.append(nu[foot_any])
        len_parts.append(length[foot_any]); spd_parts.append(np.zeros(int(foot_any.sum()), dtype=np.uint8))
        flg_parts.append(np.full(int(foot_any.sum()), F_FOOT, dtype=np.uint8) | f_round[foot_any])
        off_parts.append(noff[foot_any]); ln_parts.append(nlen[foot_any])
        del nu, nv, length, speed, flags, noff, nlen, f_car, f_foot, f_round, f_fwd, f_bwd, car_fwd, car_bwd

        src = np.concatenate(src_parts)
        dst = np.concatenate(dst_parts)
        elen = np.concatenate(len_parts)
        espd = np.concatenate(spd_parts)
        eflg = np.concatenate(flg_parts)
        eoff = np.concatenate(off_parts)
        eln = np.concatenate(ln_parts)
        del src_parts, dst_parts, len_parts, spd_parts, flg_parts, off_parts, ln_parts

        # مرتب‌سازی بر اساس گره مبدأ (CSR) — پایدار برای قطعیت خروجی
        order = np.argsort(src, kind="stable")
        dst = dst[order]; elen = elen[order]; espd = espd[order]
        eflg = eflg[order]; eoff = eoff[order]; eln = eln[order]
        csr_src = src[order]
        del src, order
        n_edges = dst.size

        csr = np.zeros(n_nodes + 1, dtype=np.uint32)
        counts = np.bincount(csr_src, minlength=n_nodes).astype(np.uint32)
        del csr_src
        csr[1:] = np.cumsum(counts)

        names_blob = bytes(self.names_blob)
        with open(path, "wb") as f:
            f.write(b"RAFG1\x00")
            f.write(struct.pack("<i", 1))
            f.write(struct.pack("<i", n_nodes))
            f.write(struct.pack("<i", n_edges))
            f.write(struct.pack("<i", len(names_blob)))
            f.write(b"\x00" * 16)
            f.write(final_lat.tobytes())
            f.write(final_lon.tobytes())
            f.write(csr.tobytes())
            f.write(dst.astype(np.uint32).tobytes())
            f.write(elen.astype(np.uint16).tobytes())
            f.write(espd.tobytes())
            f.write(eflg.tobytes())
            f.write(eoff.astype(np.uint32).tobytes())
            f.write(eln.tobytes())
            f.write(names_blob)
        print(f"گراف: {n_nodes:,} گره، {n_edges:,} یال جهت‌دار، {os.path.getsize(path)/1e6:.1f}MB ({time.time()-t0:.0f} ثانیه)")
        return n_nodes, n_edges

    # ---------- نوشتن اندیس ----------
    def write_index(self, path):
        t0 = time.time()
        seen = set()
        recs = []
        for (name, la, lo, kind, rank, cat) in self.records:
            key = (name, la, lo, kind)
            if key in seen:
                continue
            seen.add(key)
            recs.append((name, la, lo, kind, rank, cat))
        recs.sort(key=lambda x: x[0])

        name_blob = bytearray()
        norm_blob = bytearray()
        packed = []
        name_map = {}
        norm_map = {}
        for (name, la, lo, kind, rank, cat) in recs:
            b = name.encode("utf-8")
            if b not in name_map:
                name_map[b] = (len(name_blob), len(b))
                name_blob.extend(b)
            if b not in norm_map:
                nb = norm_text(name).encode("utf-8")
                norm_map[b] = (len(norm_blob), len(nb))
                norm_blob.extend(nb)
            noff, nlen = name_map[b]
            zoff, zlen = norm_map[b]
            # نسخه ۲: یک بایت دسته در پایان رکورد (۲۱ بایت)
            packed.append(struct.pack("<IBIBiiBBB", noff, nlen, zoff, zlen, la, lo, kind & 0xFF, rank & 0xFF, cat & 0xFF))

        with open(path, "wb") as f:
            f.write(b"RAFI1\x00")
            f.write(struct.pack("<i", 2))
            f.write(struct.pack("<i", len(packed)))
            f.write(struct.pack("<i", len(name_blob)))
            f.write(struct.pack("<i", len(norm_blob)))
            f.write(b"\x00" * 12)
            f.write(bytes(name_blob))
            f.write(bytes(norm_blob))
            for p in packed:
                f.write(p)
        print(f"اندیس: {len(packed):,} رکورد، {os.path.getsize(path)/1e6:.1f}MB ({time.time()-t0:.0f} ثانیه)")
        return len(packed)


# ============================================================
# فونت‌های گلیف (Noto Sans — SIL OFL)
# ============================================================
FONT_STACKS = ["Noto Sans Regular", "Noto Sans Bold"]
FONT_RANGES = [0, 256, 512, 768, 1024, 1280, 1536, 1792, 2048, 2304, 8192, 8448, 8704]


def fetch_fonts(dir_path):
    """
    فونت‌های گلیف Noto Sans (SIL OFL).
    منبع اصلی: fonts.protomaps.com (سرویس عمومی فونت MapLibre)؛
    جایگزین: demotiles.maplibre.org. پاسخ HTML هرگز به‌عنوان فونت ذخیره نمی‌شود.
    """
    os.makedirs(dir_path, exist_ok=True)
    sources = ["https://fonts.protomaps.com", "https://demotiles.maplibre.org/font"]
    for stack in FONT_STACKS:
        real = os.path.join(dir_path, stack)
        os.makedirs(real, exist_ok=True)
        for r in FONT_RANGES:
            out = os.path.join(real, f"{r}-{r+255}.pbf")
            if os.path.exists(out):
                continue
            ok = False
            for base in sources:
                url = f"{base}/{stack.replace(' ', '%20')}/{r}-{r+255}.pbf"
                try:
                    req = urllib.request.Request(url, headers={"User-Agent": "RahyabDataBuilder/1.1"})
                    with urllib.request.urlopen(req, timeout=30) as resp:
                        data = resp.read()
                    # اعتبارسنجی: گلیف PBF پروتوباف است (شروع 0x0A) نه HTML
                    if not data or data[0:1] != b"\x0a" or data[:9].lower().startswith(b"<!doctype") or b"<html" in data[:200].lower():
                        print(f"  فونت {stack} {r}-{r+255}: پاسخ غیر فونت (HTML?)")
                        continue
                    with open(out, "wb") as wf:
                        wf.write(data)
                    ok = True
                    break
                except Exception as e:
                    print(f"  فونت {stack} {r}-{r+255} از {base}: {e}")
            if not ok and os.path.exists(out):
                os.remove(out)


# ============================================================
# بسته‌بندی
# ============================================================
def make_package(pkg_dir, name, region, data_date, files, out_dir, bounds):
    manifest = {
        "schema": 1,
        "id": name,
        "name": region,
        "region": region,
        "dataDate": data_date,
        "builderVersion": BUILDER_VERSION,
        "bounds": {"west": bounds[0], "south": bounds[1], "east": bounds[2], "north": bounds[3]},
        "formats": {"graph": "RAFG1/1", "index": "RAFI1/1", "tiles": "openmaptiles pmtiles"},
        "license": "© OpenStreetMap contributors — ODbL",
        "files": [],
    }
    total = 0
    for fn in files:
        p = os.path.join(pkg_dir, fn)
        sz = os.path.getsize(p)
        total += sz
        manifest["files"].append({"name": fn, "sizeBytes": sz, "sha256": sha256_file(p)})
    with open(os.path.join(pkg_dir, "pkgmanifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    zip_name = f"{name}-{data_date}.rahpkg.zip"
    zip_path = os.path.join(out_dir, zip_name)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED) as z:
        for root, _, fns in os.walk(pkg_dir):
            for fn in sorted(fns):
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, pkg_dir)
                z.write(full, rel)
    return zip_path, total


def main():
    ap = argparse.ArgumentParser(description="ساخت بسته آفلاین راه‌یاب افغانستان")
    ap.add_argument("--name", required=True)
    ap.add_argument("--region", required=True)
    ap.add_argument("--pbf", required=True)
    ap.add_argument("--bbox", required=True, help="W,S,E,N")
    ap.add_argument("--out", default="dist")
    ap.add_argument("--work", default="build_work")
    ap.add_argument("--style-template", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "offline_style_template.json"))
    ap.add_argument("--tiles", default=None, help="pmtiles آماده برای افزودن به بسته")
    ap.add_argument("--extract-pbf", default=None, help="مسیر ذخیره استخراج pbf (برای planetiler)")
    ap.add_argument("--skip-fonts", action="store_true")
    ap.add_argument("--min-rank", type=int, default=0,
                    help="فقط راه‌های با رتبه ≥ این مقدار (بسته پایه کشوری؛ مثلاً ۶۳)")
    args = ap.parse_args()

    bbox = tuple(float(x) for x in args.bbox.split(","))
    assert len(bbox) == 4 and bbox[0] < bbox[2] and bbox[1] < bbox[3], "bbox نامعتبر: W,S,E,N"

    data_date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    os.makedirs(args.out, exist_ok=True)
    pkg_dir = os.path.join(args.work, f"pkg_{args.name}")
    os.makedirs(pkg_dir, exist_ok=True)

    # ۱) استخراج pbf برای planetiler
    if args.extract_pbf:
        t0 = time.time()
        print(f"استخراج pbf در محدوده {bbox} …")
        writer = osmium.SimpleWriter(args.extract_pbf)
        node_set = set()

        class NodePass(osmium.SimpleHandler):
            def node(hh, nd):
                if bbox[1] <= nd.location.lat <= bbox[3] and bbox[0] <= nd.location.lon <= bbox[2]:
                    writer.add_node(nd)
                    node_set.add(nd.id)

        NodePass().apply_file(args.pbf)

        class WayPass(osmium.SimpleHandler):
            def way(hh, wy):
                for nd in wy.nodes:
                    if nd.ref not in node_set:
                        return
                writer.add_way(wy)

        WayPass().apply_file(args.pbf)
        writer.close()
        print(f"استخراج ذخیره شد: {args.extract_pbf} ({os.path.getsize(args.extract_pbf)/1e6:.1f}MB، {time.time()-t0:.0f} ثانیه)")

    # ۲) گراف و اندیس
    b = Builder(args.name, bbox, args.work, min_rank=args.min_rank)
    b.collect_nodes(args.pbf)
    b.collect_ways(args.pbf)
    graph_path = os.path.join(pkg_dir, f"{args.name}.rafgraph")
    idx_path = os.path.join(pkg_dir, f"{args.name}.rafidx")
    b.write_graph(graph_path)
    b.write_index(idx_path)

    # ۳) کاشی‌ها + سبک + فونت
    files = [f"{args.name}.rafgraph", f"{args.name}.rafidx"]
    if args.tiles and os.path.isfile(args.tiles):
        shutil.copyfile(args.tiles, os.path.join(pkg_dir, f"{args.name}.pmtiles"))
        files.append(f"{args.name}.pmtiles")
        shutil.copyfile(args.style_template, os.path.join(pkg_dir, "style.json"))
        files.append("style.json")
        if not args.skip_fonts:
            print("دانلود فونت‌های گلیف…")
            fetch_fonts(os.path.join(pkg_dir, "fonts"))
            fonts_root = os.path.join(pkg_dir, "fonts")
            for root, _, fns in os.walk(fonts_root):
                for fn in fns:
                    rel = os.path.relpath(os.path.join(root, fn), pkg_dir)
                    files.append(rel)

    # ۴) بسته نهایی
    zip_path, total = make_package(pkg_dir, args.name, args.region, data_date, files, args.out,
                                   (bbox[0], bbox[1], bbox[2], bbox[3]))
    print(f"بسته ساخته شد: {zip_path} ({total/1e6:.1f}MB محتوا)")

    shutil.copyfile(graph_path, os.path.join(args.out, f"{args.name}.rafgraph"))
    shutil.copyfile(idx_path, os.path.join(args.out, f"{args.name}.rafidx"))
    print("تمام شد.")


if __name__ == "__main__":
    main()
