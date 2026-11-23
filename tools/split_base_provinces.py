#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""تفکیک بسته پایه کشوری به ۳۴ بسته ولایتی راه‌یاب.

ورودی: بسته پایه استخراج‌شده (afghanistan.rafgraph / .rafidx / .pmtiles / style.json)
خروجی: برای هر ولایت یک <id>-<date>.rahpkg.zip کامل شامل:
  <id>.rafgraph  — زیرگراف ولایت (فیلتر محدوده + بازسازی CSR و جدول نام‌ها)
  <id>.rafidx    — زیراندیس جست‌وجو (فیلتر رکوردها + بازسازی blobها)
  <id>.pmtiles   — زیرمجموعه کاشی‌های برداری (فیلتر z/x/y + بازسازی دایرکتوری)
  style.json     — همان سبک بسته پایه (جای‌نگهدارها در برنامه جایگزین می‌شوند)
  pkgmanifest.json — شناسنامه بسته با sha256 هر فایل

قالب‌ها طبق docs/DATA_FORMATS.md — همان قالبی که OfflineRouter.kt و
OfflineGeocoder.kt می‌خوانند (RAFG1/1 و RAFI1/2 و PMTiles v3).
"""
import gzip
import hashlib
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import zipfile

import numpy as np

BASE_DIR = "/home/z/my-project/work/v091/base_extract"
OUT_DIR = "/home/z/my-project/work/v091/dist_prov"
ASSETS_PKG = "/home/z/my-project/work/v091/rahyab-afghanistan/app/src/main/assets/packages"
VALIDATE = "/home/z/my-project/work/v091/rahyab-afghanistan/tools/validate_package.py"
DATA_DATE = "2026-11-10"
BUILDER = "1.2.0"

# (id, نام دری, W, S, E, N) — همان محدوده‌های با حاشیه امن build_all_provinces.py
PROVINCES = [
    ("kabul",         "کابل",        (68.80, 34.15, 69.70, 35.05)),
    ("herat",         "هرات",        (60.20, 32.85, 63.40, 34.95)),
    ("kandahar",      "قندهار",      (64.70, 29.95, 67.00, 32.15)),
    ("balkh",         "بلخ",         (65.60, 36.25, 67.60, 37.45)),
    ("nangarhar",     "ننگرهار",     (69.80, 33.65, 71.30, 35.05)),
    ("ghazni",        "غزنی",        (66.90, 32.25, 69.20, 34.15)),
    ("helmand",       "هلمند",       (61.70, 29.35, 65.30, 32.65)),
    ("bamyan",        "بامیان",      (66.20, 33.25, 68.30, 35.25)),
    ("kunduz",        "کندوز",       (68.30, 36.25, 69.30, 37.45)),
    ("takhar",        "تخار",        (69.30, 36.15, 71.00, 37.65)),
    ("badakhshan",    "بدخشان",      (70.25, 34.30, 75.15, 38.49)),
    ("baghlan",       "بغلان",       (67.60, 35.20, 69.45, 36.70)),
    ("parwan",        "پروان",       (68.40, 34.75, 69.60, 35.80)),
    ("logar",         "لوگر",        (68.50, 33.55, 69.50, 34.65)),
    ("maidan_wardak", "میدان وردک",  (67.30, 33.60, 69.00, 34.95)),
    ("kapisa",        "کاپیسا",      (69.20, 34.55, 70.00, 35.35)),
    ("panjshir",      "پنجشیر",      (68.90, 34.95, 69.90, 35.95)),
    ("laghman",       "لغمان",       (69.90, 34.35, 70.80, 35.05)),
    ("kunar",         "کنر",         (70.40, 34.35, 71.70, 35.60)),
    ("nuristan",      "نورستان",     (70.20, 34.55, 71.50, 35.95)),
    ("paktia",        "پکتیا",       (68.80, 33.25, 70.00, 34.15)),
    ("paktika",       "پکتیکا",      (67.70, 31.65, 69.40, 33.70)),
    ("khost",         "خوست",        (69.40, 33.05, 70.80, 34.10)),
    ("zabul",         "زابل",        (66.00, 30.75, 68.00, 32.55)),
    ("urozgan",       "ارزگان",      (65.20, 31.45, 67.20, 33.35)),
    ("farah",         "فراه",        (60.50, 31.85, 63.30, 33.75)),
    ("nimruz",        "نیمروز",      (60.70, 28.95, 63.50, 31.65)),
    ("badghis",       "بادغیس",      (61.90, 33.85, 64.10, 35.70)),
    ("ghor",          "غور",         (62.80, 32.85, 65.50, 35.05)),
    ("faryab",        "فاریاب",      (63.20, 35.35, 65.10, 37.25)),
    ("jowzjan",       "جوزجان",      (65.40, 36.45, 67.10, 37.55)),
    ("sar_e_pol",     "سرپل",        (65.10, 35.15, 66.70, 36.65)),
    ("samangan",      "سمنگان",      (65.90, 35.35, 67.70, 36.75)),
    ("daykundi",      "دایکندی",     (65.30, 33.35, 67.10, 34.80)),
]

# ---------- گراف RAFG1/1 ----------

def split_graph(data: bytes, bbox) -> bytes:
    w, s, e, n = (int(round(v * 1e7)) for v in bbox)
    magic = data[:6]
    ver, N, E, nameBlobSize = struct.unpack_from("<iiii", data, 6)
    assert magic == b"RAFG1\x00" and ver == 1, "قالب گراف ناشناخته"
    off = 38
    lat = np.frombuffer(data, dtype="<i4", count=N, offset=off); off += 4 * N
    lon = np.frombuffer(data, dtype="<i4", count=N, offset=off); off += 4 * N
    csr = np.frombuffer(data, dtype="<u4", count=N + 1, offset=off); off += 4 * (N + 1)
    to_node = np.frombuffer(data, dtype="<u4", count=E, offset=off); off += 4 * E
    len_m = np.frombuffer(data, dtype="<u2", count=E, offset=off); off += 2 * E
    speed = np.frombuffer(data, dtype=np.uint8, count=E, offset=off); off += E
    flags = np.frombuffer(data, dtype=np.uint8, count=E, offset=off); off += E
    name_off = np.frombuffer(data, dtype="<u4", count=E, offset=off); off += 4 * E
    name_len = np.frombuffer(data, dtype=np.uint8, count=E, offset=off); off += E
    names = data[off:off + nameBlobSize]

    keep = (lon >= w) & (lon <= e) & (lat >= s) & (lat <= n)
    keep_idx = np.nonzero(keep)[0]
    Nn = len(keep_idx)
    if Nn < 2:
        raise ValueError("زیرگراف خالی است")
    remap = np.full(N, 0xFFFFFFFF, dtype="<u4")
    remap[keep_idx] = np.arange(Nn, dtype="<u4")

    # مبدأ هر یال از CSR
    src = np.repeat(np.arange(N, dtype=np.int64), np.diff(csr.astype(np.int64)))
    ekeep = keep[src] & keep[to_node]
    ek = np.nonzero(ekeep)[0]
    En = len(ek)
    if En < 1:
        raise ValueError("یالی برای این ولایت نیست")
    new_src = remap[src[ek]].astype(np.int64)
    # CSR جدید (یال‌ها به‌ترتیب مبدأ قدیمی‌اند → مبدأ جدید صعودی غیرسخت‌گیرانه)
    counts = np.bincount(new_src, minlength=Nn).astype("<u4")
    new_csr = np.zeros(Nn + 1, dtype="<u4")
    np.cumsum(counts, out=new_csr[1:])

    # جدول نام‌ها: فقط نام‌های یال‌های نگه‌داشته، با نگاشت آفست
    blob = bytearray()
    off_map = {}
    new_name_off = np.zeros(En, dtype="<u4")
    new_name_len = np.zeros(En, dtype=np.uint8)
    for i, ei in enumerate(ek):
        o, l = int(name_off[ei]), int(name_len[ei])
        key = (o, l)
        if key in off_map:
            new_name_off[i], new_name_len[i] = off_map[key]
        else:
            no = len(blob)
            blob += names[o:o + l]
            off_map[key] = (no, l)
            new_name_off[i], new_name_len[i] = (no, l)

    out = bytearray()
    out += struct.pack("<6siiii16x", b"RAFG1\x00", 1, Nn, En, len(blob))
    out += lat[keep_idx].astype("<i4").tobytes()
    out += lon[keep_idx].astype("<i4").tobytes()
    out += new_csr.tobytes()
    out += remap[to_node[ek]].tobytes()
    out += len_m[ek].astype("<u2").tobytes()
    out += speed[ek].tobytes()
    out += flags[ek].tobytes()
    out += new_name_off.tobytes()
    out += new_name_len.tobytes()
    out += bytes(blob)
    return bytes(out)


# ---------- اندیس RAFI1/2 ----------

def split_index(data: bytes, bbox) -> bytes:
    w, s, e, n = (int(round(v * 1e7)) for v in bbox)
    magic = data[:6]
    ver, rc, nbs, nus = struct.unpack_from("<iiii", data, 6)
    assert magic == b"RAFI1\x00" and ver in (1, 2), "قالب اندیس ناشناخته"
    rec_size = 21 if ver == 2 else 20
    off = 34
    name_blob = data[off:off + nbs]; off += nbs
    norm_blob = data[off:off + nus]; off += nus
    recs = data[off:off + rc * rec_size]

    name_nb = bytearray(); norm_nb = bytearray()
    m1, m2 = {}, {}
    out_recs = bytearray()
    for i in range(rc):
        p = i * rec_size
        lat, lon = struct.unpack_from("<ii", recs, p + 10)
        if not (s <= lat <= n and w <= lon <= e):
            continue
        no, nl = struct.unpack_from("<I", recs, p)[0], recs[p + 4]
        qo, ql = struct.unpack_from("<I", recs, p + 5)[0], recs[p + 9]
        key1 = (no, nl)
        if key1 in m1:
            nno, nnl = m1[key1]
        else:
            nno, nnl = len(name_nb), nl
            name_nb += name_blob[no:no + nl]
            m1[key1] = (nno, nnl)
        key2 = (qo, ql)
        if key2 in m2:
            nqo, nql = m2[key2]
        else:
            nqo, nql = len(norm_nb), ql
            norm_nb += norm_blob[qo:qo + ql]
            m2[key2] = (nqo, nql)
        out_recs += struct.pack("<IBI", nno, nnl, nqo)
        out_recs += bytes([nql])
        out_recs += recs[p + 10:p + rec_size]
    new_rc = len(out_recs) // rec_size
    out = bytearray()
    out += struct.pack("<6siiii12x", b"RAFI1\x00", ver, new_rc, len(name_nb), len(norm_nb))
    out += bytes(name_nb) + bytes(norm_nb) + bytes(out_recs)
    return bytes(out)


# ---------- PMTiles v3 ----------

def hilbert_d2xy(n: int, d: int):
    """تبدیل فاصله هیلبرت به (x, y) — الگوریتم استاندارد منحنی هیلبرت."""
    x = y = 0
    t = d
    s = 1
    while s < n:
        rx = 1 & (t // 2)
        ry = 1 & (t ^ rx)
        if ry == 0:
            if rx == 1:
                x = s - 1 - x
                y = s - 1 - y
            x, y = y, x
        x += s * rx
        y += s * ry
        t //= 4
        s *= 2
    return x, y


def tile_id_to_zxy(tid: int):
    acc = 0
    z = 0
    tip = 1
    while acc + tip * tip <= tid:
        acc += tip * tip
        z += 1
        tip *= 2
    n = tid - acc
    x, y = hilbert_d2xy(tip, n)
    return z, x, y


def zxy_to_tile_id(z: int, x: int, y: int) -> int:
    acc = 0
    tip = 1
    for zz in range(z):
        acc += tip * tip
        tip *= 2
    n = 0
    s = tip // 2
    xx, yy = x, y
    while s > 0:
        rx = 1 & (xx // s)
        ry = 1 & (yy // s)
        n += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                xx = s - 1 - xx
                yy = s - 1 - yy
            xx, yy = yy, xx
        s //= 2
    return acc + n


def read_varint(b: bytes, i: int):
    result = 0
    shift = 0
    while True:
        byte = b[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return result, i
        shift += 7


def write_varint(buf: bytearray, v: int):
    while True:
        byte = v & 0x7F
        v >>= 7
        if v:
            buf.append(byte | 0x80)
        else:
            buf.append(byte)
            return


def parse_dir(buf: bytes):
    """دایرکتوری PMTiles v3 (قالب ستونی طبق spec) — خروجی: entries و leafها.

    ترتیب varintها: [تعداد ورودی] [Δtile_id ×n] [run_length ×n] [length ×n]
                    [offset ×n — صفر = پیوسته با قبلی، وگرنه offset+1]
    """
    i = 0
    n, i = read_varint(buf, i)
    ids = []
    tid = 0
    for _ in range(n):
        d, i = read_varint(buf, i)
        tid += d
        ids.append(tid)
    runs = []
    for _ in range(n):
        v, i = read_varint(buf, i)
        runs.append(v)
    lens = []
    for _ in range(n):
        v, i = read_varint(buf, i)
        lens.append(v)
    entries = []
    leaves = []
    for k in range(n):
        tmp, i = read_varint(buf, i)
        if k > 0 and tmp == 0:
            off = entries[-1][1] + entries[-1][2]
        else:
            off = tmp - 1
        if runs[k] == 0:
            leaves.append((ids[k], off, lens[k]))
        else:
            entries.append((ids[k], off, lens[k], runs[k]))
    return entries, leaves


def serialize_dir(entries):
    """نوشتن دایرکتوری به قالب ستونی رسمی (entries: [(tid, off, len, run)])"""
    out = bytearray()
    write_varint(out, len(entries))
    prev = 0
    for (tid, _, _, _) in entries:
        write_varint(out, tid - prev)
        prev = tid
    for (_, _, _, run) in entries:
        write_varint(out, run)
    for (_, _, length, _) in entries:
        write_varint(out, length)
    prev_end = 0
    for k, (_, off, length, _) in enumerate(entries):
        if k > 0 and off == prev_end:
            write_varint(out, 0)
        else:
            write_varint(out, off + 1)
        prev_end = off + length
    return bytes(out)


def tile_bbox(z, x, y):
    """محدوده (W,S,E,N) کاشی در تصویربرداری وب مرکاتور — عرض جغرافیا غیرخطی است"""
    import math
    step = 360.0 / (1 << z)
    w = x * step - 180.0
    e = (x + 1) * step - 180.0

    def lat_of(yy):
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / (1 << z)))))

    n = lat_of(y)
    s = lat_of(y + 1)
    return (w, s, e, n)


def intersects(b1, b2) -> bool:
    return not (b1[2] < b2[0] or b1[0] > b2[2] or b1[3] < b2[1] or b1[1] > b2[3])


def split_pmtiles(data: bytes, bbox) -> bytes:
    if data[:7] != b"PMTiles" or data[7] != 3:
        raise ValueError("قالب pmtiles ناشناخته")
    ro, rl = struct.unpack_from("<QQ", data, 8)
    jo, jl = struct.unpack_from("<QQ", data, 24)
    lo, ll = struct.unpack_from("<QQ", data, 40)
    to, tl = struct.unpack_from("<QQ", data, 56)
    icomp, tcomp, ttype = data[97], data[98], data[99]
    minz, maxz = data[100], data[101]

    def decomp(b: bytes) -> bytes:
        if icomp == 2:
            return gzip.decompress(b)
        if icomp == 1:
            return b
        raise ValueError(f"فشرده‌سازی داخلی پشتیبانی نمی‌شود: {icomp}")

    root = decomp(data[ro:ro + rl])
    entries, leaves = parse_dir(root)
    # برگ‌ها (در صورت وجود) هم خوانده می‌شوند
    for (ltid, loff, llen) in leaves:
        ld = decomp(data[lo + loff:lo + loff + llen])
        e2, _ = parse_dir(ld)
        entries += e2

    # باز کردن runها و فیلتر بر اساس محدوده
    tiles = []
    for (tid, off, length, run) in entries:
        for k in range(run):
            tiles.append((tid + k, off, length))
    kept = []
    for (tid, off, length) in tiles:
        z, x, y = tile_id_to_zxy(tid)
        if intersects(tile_bbox(z, x, y), bbox):
            kept.append((tid, off, length))
    if not kept:
        raise ValueError("هیچ کاشی در محدوده نیست")
    kept.sort(key=lambda t: t[0])

    # tile_data پیوسته به‌ترتیب tile_id → clustered = 1
    body = bytearray()
    new_entries = []
    for (tid, off, length) in kept:
        new_entries.append((tid, len(body), length, 1))
        body += data[to + off:to + off + length]
    num_addressed = len(kept)
    num_contents = num_addressed  # بدون حذف تکرار — داده پیوسته

    dir_comp = gzip.compress(serialize_dir(new_entries), 9) if icomp == 2 \
        else serialize_dir(new_entries)

    # متادیتا — محدوده و مرکز ولایت
    meta = json.loads(decomp(data[jo:jo + jl]))
    wD, sD, eD, nD = bbox
    meta["bounds"] = [wD, sD, eD, nD]
    meta["center"] = [(wD + eD) / 2, (sD + nD) / 2, min(6, maxz)]
    meta_comp = gzip.compress(json.dumps(meta, separators=(",", ":")).encode(), 9) \
        if icomp == 2 else json.dumps(meta, separators=(",", ":")).encode()

    # چیدمان: header(127) + root + metadata + tile_data (پیوسته، clustered)
    root_off = 127
    root_len = len(dir_comp)
    json_off = root_off + root_len
    json_len = len(meta_comp)
    tiles_off = json_off + json_len

    hdr = bytearray(127)
    hdr[0:7] = b"PMTiles"
    hdr[7] = 3
    struct.pack_into("<QQ", hdr, 8, root_off, root_len)
    struct.pack_into("<QQ", hdr, 24, json_off, json_len)
    struct.pack_into("<QQ", hdr, 40, 0, 0)  # بدون برگ
    struct.pack_into("<QQ", hdr, 56, tiles_off, len(body))
    struct.pack_into("<QQQ", hdr, 72, num_addressed, num_addressed, num_contents)
    hdr[96] = 1  # clustered — داده پیوسته به‌ترتیب tile_id
    hdr[97] = icomp
    hdr[98] = tcomp
    hdr[99] = ttype
    hdr[100] = minz
    hdr[101] = maxz
    struct.pack_into("<ii", hdr, 102, int(wD * 1e7), int(sD * 1e7))
    struct.pack_into("<ii", hdr, 110, int(eD * 1e7), int(nD * 1e7))
    hdr[118] = min(6, maxz)
    struct.pack_into("<ii", hdr, 119, int((wD + eD) / 2 * 1e7), int((sD + nD) / 2 * 1e7))
    return bytes(hdr) + dir_comp + meta_comp + bytes(body)


# ---------- ساخت بسته‌ها ----------

def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def build_province(pid, name, bbox, base_files):
    w, s, e, n = bbox
    graph = split_graph(base_files["graph"], bbox)
    index = split_index(base_files["index"], bbox)
    pmt = split_pmtiles(base_files["pmtiles"], bbox)
    style = base_files["style"]

    files = {
        f"{pid}.rafgraph": graph,
        f"{pid}.rafidx": index,
        f"{pid}.pmtiles": pmt,
        "style.json": style,
    }
    if len(pmt) < 2000:
        raise ValueError(f"کاشی {pid} بسیار کوچک است: {len(pmt)} بایت")

    manifest = {
        "schema": 1,
        "id": pid,
        "name": name,
        "region": f"ولایت {name}",
        "dataDate": DATA_DATE,
        "builderVersion": BUILDER,
        "bounds": {"west": w, "south": s, "east": e, "north": n},
        "formats": {"graph": "RAFG1/1", "index": "RAFI1/1", "tiles": "openmaptiles pmtiles"},
        "license": "© OpenStreetMap contributors — ODbL",
        "files": [
            {"name": fn, "sizeBytes": len(fc), "sha256": sha256(fc)}
            for fn, fc in files.items()
        ],
    }

    zpath = os.path.join(OUT_DIR, f"{pid}-{DATA_DATE}.rahpkg.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for fn, fc in files.items():
            z.writestr(fn, fc)
        z.writestr("pkgmanifest.json", json.dumps(manifest, ensure_ascii=False, indent=1))
    return zpath, manifest


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    base_files = {
        "graph": open(os.path.join(BASE_DIR, "afghanistan.rafgraph"), "rb").read(),
        "index": open(os.path.join(BASE_DIR, "afghanistan.rafidx"), "rb").read(),
        "pmtiles": open(os.path.join(BASE_DIR, "afghanistan.pmtiles"), "rb").read(),
        "style": open(os.path.join(BASE_DIR, "style.json"), "rb").read(),
    }
    only = [a for a in sys.argv[1:] if not a.startswith("-")]
    total = 0
    entries = []
    for pid, name, bbox in PROVINCES:
        if only and pid not in only:
            continue
        zpath, manifest = build_province(pid, name, bbox, base_files)
        size = os.path.getsize(zpath)
        total += size
        entries.append({
            "id": pid, "name": name, "region": f"ولایت {name}",
            "dataDate": DATA_DATE, "builderVersion": BUILDER,
            "url": f"asset://packages/{pid}-{DATA_DATE}.rahpkg.zip",
            "sizeBytes": size, "sha256": sha256(open(zpath, "rb").read()),
            "bounds": {"west": bbox[0], "south": bbox[1], "east": bbox[2], "north": bbox[3]},
            "note": "بسته ولایتی — نقشه، مسیریابی و جست‌وجوی همان ولایت بدون انترنت",
        })
        print(f"{pid:14s} {size/1048576:6.2f}MB  graph={len(base_files['graph'])>0 and ''}{os.path.getsize(zpath)}", flush=True)
    print(f"TOTAL {total/1048576:.1f}MB for {len(entries)} provinces")
    # manifest محلی برای برنامه
    if not only:
        base_zip = "/home/z/my-project/work/v091/rahyab-afghanistan/app/src/main/assets/initial/afghanistan.rahpkg.zip"
        bz = open(base_zip, "rb").read()
        m = {
            "schema": 1,
            "updated": "2026-11-10T00:00:00Z",
            "source": "bundled",
            "packages": [
                {
                    "id": "afghanistan", "name": "افغانستان — بسته پایه کشوری",
                    "region": "همه ۳۴ ولایت", "dataDate": "2025-11",
                    "builderVersion": "0.8.1",
                    "url": "asset://initial/afghanistan.rahpkg.zip",
                    "sizeBytes": len(bz), "sha256": sha256(bz),
                    "note": "این بسته همراه خود برنامه است و در صورت نیاز از همین‌جا دوباره نصب می‌شود — بدون انترنت.",
                }
            ] + entries,
        }
        os.makedirs(ASSETS_PKG, exist_ok=True)
        with open(os.path.join(ASSETS_PKG, "manifest.json"), "w", encoding="utf-8") as f:
            json.dump(m, f, ensure_ascii=False, indent=1)
        # کپی زیپ‌ها به دارایی‌های برنامه
        for ent in entries:
            src = os.path.join(OUT_DIR, f"{ent['id']}-{DATA_DATE}.rahpkg.zip")
            shutil.copy(src, ASSETS_PKG)
        print(f"manifest.json + {len(entries)} zip → {ASSETS_PKG}")


if __name__ == "__main__":
    main()
