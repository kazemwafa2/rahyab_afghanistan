# قالب‌های داده آفلاین

این سند مشخصات دقیق قالب‌های باینری بسته آفلاین است؛ هم ابزار ساخت (tools/build_package.py)
و هم خواننده‌های برنامه (OfflineRouter.kt، OfflineGeocoder.kt) از همین مشخصات پیروی می‌کنند.

همه اعداد **Little-Endian** هستند.

## ۱. گراف مسیریابی — RAFG1 نسخه ۱ (`.rafgraph`)

```
سرآیند (38 بایت):
  0    6    جادو "RAFG1\0"
  6    4    نسخه = 1 (i32)
  10   4    nodeCount (i32)
  14   4    edgeCount (i32)  — تعداد یال‌های جهت‌دار
  18   4    nameBlobSize (i32)
  22   16   رزرو (صفر)

بخش‌ها به ترتیب:
  lat      i32 × nodeCount   (عرض جغرافیایی × 1e7)
  lon      i32 × nodeCount   (طول جغرافیایی × 1e7)
  csr      u32 × (nodeCount+1)  — آفست‌های آغاز یال هر گره در آرایه یال‌ها
  toNode   u32 × edgeCount
  lenM     u16 × edgeCount   (طول پاره‌راه به متر، حداکثر 65535)
  speedKmh u8  × edgeCount   (سرعت موتر؛ برای یال پیاده = 0)
  flags    u8  × edgeCount   (bit0=قابل موتر، bit1=قابل پیاده، bit2=دوربرگردان)
  nameOff  u32 × edgeCount   (آفست در جدول نام‌ها؛ 0xFFFFFFFF = بی‌نام)
  nameLen  u8  × edgeCount
  names    nameBlobSize بایت (UTF-8)

یال‌ها بر اساس گره مبدأ صعودی مرتب‌اند تا CSR کار کند.
یک‌طرفه بودن در زمان ساخت اعمال شده: یال معکوس اصلاً نوشته نمی‌شود.
پیاده همیشه دوسویه است.
```

محدودیت‌های رعایت‌شده در مسیریابی: oneway (موتر)، دوربرگردان (یک‌طرفه)،
دسترسی موتر/پیاده (access، motor_vehicle، foot)، کلاس راه → سرعت.

نقاط ضعف شناخته‌شده (برای شفافیت):
- محدودیت‌های پیچ (turn restrictions) رعایت نمی‌شود؛ در نسخه‌های بعد با جدول restrictions اضافه می‌شود.
- ارتفاع/شیب محاسبه نمی‌شود.

## ۲. اندیس جست‌وجو

**نسخه ۲ (فعلی):** یک بایت دسته (cat) در پایان هر رکورد اضافه شده است — ۲۱ بایت.
دسته‌ها برای جست‌وجوی سریع مکان‌ها (مساجد، شفاخانه‌ها، دواخانه‌ها و…) استفاده می‌شوند:
`1`=مذهبی، `2`=صحی، `3`=دواخانه، `4`=آموزشی، `5`=پمپ‌بنزین، `6`=بانک، `7`=بازار/دوکان،
`8`=رستورانت، `9`=هوتل، `10`=پارک، `11`=پولیس/آتش‌نشانی، `12`=حمل‌ونقل، `13`=اداره،
`14`=پارکینگ، `15`=فرهنگی/ورزشی. نسخه ۱ (۲۰ بایت، بدون دسته) هم‌چنان خوانده می‌شود. — RAFI1 نسخه ۱ (`.rafidx`)

```
سرآیند (34 بایت):
  0    6    جادو "RAFI1\0"
  6    4    نسخه = 1
  10   4    recordCount (i32)
  14   4    nameBlobSize (i32)  — نام‌های اصلی (نمایشی)
  18   4    normBlobSize (i32)  — نام‌های نرمال‌شده (مقایسه)
  22   12   رزرو

بخش‌ها:
  nameBlob  (UTF-8)
  normBlob  (UTF-8)
  رکورد × recordCount — هر رکورد دقیقاً 20 بایت:
    nameOff  u32
    nameLen  u8
    normOff  u32
    normLen  u8
    lat      i32 (×1e7)
    lon      i32 (×1e7)
    kind     u8   (0=شهر 1=شهرک 2=قریه 3=پای 4=ناحیه 5=محله 6=خیابان 7=ساحه/POI 8=سایر؛
                  بیت 128=نام جایگزین)
    rank     u8   (0..100 اهمیت)
```

نرمال‌سازی (یکسان در Python و Kotlin — TextNorm.kt):
ی عربی→ی، ک عربی→ک، ة→ه، همه شکل‌های الف→ا، ؤ→و، ئ→ی، ۀ/ہ/ھ→ه، ں→ن،
حذف اعراب و کشیده، ZWNJ→فاصله، ارقام فارسی/عربی→انگلیسی، حروف بزرگ→کوچک،
هر نویسه غیرحرف/غیررقم→فاصله، فشردن فاصله‌های تکراری.

## ۳. بسته — `.rahpkg.zip`

```
pkgmanifest.json   شناسنامه: schema، id، name، region، dataDate، builderVersion،
                   bounds، formats، license، files[{name, sizeBytes, sha256}]
<id>.rafgraph      گراف مسیریابی
<id>.rafidx        اندیس جست‌وجو
<id>.pmtiles       کاشی‌های برداری OpenMapTiles (زوم 0 تا 14)
style.json         سبک محلی با جای‌نگهدارهای __PMTILES__ و __FONTS__
fonts/<stack>/<range>.pbf   گلیف‌های Noto Sans (SIL OFL)
```

## ۴. manifest سراسری (`manifest.json`)

```json
{
  "schema": 1,
  "updated": "2026-11-06T00:00:00Z",
  "packages": [
    {
      "id": "kabul", "name": "کابل و حومه", "region": "کابل و حومه",
      "dataDate": "2026-11-05", "builderVersion": "1.0.0",
      "file": "kabul-2026-11-06.rahpkg.zip",
      "url": "https://github.com/USER/rahyab-afghanistan/releases/download/map-data/kabul-2026-11-06.rahpkg.zip",
      "sizeBytes": 12345678,
      "sha256": "…",
      "bounds": {"west":68.85,"south":34.35,"east":69.45,"north":34.95}
    }
  ]
}
```

نشانی پیش‌فرض manifest در Config.kt ساخته می‌شود:
`$REPO_URL/releases/latest/download/manifest.json` — یعنی آخرین انتشار مخزن.
برای تغییر منبع (مثلاً سرور خودتان) کافی است نشانی را در Config یا
`manifest_url` در تنظیمات برنامه عوض کنید.
