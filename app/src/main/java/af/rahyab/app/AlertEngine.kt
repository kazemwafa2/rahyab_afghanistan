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
        val police: Boolean = true,
        val redlight: Boolean = true,
        val slippery: Boolean = true,
        val narrow: Boolean = true,
        val school: Boolean = true,
        val crosswalk: Boolean = true,
        val rail: Boolean = true,
        val animal: Boolean = true,
        val trafficlight: Boolean = true,
        val limit: Boolean = true,
        val steepDown: Boolean = true,
        val steepUp: Boolean = true,
        val toll: Boolean = true,
        val border: Boolean = true,
        val bridge: Boolean = true,
        val tunnel: Boolean = true,
        val trafficLight: Boolean = true,
        val overspeed: Boolean = true,
        val speedLimitKmh: Int = 80
    ) {
        val anyReportEnabled: Boolean
            get() = bump || accident || traffic || closed || hazard || cam ||
                police || redlight || slippery || narrow || school || crosswalk ||
                rail || animal || trafficlight || limit || steepDown || steepUp ||
                toll || border || bridge || tunnel || trafficLight
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
        ReportsModule.TYPE_POLICE -> "alert_police"
        ReportsModule.TYPE_RED_LIGHT_CAMERA -> "alert_redlight"
        ReportsModule.TYPE_SLIPPERY -> "alert_slippery"
        ReportsModule.TYPE_NARROW -> "alert_narrow"
        ReportsModule.TYPE_SCHOOL_ZONE -> "alert_school"
        ReportsModule.TYPE_CROSSWALK -> "alert_crosswalk"
        ReportsModule.TYPE_RAIL_CROSSING -> "alert_rail"
        ReportsModule.TYPE_ANIMALS -> "alert_animal"
        ReportsModule.TYPE_BROKEN_TRAFFIC_LIGHT -> "alert_trafficlight"
        ReportsModule.TYPE_SPEED_LIMIT_CHANGE -> "alert_limit"
        ReportsModule.TYPE_STEEP_DOWN -> "alert_steep_down"
        ReportsModule.TYPE_STEEP_UP -> "alert_steep_up"
        ReportsModule.TYPE_TOLL -> "alert_toll"
        ReportsModule.TYPE_BORDER -> "alert_border"
        ReportsModule.TYPE_BRIDGE -> "alert_bridge"
        ReportsModule.TYPE_TUNNEL -> "alert_tunnel"
        ReportsModule.TYPE_TRAFFIC_LIGHT -> "alert_trafficlight"
        ReportsModule.TYPE_SURVEILLANCE -> "alert_cam"
        // انواع سند جامع بخش ۷ — با کلیپ‌های موجود نگاشت می‌شوند؛ صدای تازه لازم نیست:
        // ترافیک متوسط ← صدای ترافیک، انسداد موقت ← صدای انسداد،
        // ریزش کوه/سیلاب/عملیات ← صدای «خطر جاده‌ای»
        ReportsModule.TYPE_MEDIUM_TRAFFIC -> "alert_traffic"
        ReportsModule.TYPE_TEMP_BLOCK -> "alert_closed"
        ReportsModule.TYPE_LANDSLIDE -> "alert_hazard"
        ReportsModule.TYPE_FLOOD -> "alert_hazard"
        ReportsModule.TYPE_ROAD_WORKS -> "alert_hazard"
        else -> null
    }

    fun enabledFor(c: Config, type: String): Boolean = when (type) {
        ReportsModule.TYPE_SPEED_BUMP -> c.bump
        ReportsModule.TYPE_ACCIDENT -> c.accident
        ReportsModule.TYPE_HEAVY_TRAFFIC -> c.traffic
        ReportsModule.TYPE_ROAD_CLOSED -> c.closed
        ReportsModule.TYPE_HAZARD -> c.hazard
        ReportsModule.TYPE_SPEED_CAMERA -> c.cam
        ReportsModule.TYPE_POLICE -> c.police
        ReportsModule.TYPE_RED_LIGHT_CAMERA -> c.redlight
        ReportsModule.TYPE_SLIPPERY -> c.slippery
        ReportsModule.TYPE_NARROW -> c.narrow
        ReportsModule.TYPE_SCHOOL_ZONE -> c.school
        ReportsModule.TYPE_CROSSWALK -> c.crosswalk
        ReportsModule.TYPE_RAIL_CROSSING -> c.rail
        ReportsModule.TYPE_ANIMALS -> c.animal
        ReportsModule.TYPE_BROKEN_TRAFFIC_LIGHT -> c.trafficlight
        ReportsModule.TYPE_SPEED_LIMIT_CHANGE -> c.limit
        ReportsModule.TYPE_STEEP_DOWN -> c.steepDown
        ReportsModule.TYPE_STEEP_UP -> c.steepUp
        ReportsModule.TYPE_TOLL -> c.toll
        ReportsModule.TYPE_BORDER -> c.border
        ReportsModule.TYPE_BRIDGE -> c.bridge
        ReportsModule.TYPE_TUNNEL -> c.tunnel
        ReportsModule.TYPE_TRAFFIC_LIGHT -> c.trafficLight
        ReportsModule.TYPE_SURVEILLANCE -> c.cam
        // انواع سند جامع بخش ۷ — از تنظیمات همان خانوادهٔ هشدار استفاده می‌کنند
        ReportsModule.TYPE_MEDIUM_TRAFFIC -> c.traffic
        ReportsModule.TYPE_TEMP_BLOCK -> c.closed
        ReportsModule.TYPE_LANDSLIDE -> c.hazard
        ReportsModule.TYPE_FLOOD -> c.hazard
        ReportsModule.TYPE_ROAD_WORKS -> c.hazard
        else -> false
    }

    /** بررسی سریع فعال‌بودن یک نوع هشدار از SharedPreferences (برای RoadPois) */
    fun enabledType(p: android.content.SharedPreferences, type: String): Boolean =
        enabledFor(Config(
            bump = p.getBoolean("a_bump", true),
            accident = p.getBoolean("a_accident", true),
            traffic = p.getBoolean("a_traffic", true),
            closed = p.getBoolean("a_closed", true),
            hazard = p.getBoolean("a_hazard", true),
            cam = p.getBoolean("a_cam", true),
            police = p.getBoolean("a_police", true),
            redlight = p.getBoolean("a_redlight", true),
            slippery = p.getBoolean("a_slippery", true),
            narrow = p.getBoolean("a_narrow", true),
            school = p.getBoolean("a_school", true),
            crosswalk = p.getBoolean("a_crosswalk", true),
            rail = p.getBoolean("a_rail", true),
            animal = p.getBoolean("a_animal", true),
            trafficlight = p.getBoolean("a_trafficlight", true),
            limit = p.getBoolean("a_limit_change", true),
            steepDown = p.getBoolean("a_steep_down", true),
            steepUp = p.getBoolean("a_steep_up", true),
            toll = p.getBoolean("a_toll", true),
            border = p.getBoolean("a_border", true),
            bridge = p.getBoolean("a_bridge", true),
            tunnel = p.getBoolean("a_tunnel", true),
            trafficLight = p.getBoolean("a_traffic_light", true),
            overspeed = p.getBoolean("a_speed", true),
            speedLimitKmh = p.getInt("a_limit", 80)
        ), type)

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
