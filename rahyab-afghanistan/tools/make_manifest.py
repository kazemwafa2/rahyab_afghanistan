#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ساخت manifest سراسری بسته‌ها برای دانلودکننده برنامه.
manifest.json باید در GitHub Releases (تگ map-data) منتشر شود تا برنامه
از مسیر releases/latest/download/manifest.json بخواند.

هر بسته: شناسه، نام، تاریخ داده، حجم، checksum و نشانی کامل دانلود.
"""
import argparse
import glob
import hashlib
import json
import os
import sys
import zipfile
from datetime import datetime, timezone


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zips", nargs="+", help="فایل‌های .rahpkg.zip")
    ap.add_argument("--out", required=True)
    ap.add_argument("--base-url", default="", help="نشانی پایه؛ اگر خالی باشد از فیلد url داخل هر بسته استفاده نمی‌شود و file نسبی می‌ماند")
    args = ap.parse_args()

    packages = []
    for zpath in sorted(args.zips):
        with zipfile.ZipFile(zpath) as z:
            m = json.loads(z.read("pkgmanifest.json"))
        url = f"{args.base_url.rstrip('/')}/{os.path.basename(zpath)}" if args.base_url else ""
        packages.append({
            "id": m["id"],
            "name": m.get("name", m["id"]),
            "region": m.get("region", ""),
            "dataDate": m.get("dataDate", ""),
            "builderVersion": m.get("builderVersion", ""),
            "file": os.path.basename(zpath),
            "url": url,
            "sizeBytes": os.path.getsize(zpath),
            "sha256": sha256_file(zpath),
            "bounds": m.get("bounds"),
        })
        print(f"بسته: {m['id']} — {os.path.basename(zpath)} — {os.path.getsize(zpath)/1e6:.1f}MB")

    doc = {
        "schema": 1,
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "packages": packages,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
    print(f"manifest نوشته شد: {args.out}")


if __name__ == "__main__":
    sys.exit(main())
