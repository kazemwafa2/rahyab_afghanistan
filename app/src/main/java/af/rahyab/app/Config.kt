package af.rahyab.app

/**
 * تنظیمات مرکزی برنامه. همه نشانی‌ها و ثابت‌ها فقط در همین فایل تغییر می‌کنند.
 *
 * مهم: نشانی مخزن از BuildConfig.RAHYAB_REPO_URL می‌آید که در app/build.gradle.kts
 * از پراپرتی gradle.properties با نام rahyabGithubUser ساخته می‌شود.
 * کافی است یک بار نام کاربری گیت‌هاب خود را در gradle.properties بنویسید.
 */
object Config {
    val APP_VERSION: String get() = BuildConfig.VERSION_NAME
    val REPO_URL: String get() = BuildConfig.RAHYAB_REPO_URL

    /** شناسه برنامه برای سرویس‌های عمومی — مطابق سیاست Nominatim باید معتبر و تماس‌پذیر باشد */
    val USER_AGENT: String
        get() = "RahyabAfghanistan/${BuildConfig.VERSION_NAME} (+$REPO_URL)"

    // ---------- نقشه آنلاین ----------
    // OpenFreeMap: بدون کلید و ثبت‌نام؛ بدون تضمین دائمی بودن سرویس (docs/SERVER.md)
    const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
    const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

    // ---------- جست‌وجوی آنلاین (Nominatim) ----------
    const val GEOCODER_URL = "https://nominatim.openstreetmap.org"
    /** سیاست رسمی: حداکثر یک درخواست در ثانیه برای استفاده‌های کم‌حجم */
    const val GEOCODER_MIN_INTERVAL_MS = 1100L
    /** عمر اعتبار کش نتایج جست‌وجو روی گوشی */
    const val SEARCH_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L
    /** حداکثر تعداد نتایج ذخیره‌شده در کش جست‌وجو */
    const val SEARCH_CACHE_MAX = 300

    // ---------- مسیریابی آنلاین ----------
    // فقط برای نمونه اولیه و آزمایش؛ محدودیت سرور عمومی FOSSGIS باید رعایت شود
    const val ROUTING_CAR_URL = "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
    const val ROUTING_FOOT_URL = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"
    const val ROUTING_BIKE_URL = "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"
    const val ROUTE_CACHE_TTL_MS = 10 * 60 * 1000L
    const val ROUTE_CACHE_MAX = 40

    // ---------- بسته‌های آفلاین ----------
    /** نشانی manifest بسته‌ها: ریلیز ثابت map-data در مخزن پروژه */
    val MANIFEST_URL: String get() = "$REPO_URL/releases/download/map-data/manifest.json"

    /** شناسه بسته پایه کشوری — همراه خود برنامه نصب می‌شود (APK) */
    const val BASE_PACKAGE_ID = "afghanistan"
    /** شناسه بسته پایه کامل (راه‌های اصلی شهرها هم) — دانلودی اختیاری، سنگین‌تر */
    const val BASE_FULL_PACKAGE_ID = "afghanistan_full"
    /** مسیر دارایی بسته پایه داخل APK */
    const val BASE_PACKAGE_ASSET = "initial/afghanistan.rahpkg.zip"

    // ---------- انکر نقشه ----------
    // شروع نقشه: کابل
    const val START_LAT = 34.5281
    const val START_LON = 69.1723
    const val START_ZOOM = 12.0

    // ---------- سقف شبکه ----------
    const val HTTP_CONNECT_TIMEOUT_MS = 15_000
    const val HTTP_READ_TIMEOUT_MS = 20_000
}
