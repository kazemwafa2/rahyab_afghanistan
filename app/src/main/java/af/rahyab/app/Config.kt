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
    // سبک آنلاین و آفلاین از یک سیستم طراحی مشترک استفاده می‌کنند (rahyab_light/dark.json)
    // تنها منبع کاشی بین دو حالت فرق می‌کند: آنلاین = کاشی برداری OpenFreeMap (بدون کلید؛
    // بدون تضمین دائمی بودن سرویس — docs/SERVER.md)، آفلاین = فایل pmtiles بستهٔ نصب‌شده.
    const val TILES_URL = "https://tiles.openfreemap.org/planet"
    /** سبک‌های قدیمی — فقط برای عقب‌نشینی و اشکال‌زدایی؛ مسیر اصلی سبک از دارایی برنامه ساخته می‌شود */
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
    /** ریلیز ثابت بسته‌های نقشه در مخزن پروژه */
    const val RELEASE_TAG = "map-data"

    /** نشانی manifest بسته‌ها: ریلیز ثابت map-data در مخزن پروژه */
    val MANIFEST_URL: String get() = "$REPO_URL/releases/download/$RELEASE_TAG/manifest.json"

    /**
     * فایل داده اختصاصی افغانستان (PMTiles، زوم ۰ تا ۱۴، ~۱۶۰MB) در ریلیز map-data.
     * حالت آنلاین، وقتی بسته کامل کشوری روی گوشی نصب نیست، کاشی‌ها را با درخواست Range
     * مستقیم از همین فایل می‌خواند؛ اگر شکست خورد به OpenFreeMap برمی‌گردد.
     * برای کاربر زیاد، همین فایل را روی Worker/R2 بگذارید و فقط این نشانی را عوض کنید.
     */
    val AF_PMTILES_URL: String get() = "$REPO_URL/releases/download/$RELEASE_TAG/af.pmtiles"

    /**
     * صفحهٔ دارایی‌های ریلیز — اسکن پشتیبان فهرست بسته‌ها.
     * اگر manifest.json روی سرور قدیمی بماند ولی فایل‌های بسته تازه بارگذاری شوند
     * (دقیقاً اتفاقی که برای کاربر افتاد)، برنامه از همین صفحه نسخهٔ جدید را می‌بیند.
     */
    val RELEASE_ASSETS_URL: String get() = "$REPO_URL/releases/expanded_assets/$RELEASE_TAG"

    /** شناسه قدیمی بسته ملی؛ برای شناسایی داده‌های نصب‌شده نسخه‌های قبلی */
    const val BASE_PACKAGE_ID = "afghanistan"
    /** شناسه بسته پایه کامل (راه‌های اصلی شهرها هم) — دانلودی اختیاری، سنگین‌تر */
    const val BASE_FULL_PACKAGE_ID = "afghanistan_full"

    // ---------- سرور اطلاعات جاده‌ای (ضمیمهٔ ۱ — Cloudflare Worker + D1) ----------
    // ورکر مستقر و زندهٔ پروژه (نام: rahyab-api) — بدون هیچ توکن یا کلید مخفی.
    // کاربر می‌تواند در «تنظیمات پیشرفته» نشانی دیگری بگذارد (کلید poi_api_base).
    const val POI_API_BASE = "https://rahyab-api.ghznywalghznywal8.workers.dev"

    // ---------- انکر نقشه ----------
    // شروع نقشه: کابل
    const val START_LAT = 34.5281
    const val START_LON = 69.1723
    const val START_ZOOM = 12.0

    // ---------- سقف شبکه ----------
    const val HTTP_CONNECT_TIMEOUT_MS = 15_000
    const val HTTP_READ_TIMEOUT_MS = 20_000
}
