package af.rahyab.app

/**
 * قواعد موتور ترافیک — بخش ۱۲، ۱۳ و ۱۵ سند جامع.
 *
 * همهٔ مقادیر «اولیهٔ قابل‌تنظیم» سند در همین یک جا جمع شده‌اند تا آینده‌ساز
 * به‌روزرسانی از سرور یا تنظیمات ساده باشند. این شیء کاملاً خالص است (بدون
 * وابستگی اندروید) تا در آزمون واحد پوشش داده شود و با منطق Worker
 * (worker/src/index.js — ROAD_BASE_KMH/hourFactor/stateForRatio) هم‌سان است.
 *
 * هیچ دادهٔ جعلی اینجا تولید نمی‌شود؛ فقط «قواعد تفسیر دادهٔ واقعی».
 */
object TrafficRules {

    // ---------- حالت‌های نهایی (بخش ۱۴: ۸ حالت) ----------
    const val ST_NORMAL = "normal"           // عادی
    const val ST_SUSPECTED = "suspected"     // احتمال ترافیک
    const val ST_MODERATE = "moderate"       // متوسط
    const val ST_SEVERE = "severe"           // سنگین
    const val ST_EXTREME = "extreme"         // بسیار شدید
    const val ST_CLOSED = "closed"           // انسداد (از گزارش‌های انسداد)
    const val ST_INSUFFICIENT = "insufficient" // اطلاعات ناکافی
    const val ST_EXPIRED = "expired"         // منقضی/پایان‌یافته

    // ---------- سرعت پایهٔ اولیهٔ سند (بخش ۱۲ — میانهٔ بازه‌ها، غیررسمی) ----------
    // شهر ۲۰–۳۵ / اصلی ۳۰–۵۰ / برون‌شهری آسفالت ۴۰–۷۰ / کوهستان ۲۰–۴۰ / خاکی ۱۵–۳۵
    val ROAD_BASE_KMH = mapOf(
        "city" to 27.5, "main" to 40.0, "intercity" to 55.0,
        "mountain" to 30.0, "dirt" to 25.0
    )

    /** ضریب ساعت روز — اولیه؛ با دادهٔ واقعی بعدی قابل تنظیم (بخش ۱۲) */
    fun hourFactor(hourOfDay: Int): Double = when (hourOfDay) {
        in 0..4 -> 0.85    // شب
        5 -> 0.9
        in 6..9 -> 0.75    // شلوغی صبح
        in 10..15 -> 1.0   // میانهٔ روز
        in 16..19 -> 0.7   // شلوغی عصر
        in 20..21 -> 0.9
        else -> 0.85
    }

    /** سرعت پایهٔ یک قطعه: نوع سرک × ضریب ساعت روز (کمینهٔ ۵ km/h) */
    fun baselineKmh(roadType: String, hourOfDay: Int): Double =
        maxOf(5.0, (ROAD_BASE_KMH[roadType] ?: 40.0) * hourFactor(hourOfDay))

    // ---------- آستانه‌های شدت (بخش ۱۳ — قابل‌تنظیم) ----------
    // <۶۰٪ پایه = احتمال؛ ~۳۵٪ = سنگین؛ ~۲۰٪ = بسیار شدید (لنگرهای سند)
    fun stateFor(ratio: Double): String = when {
        ratio >= 0.60 -> ST_NORMAL
        ratio >= 0.45 -> ST_SUSPECTED
        ratio >= 0.35 -> ST_MODERATE
        ratio >= 0.20 -> ST_SEVERE
        else -> ST_EXTREME
    }

    // ---------- اعتماد بر اساس شمار رانندهٔ مستقل (بخش ۱۳) ----------
    fun confidenceFor(drivers: Int): String = when {
        drivers < 3 -> "insufficient"   // اطلاعات ناکافی
        drivers <= 5 -> "preliminary"   // اعتماد اولیه
        else -> "higher"                // اعتماد بالاتر
    }

    // ---------- فاصلهٔ هشدار مسیرمحور (بخش ۱۵ و ۱۶ — قابل‌تنظیم) ----------
    // شهری ۵۰۰م–۱ک؛ برون‌شهری ۲–۳ک؛ خطر جدی جادهٔ پرسرعت ۳–۵ک
    fun alertAheadM(state: String, roadType: String): Double {
        val cityLike = roadType == "city" || roadType == "dirt"
        val fastRoad = roadType == "intercity" || roadType == "mountain"
        return when (state) {
            ST_EXTREME -> when {
                cityLike -> 1000.0
                fastRoad -> 4000.0   // خطر جدی جادهٔ برون‌شهری: ۳–۵ کیلومتر
                else -> 3000.0
            }
            ST_SEVERE, ST_CLOSED -> if (cityLike) 1000.0 else 3000.0
            ST_MODERATE, ST_SUSPECTED -> when {
                cityLike -> 500.0
                roadType == "intercity" -> 2000.0
                else -> 1000.0
            }
            else -> 0.0
        }
    }

    /** رنگ نمایش وضعیت روی نقشه — فقط برای دادهٔ واقعی (عادی/ناکافی رنگ نمی‌گیرد) */
    fun colorFor(state: String): String = when (state) {
        ST_SEVERE, ST_EXTREME, ST_CLOSED -> "#E0342C"
        ST_SUSPECTED, ST_MODERATE -> "#F5A623"
        else -> "#23A455"
    }

    /**
     * آیا این نوع گزارش هر دو جهت جاده را واقعاً تحت تأثیر قرار می‌دهد؟ (بند ۱۵ سند:
     * حادثهٔ یک‌طرفه به‌طور خودکار به جهت دیگر نسبت داده نمی‌شود مگر واقعاً هر دو را ببندد)
     */
    fun affectsBothDirections(type: String): Boolean = type in setOf(
        ReportsModule.TYPE_ROAD_CLOSED, ReportsModule.TYPE_TEMP_BLOCK,
        ReportsModule.TYPE_LANDSLIDE, ReportsModule.TYPE_FLOOD
    )

    /**
     * هشدار زودهنگام مسیرمحور برای گزارش‌های دیگران (بند ۱۵–۱۶):
     * فقط انواع بسته‌کنندهٔ جاده و تصادف خیلی شدید، از فاصلهٔ دور اعلام می‌شوند؛
     * بقیه هشدار نزدیک (۹۰۰ متر) موتور AlertEngine را دارند.
     * خروجی ۰ = هشدار دور ندارد.
     */
    fun farReportAlertM(type: String, severity: String?): Double = when {
        affectsBothDirections(type) -> 3000.0
        type == ReportsModule.TYPE_ACCIDENT && severity == "high" -> 2500.0
        else -> 0.0
    }
}
