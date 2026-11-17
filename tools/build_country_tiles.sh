#!/bin/bash
# ============================================================
# ساخت نقشه اختصاصی راهیاب از داده متن‌باز OpenStreetMap
# (همان منبعی که map.af استفاده می‌کند — بدون نیاز به API آن)
#
# پیش‌نیازها:
#   - tilemaker (باینری از github.com/systemed/tilemaker releases)
#   - pmtiles CLI  (از github.com/protomaps/go-pmtiles releases)
#   - فایل OSM افغانستان: https://download.geofabrik.de/asia/afghanistan-latest.osm.pbf
#     (هر روز به‌روز می‌شود؛ آخرین نسخه تست‌شده: 2026-11-12، 107MB)
#
# ورودی:  afghanistan-latest.osm.pbf در همین پوشه
# خروجی:  afghanistan.pmtiles — کل کشور، زوم 3 تا 14، سکیمای OpenMapTiles،
#         نام‌ها داری-محور (name:fa) + پشتو (name:ps)
# سپس با tools/split_base_provinces.py به ۳۴ بسته ولایتی تقسیم می‌شود.
#
# مجوز داده: © OpenStreetMap contributors — ODbL (انتساب در NOTICE.md الزامی است)
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/../.."   # ریشه مخزن → tools/ → ریشه
DATA_DIR="${DATA_DIR:-/home/z/my-project/mapdata}"

mkdir -p "$DATA_DIR"
cd "$DATA_DIR"

echo "۱/۳) ساخت کاشی‌ها با tilemaker (خروجی mbtiles — حافظه‌کم، سپس تبدیل به pmtiles)"
./tilemaker \
  --input afghanistan-latest.osm.pbf \
  --output afghanistan-omt.mbtiles \
  --config rahyab-omt-config.json \
  --process rahyab-omt-process.lua \
  --store tm_store \
  --shard-stores \
  --threads 2

echo "۲/۳) تبدیل mbtiles → pmtiles"
./bin/pmtiles convert afghanistan-omt.mbtiles rahyab-afghanistan.pmtiles

echo "۳/۳) تمیزکاری فضای موقت"
rm -rf tm_store

echo "تمام شد: $DATA_DIR/rahyab-afghanistan.pmtiles"
echo "نکته: اگر خروجی مستقیم pmtiles خواسته شد ولی حافظه کم بود، حتماً از مسیر mbtiles استفاده کنید؛"
echo "نویسنده pmtiles داخلی tilemaker روی کشورهای بزرگ حافظه زیادی می‌گیرد و ممکن است کشته شود."
