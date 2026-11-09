# راهنمای یکپارچه‌سازی برای اپلیکیشن‌های تاکسی و حمل‌ونقل

از نسخه ۰٫۶٫۰، برنامه راه‌یاب می‌تواند به‌عنوان **موتور مسیریابی** برای
اپلیکیشن‌های تاکسی آنلاین، پیک و حمل‌ونقل افغانستان عمل کند — مانند رابط
«مسیریابی با نشان» در اپلیکیشن‌های ایرانی.

## ۱. درخواست مسیریابی (Deep Link)

اپ شرکت، Rahyab را با این قالب باز می‌کند:

```
rahyab://navigate?origin_lat=34.5287&origin_lon=69.1758
                &destination_lat=33.5534&destination_lon=68.4231
                &travel_mode=driving
                &name=بازار تهله سیاب
```

| پارامتر | الزامی | توضیح |
|---|---|---|
| `destination_lat` / `destination_lon` | بله | مختصات مقصد (WGS84) |
| `origin_lat` / `origin_lon` | اختیاری | مبدأ؛ اگر ندهید موقعیت فعلی گوشی استفاده می‌شود |
| `travel_mode` | اختیاری | `driving` (پیش‌فرض) یا `walking` |
| `name` | اختیاری | نام مکان مقصد برای نمایش |

نمونه اندروید (Kotlin):

```kotlin
val uri = Uri.parse(
    "rahyab://navigate?origin_lat=$driverLat&origin_lon=$driverLon" +
    "&destination_lat=$passengerLat&destination_lon=$passengerLon" +
    "&travel_mode=driving&name=" + Uri.encode(passengerAddress)
)
startActivity(Intent(Intent.ACTION_VIEW, uri))
```

## ۲. دریافت نتیجه (اختیاری)

اگر اپ شرکت با `startActivityForResult` و پرچم `EXTRA_RETURN_RESULT=true`
فراخوانی کند، پس از محاسبه مسیر نتیجه برگردانده می‌شود:

```kotlin
val intent = Intent(Intent.ACTION_VIEW, uri).putExtra("EXTRA_RETURN_RESULT", true)
startActivityForResult(intent, REQ_ROUTE)   // از AppCompatActivityResultLauncher هم می‌شود

override fun onActivityResult(req: Int, res: Int, data: Intent?) {
    if (req == REQ_ROUTE && res == RESULT_OK && data != null) {
        val distanceM = data.getDoubleExtra("distance_m", 0.0)
        val durationS = data.getDoubleExtra("duration_s", 0.0)
        val routesCount = data.getIntExtra("routes_count", 1)
    }
}
```

| فیلد خروجی | معنا |
|---|---|
| `distance_m` | فاصله مسیر انتخابی (متر) |
| `duration_s` | زمان تخمینی مسیر انتخابی (ثانیه) |
| `routes_count` | تعداد مسیرهای محاسبه‌شده (۱ تا ۳) |
| `RESULT_FIRST_USER` | هیچ مسیری یافت نشد |

## ۳. امنیت و اعتبارسنجی

- مختصات ورودی اعتبارسنجی می‌شود (بازه جهانی ±90/±180 و عددی بودن)؛
  درخواست نامعتبر رد می‌شود و برنامه کرش نمی‌کند.
- `launchMode="singleTask"` — چند درخواست پشت‌سرهم هم درست هندل می‌شود.
- هیچ داده شخصی از اپ شرکت ذخیره نمی‌شود؛ درخواست فقط برای نمایش مسیر است.

## ۴. چه چیزی هنوز در این رابط نیست (شفاف)

- بازگرداندن **هندسه کامل مسیر** (نقاط) به اپ فراخوان فعلاً نیست؛ فعلاً
  فاصله/زمان/تعداد مسیرها برمی‌گردد. در نسخه بعد با یک ContentProvider
  یا Intent اضافی اضافه می‌شود.
- مسیریابی سرور-به-سرور (بدون باز شدن UI) فعلاً وجود ندارد — راه‌یاب
  یک اپلیکیشن روی گوشی است، نه سرویس ابری.
