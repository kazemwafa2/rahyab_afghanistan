#!/usr/bin/env python3
"""افزودن/به‌روزرسانی بستهٔ afghanistan_full در manifest موجود ریلیز map-data."""
import hashlib, json, os, sys
from datetime import datetime, timezone

def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b: break
            h.update(b)
    return h.hexdigest()

def main():
    live_manifest, new_zip, base_url, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    m = json.load(open(live_manifest))
    import zipfile
    with zipfile.ZipFile(new_zip) as z:
        pm = json.loads(z.read("pkgmanifest.json"))
    entry = {
        "id": pm["id"],
        "name": pm.get("name", pm["id"]),
        "region": pm.get("region", ""),
        "dataDate": pm.get("dataDate", ""),
        "builderVersion": pm.get("builderVersion", ""),
        "file": os.path.basename(new_zip),
        "url": f"{base_url.rstrip('/')}/{os.path.basename(new_zip)}",
        "sizeBytes": os.path.getsize(new_zip),
        "sha256": sha256_file(new_zip),
        "bounds": pm.get("bounds"),
    }
    pkgs = [p for p in m["packages"] if p["id"] != pm["id"]]
    # afghanistan_full بعد از بستهٔ پایهٔ ملی و پیش از ولایات درج شود
    idx = next((i for i, p in enumerate(pkgs) if p["id"] == "afghanistan"), 0)
    if idx < len(pkgs) - 1:
        pkgs.insert(idx + 1, entry)
    else:
        pkgs.append(entry)
    m["packages"] = pkgs
    m["updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=2)
    print("manifest:", out, "| packages:", len(pkgs))
    for p in pkgs[:3]:
        print(" ", p["id"], p["dataDate"])

if __name__ == "__main__":
    main()
