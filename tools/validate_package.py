#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
اعتبارسنجی بسته نقشه آفلاین — پیش از انتشار اجرا می‌شود.
فایل خراب یا ناقص هرگز نباید منتشر شود.

بررسی‌ها:
  - ساختار zip و خوانا بودن pkgmanifest.json
  - وجود فایل‌های مورد انتظار (graph/index/tiles/style/fonts)
  - سلامت سرآیند گراف (RAFG1) و اندیس (RAFI1) و نسخه آن‌ها
  - هم‌خوانی checksum هر فایل با pkgmanifest.json
  - اندازه معقول کاشی‌ها
"""
import argparse
import hashlib
import io
import json
import struct
import sys
import zipfile


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip", help="فایل بسته .rahpkg.zip")
    ap.add_argument("--expect", default="graph,index,tiles,style",
                    help="موارد الزامی: graph,index,tiles,style,fonts (فونت اختیاری است — از برنامه بارگذاری می‌شود)")
    args = ap.parse_args()
    expect = set(args.expect.split(","))

    ok = True
    with zipfile.ZipFile(args.zip) as z:
        names = z.namelist()
        print("محتوا:", ", ".join(sorted(names)))

        if "pkgmanifest.json" not in names:
            print("خطا: pkgmanifest.json نیست")
            return 1
        manifest = json.loads(z.read("pkgmanifest.json"))
        pkg_id = manifest["id"]
        print(f"بسته: {pkg_id} — {manifest.get('region')} — داده {manifest.get('dataDate')}")

        # checksum فایل‌ها
        by_name = {f["name"]: f for f in manifest.get("files", [])}
        for fn, meta in by_name.items():
            if fn not in names:
                print(f"خطا: {fn} در manifest هست ولی در بسته نیست")
                ok = False
                continue
            data = z.read(fn)
            if sha256(data) != meta["sha256"]:
                print(f"خطا: checksum {fn} مطابقت ندارد")
                ok = False
            if data and meta.get("sizeBytes", 0) != len(data):
                print(f"خطا: اندازه {fn} مطابقت ندارد")
                ok = False

        # گراف
        graph_name = f"{pkg_id}.rafgraph"
        if "graph" in expect:
            if graph_name not in names:
                print("خطا: فایل گراف نیست")
                return 1
            g = z.read(graph_name)
            if g[:6] != b"RAFG1\x00":
                print("خطا: سرآیند گراف نامعتبر")
                ok = False
            else:
                ver, n_nodes, n_edges, nblob = struct.unpack("<iiii", g[6:22])
                if ver != 1 or n_nodes <= 0 or n_edges <= 0:
                    print(f"خطا: سرآیند گراف ناسالم: ver={ver} nodes={n_nodes} edges={n_edges}")
                    ok = False
                else:
                    print(f"گراف: {n_nodes:,} گره، {n_edges:,} یال")
                # lat,lon(4×2n) + csr(4(n+1)) + to(4e) + lenM(2e) + speed(1e) + flags(1e)
                # + nameOff(4e) + nameLen(1e) + names(nblob)
                expected_len = 22 + 16 + 4 * (2 * n_nodes + n_nodes + 1 + n_edges) + 2 * n_edges + 2 * n_edges + 5 * n_edges + nblob
                if len(g) != expected_len:
                    print(f"هشدار: اندازه گراف {len(g):,} ≠ محاسبه‌شده {expected_len:,}")

        # اندیس
        idx_name = f"{pkg_id}.rafidx"
        if "index" in expect:
            if idx_name not in names:
                print("خطا: فایل اندیس نیست")
                return 1
            ix = z.read(idx_name)
            if ix[:6] != b"RAFI1\x00":
                print("خطا: سرآیند اندیس نامعتبر")
                ok = False
            else:
                ver, n_rec, nblob, zblob = struct.unpack("<iiii", ix[6:22])
                rec_size = {1: 20, 2: 21}.get(ver)  # نسخه ۲: یک بایت دسته اضافه دارد
                if rec_size is None or n_rec <= 0:
                    print(f"خطا: سرآیند اندیس ناسالم (نسخه {ver})")
                    ok = False
                else:
                    print(f"اندیس: {n_rec:,} رکورد (نسخه {ver})")
                expected_len = 22 + 12 + nblob + zblob + rec_size * n_rec
                if len(ix) != expected_len:
                    print(f"هشدار: اندازه اندیس {len(ix):,} ≠ محاسبه‌شده {expected_len:,}")

        # کاشی‌ها
        tiles_name = f"{pkg_id}.pmtiles"
        if "tiles" in expect:
            if tiles_name not in names:
                print("خطا: فایل کاشی‌ها نیست")
                return 1
            t = z.read(tiles_name)
            if t[:7] != b"PMTiles":
                # PMTiles spec: magic "PMTiles" در بایت‌های ۷-۱۳ (3..9?) — سرآیند ۱۰ بایتی با magic در 3
                if len(t) > 10 and t[3:10] == b"PMTiles":
                    pass
                else:
                    print("خطا: سرآیند PMTiles نامعتبر")
                    ok = False
            if len(t) < 10_000:
                print("خطا: فایل کاشی‌ها خیلی کوچک است")
                ok = False
            else:
                print(f"کاشی‌ها: {len(t)/1e6:.1f}MB")

        if "style" in expect and "style.json" not in names:
            print("خطا: style.json نیست")
            ok = False
        if "fonts" in expect and not any(n.startswith("fonts/") for n in names):
            print("خطا: فونت‌ها نیستند")
            ok = False

    if ok:
        print("اعتبارسنجی موفق ✔")
        return 0
    print("اعتبارسنجی ناموفق ✘")
    return 1


if __name__ == "__main__":
    sys.exit(main())
