# ابزار ساخت داده نقشه آفلاین

این ابزارها روی کمپیوتر یا GitHub Actions اجرا می‌شوند (نه روی گوشی) و از داده خام
OpenStreetMap بسته‌های آفلاین برنامه را می‌سازند.

## نصب پیش‌نیازها

```bash
pip install osmium numpy
# java 17+ برای planetiler
```

## ساخت بسته یک منطقه (مثال: کابل)

```bash
# ۱) داده کشور از Geofabrik (© OpenStreetMap contributors — ODbL)
curl -L -o afghanistan-latest.osm.pbf \
  https://download.geofabrik.de/asia/afghanistan-latest.osm.pbf

# ۲) استخراج منطقه + گراف مسیریابی + اندیس جست‌وجو
python3 tools/build_package.py \
  --name kabul --region "کابل و حومه" \
  --pbf afghanistan-latest.osm.pbf \
  --bbox 68.85,34.35,69.45,34.95 \
  --out dist --work work \
  --extract-pbf work/kabul.pbf

# ۳) کاشی‌های برداری با planetiler
java -Xmx8g -jar planetiler.jar \
  --area=kabul --osm_path=work/kabul.pbf \
  --bounds=68.85,34.35,69.45,34.95 \
  --output=work/kabul.pmtiles --download

# ۴) بسته نهایی (گراف + اندیس + کاشی + سبک + فونت + شناسنامه)
python3 tools/build_package.py \
  --name kabul --region "کابل و حومه" \
  --pbf afghanistan-latest.osm.pbf \
  --bbox 68.85,34.35,69.45,34.95 \
  --out dist --work work \
  --tiles work/kabul.pmtiles

# ۵) اعتبارسنجی — فایل خراب هرگز منتشر نشود
python3 tools/validate_package.py dist/kabul-*.rahpkg.zip

# ۶) manifest سراسری برای دانلودکننده برنامه
python3 tools/make_manifest.py --out dist/manifest.json \
  --base-url "https://github.com/USER/rahyab-afghanistan/releases/download/map-data" \
  dist/*.rahpkg.zip
```

در عمل همه این مراحل با Workflow آماده `.github/workflows/build-map.yml` خودکار انجام می‌شود.

## فایل‌ها

| فایل | کار |
|---|---|
| `build_package.py` | استخراج PBF، ساخت گراف RAFG1 و اندیس RAFI1، بسته‌بندی ZIP |
| `validate_package.py` | اعتبارسنجی سرآیندها، checksumها و محتوای بسته |
| `make_manifest.py` | ساخت manifest سراسری برای برنامه |
| `offline_style_template.json` | سبک نقشه آفلاین (OpenMapTiles schema؛ جای‌نگهدار `__PMTILES__` و `__FONTS__` توسط برنامه پر می‌شود) |

## سیاست‌های رعایت‌شده

- داده: © OpenStreetMap contributors — ODbL؛ انتساب در NOTICE.md و داخل سبک نقشه.
- ابزار: planetiler (Apache-2.0)، pyosmium (LGPL)، فونت Noto Sans (SIL OFL).
- Workflow ساخت بسته جدا از Workflow ساخت APK است و سهمیه Actions پایش می‌شود.
- نسخه داده (تاریخ PBF) و checksum همه فایل‌ها در pkgmanifest.json ثبت می‌شود.
