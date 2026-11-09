# معماری

```
UI (MainActivity / MapPackagesActivity)
   │
   ├── NavigationController + NavigationService
   │       ناوبری در سطح برنامه: با صفحه خاموش هم ادامه دارد،
   │       اعلان دائمی، اعلام صوتی، تشخیص خروج از مسیر و محاسبه مجدد
   │
   ├── Services.kt  ← نقطه تعویض: GeocoderService, RoutingService
   │       ├── آفلاین (اولویت): OfflineRouter + OfflineGeocoder روی بسته نصب‌شده
   │       ├── آنلاین (جایگزین): Nominatim (با محدودسازی نرخ + کش) و OSRM/FOSSGIS
   │       └── سرور اختصاصی خودتان  (آینده — docs/SERVER.md)
   │
   ├── OfflineData + PackageDownloader
   │       دانلود/نصب/حذف بسته‌های آفلاین از GitHub Releases (manifest نسخه‌دار)
   │
   ├── MapLibre 11.11 (نمایش نقشه)
   │       ├── آنلاین: OpenFreeMap (روز/شب/خودکار)
   │       └── آفلاین: PMTiles محلی + سبک محلی + فونت محلی (بدون هیچ درخواست شبکه)
   │
   ├── NavEngine (پیشرفت روی مسیر، پیچ بعدی، فاصله)
   └── Config.kt (همه آدرس‌ها و تنظیمات؛ نشانی مخزن از gradle.properties)
```

اصول:
- رابط کاربری فقط با رابط‌های `GeocoderService` و `RoutingService` کار می‌کند؛ عوض کردن سرور یا موتور آفلاین نیازی به تغییر UI ندارد.
- بسته آفلاین نصب باشد → جست‌وجو، مسیریابی، ناوبری و نقشه همگی بدون انترنت کار می‌کنند.
- هیچ حساب کاربری برای نقشه و مسیریابی لازم نیست.
- داده شخصی (خانه، کار، علاقه‌مندی‌ها) فقط روی خود گوشی ذخیره می‌شود.
- آماده برای تاکسی: بعداً ماژول‌های `core-map`، `online-services` و `taxi` اضافه می‌شوند و پشت feature flag خاموش می‌مانند.
- پرداخت و مجوز فعالیت حمل‌ونقل باید پیش از ساخت ماژول تاکسی از نظر حقوقی بررسی شود.

ماژول‌های داده (tools/):
- `build_package.py` — ساخت گراف مسیریابی، اندیس جست‌وجو، استخراج PBF و بسته‌بندی
- `validate_package.py` — اعتبارسنجی بسته پیش از انتشار (فایل خراب منتشر نمی‌شود)
- `make_manifest.py` — ساخت manifest سراسری برای دانلودکننده برنامه
- `offline_style_template.json` — سبک نقشه آفلاین (OpenMapTiles schema)
