# انتشار بسته‌های آفلاین — محل دانلود عمومی و پایدار

برنامه راه‌یاب بسته‌ها را از نشانی ثابت زیر می‌خواند (در `Config.kt`):

```
https://github.com/rahyab-afghanistan/rahyab-afghanistan/releases/download/map-data/manifest.json
```

این نشانی فقط زمانی زنده می‌شود که بسته‌ها در ریلیز `map-data` مخزن گیت‌هاب منتشر شوند. دو راه وجود دارد:

## راه ۱ — کاملاً خودکار با GitHub Actions (توصیه می‌شود)

1. کد را به گیت‌هاب پوش کنید (مخزن `rahyab-afghanistan/rahyab-afghanistan`).
2. در گیت‌هاب: تب **Actions** → ورک‌فلو **publish-map-data** → **Run workflow**.
3. ورک‌فلو همه چیز را خودکار انجام می‌دهد: دانلود داده رسمی OSM از Geofabrik،
   ساخت بسته پایه کشوری + ۳۴ بسته ولایتی، ساخت `manifest.json` و `SHA256SUMS.txt`
   و بارگذاری همه در ریلیز `map-data`.
4. پس از پایان (حدود ۱ تا ۲ ساعت)، دانلود داخل برنامه بلافاصله کار می‌کند.

## راه ۲ — انتشار دستی از لپ‌تاپ

اگر بسته‌های ساخته‌شده (`download/packages/`) را همین حالا می‌خواهید منتشر کنید:

```bash
gh release create map-data \
  download/packages/*.rahpkg.zip download/packages/manifest.json download/packages/SHA256SUMS.txt \
  --title "بسته‌های آفلاین راه‌یاب" \
  --notes "بسته پایه کشوری + ۳۴ ولایت — © OpenStreetMap contributors (ODbL)"
```

## تغییر نشانی منبع (مثلاً هاست شخصی)

در صفحه «بسته‌های آفلاین» برنامه می‌توانید منبع را عوض کنید؛ همچنین با تنظیم
`rahyabGithubUser` در `gradle.properties` نشانی مخزن یک‌جا عوض می‌شود. اگر
manifest را روی سرور خودتان می‌گذارید، فیلد `url` هر بسته باید نشانی کامل
دانلود باشد یا فایل‌ها کنار همان manifest قرار گیرند.

## نکته صادقانه

تا وقتی ریلیز `map-data` منتشر نشده باشد، دکمه‌های دانلود برنامه پیام خطای
شبکه می‌دهند (و بسته پایه کشوری همچنان از خود APK نصب است). این محدودیت از
سندباکس ساخت است که دسترسی انتشار به گیت‌هاب ندارد — با یک کلیک طبق راه ۱ حل می‌شود.

## فایل PMTiles آنلاین (۱.۱.۶-test)

نقشهٔ آنلاین برنامه فایل `afghanistan_full.pmtiles` را مستقیم از ریلیز `map-data` با HTTP Range می‌خواند.
این فایل باید **جداگانه از ZIP** بارگذاری شود (همان فایل داخل `afghanistan_full-<تاریخ>.rahpkg.zip`).
بررسی پیش از انتشار:

```
curl -sSL -r 0-126 -o /dev/null -w '%{http_code}\n' \
  https://github.com/kazemwafa2/rahyab_afghanistan/releases/download/map-data/afghanistan_full.pmtiles
# باید 206 بدهد؛ ۷ بایت اول باید PMTiles باشد
```

اگر ۲۰۶ نیامد، برنامه از بستهٔ نصب‌شده یا OpenFreeMap استفاده می‌کند (بدون خطا).
