package af.rahyab.app

/**
 * موتور هشدارهای جاده‌ای — مثل «تنظیمات هشدارها» نشان.
 *
 * انواع هشدار: سرعت‌گیر، تصادم، زحمت ترافیکی، راه‌بند، خطر جاده‌ای، کمره سرعت
 * و سرعت زیاد خودرو. منبع هشدارها فقط گزارش‌های واقعی کاربران راه‌یاب و تنظیم
 * حد سرعت خود کاربر است — هیچ داده جعلی تولید نمی‌شود (قانون ۵ چک‌لیست).
 *
 * منطق کاملاً خالص است تا در آزمون واحد پوشش داده شود:
 *  - هر گزارش در شعاع ۹۰۰ متر فقط یک بار اعلام و تا خروج از شعاع ۱۴۰۰ متر نگه
 *    داشته می‌شود؛ خروج از شعاع دوباره «مسلح» می‌کند (بازگشت به مسیر).
 *  - هشدار سرعت زیاد حداکثر هر ۴۵ ثانیه یک بار.
 */
object AlertEngine {

    /** شعاع اعلام هشدار گزارش‌ها */
    const val RADIUS_M = 900.0
    /** شعاع مسلح‌شدن دوباره پس از فاصله گرفتن */
    const val REARM_M = 1400.0
    /** کمینه فاصله زمانی تکرار هشدار سرعت */
    const val SPEED_REPEAT_MS = 45_000L

    data class Config(
        val bump: Boolean = true,
        val accident: Boolean = true,
        val traffic: Boolean = true,
        val closed: Boolean = true,
        val hazard: Boolean = true,
        val cam: Boolean = true,
        val overspeed: Boolean = true,
        val speedLimitKmh: Int = 80
    ) {
        val anyReportEnabled: Boolean
            get() = bump || accident || traffic || closed || hazard || cam
    }

    /** نقطه گزارش — بدون وابستگی به دیتابیس برای سادگی آزمون */
    data class ReportPt(val id: Long, val type: String, val lat: Double, val lon: Double)

    /** وضعیت ماندگار موتور میان قرائت‌های GPS */
    data class State(val alerted: Set<String> = emptySet(), val lastSpeedAlertMs: Long = 0L)

    /** یک هشدار تولیدشده؛ reportId == null یعنی هشدار سرعت */
    data class Alert(val clip: String, val reportId: Long?)

    fun clipFor(type: String): String? = when (type) {
        ReportsModule.TYPE_SPEED_BUMP -> "alert_bump"
        ReportsModule.TYPE_ACCIDENT -> "alert_accident"
        ReportsModule.TYPE_HEAVY_TRAFFIC -> "alert_traffic"
        ReportsModule.TYPE_ROAD_CLOSED -> "alert_closed"
        ReportsModule.TYPE_HAZARD -> "alert_hazard"
        ReportsModule.TYPE_SPEED_CAMERA -> "alert_cam"
        else -> null
    }

    fun enabledFor(c: Config, type: String): Boolean = when (type) {
        ReportsModule.TYPE_SPEED_BUMP -> c.bump
        ReportsModule.TYPE_ACCIDENT -> c.accident
        ReportsModule.TYPE_HEAVY_TRAFFIC -> c.traffic
        ReportsModule.TYPE_ROAD_CLOSED -> c.closed
        ReportsModule.TYPE_HAZARD -> c.hazard
        ReportsModule.TYPE_SPEED_CAMERA -> c.cam
        else -> false
    }

    /**
     * یک گام ارزیابی.
     * خروجی: وضعیت تازه + فهرست هشدارهای جدید (تازه‌ها فقط پخش صوتی می‌شوند).
     */
    fun evaluate(
        lat: Double, lon: Double, speedKmh: Double,
        reports: List<ReportPt>, cfg: Config, st: State, nowMs: Long
    ): Pair<State, List<Alert>> {
        val out = ArrayList<Alert>()
        val alerted = HashSet(st.alerted)
        if (cfg.anyReportEnabled) {
            for (r in reports) {
                if (!enabledFor(cfg, r.type)) continue
                val clip = clipFor(r.type) ?: continue
                val key = "${r.id}:${r.type}"
                val d = Geo.dist(lat, lon, r.lat, r.lon)
                if (d <= RADIUS_M) {
                    if (!alerted.contains(key)) {
                        alerted.add(key)
                        out.add(Alert(clip, r.id))
                    }
                } else if (d > REARM_M) {
                    alerted.remove(key)
                }
            }
        }
        var lastSpeed = st.lastSpeedAlertMs
        if (cfg.overspeed && speedKmh > cfg.speedLimitKmh + 4 &&
            nowMs - lastSpeed > SPEED_REPEAT_MS
        ) {
            lastSpeed = nowMs
            out.add(Alert("alert_speed", null))
        }
        return Pair(State(alerted, lastSpeed), out)
    }
}
