package af.rahyab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ترافیک زنده — بدون نیاز به هیچ تنظیمی از سوی کاربر.
 *
 * منابع داده (به ترتیب اولویت، همه خودکار):
 *  ۱) گزارش‌های کاربران راه‌یاب (حادثه، بسته‌بودن، ترافیک سنگین، خرابی جاده)
 *     — همیشه در دسترس، کاملاً آفلاین، مستقیم از پایگاه‌داده خود گوشی.
 *  ۲) فید جامعه (GeoJSON) — اگر در دسترس باشد؛ نشانی پیش‌فرض از مخزن رسمی
 *     پروژه خوانده می‌شود و هر ۹۰ ثانیه یک بار تازه‌سازی می‌گردد.
 *  ۳) کاشی‌های جریان TomTom — فقط اگر کاربر در «تنظیمات پیشرفته» کلید API
 *     خودش را وارد کرده باشد (اختیاری کامل).
 *
 * قانون شماره ۵ پروژه: هرگز داده جعلی یا قدیمی به‌عنوان ترافیک زنده نمایش
 * داده نمی‌شود. اگر هیچ منبعی داده نداشت، لایه ترافیک خالی می‌ماند و فقط
 * داخل تنظیمات وضعیت واقعی منابع نشان داده می‌شود — بدون پیام خطای مزاحم.
 */
object TrafficModule {

    /** نشانی پیش‌فرض فید جامعه — فایل داخل خود مخزن (شاخهٔ main).
     *  این فایل در مسیر traffic/traffic.geojson مخزن نگهداری می‌شود و با هر
     *  push به‌روز می‌شود؛ برخلاف ریلیز، هیچ‌گاه ۴۰۴ نمی‌دهد. */
    const val DEFAULT_FEED_URL =
        "https://raw.githubusercontent.com/kazemwafa2/rahyab_afghanistan/main/traffic/traffic.geojson"

    /** نشانی قدیمی (ریلیز map-data) که همیشه ۴۰۴ می‌داد — برای مهاجرت خودکار */
    const val LEGACY_FEED_URL =
        "https://github.com/kazemwafa2/rahyab_afghanistan/releases/download/map-data/traffic.geojson"

    data class TrafficPoint(
        val lat: Double, val lon: Double,
        val kind: String,     // report / engine / feed
        val color: String,    // رنگ نمایش: سبز/نارنجی/قرمز
        val title: String,
        val rid: Long = 0     // شناسهٔ سروری گزارش (برای تأیید/رد با لمس نقشه — بند ۹)
    )

    private val prefsLock = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("traffic", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("enabled", v).apply()

    /** نشانی فید جامعه — پیش‌فرض فایل رسمی مخزن؛ اگر نسخهٔ قدیمی ذخیره شده
     *  باشد (ریلیز map-data که ۴۰۴ می‌داد) خودکار به نشانی تازه مهاجرت می‌کند */
    fun feedUrl(ctx: Context): String {
        val v = prefs(ctx).getString("feed_url", DEFAULT_FEED_URL)!!
        return if (v == LEGACY_FEED_URL) DEFAULT_FEED_URL else v
    }

    fun setFeedUrl(ctx: Context, v: String) {
        val trimmed = v.trim()
        // اگر کاربر متن قدیمی را برگرداند، همان به نشانی تازه تبدیل شود
        prefs(ctx).edit().putString("feed_url",
            if (trimmed == LEGACY_FEED_URL) DEFAULT_FEED_URL else trimmed).apply()
    }

    /** کلید TomTom (اختیاری کامل) */
    fun tomtomKey(ctx: Context): String = prefs(ctx).getString("tomtom_key", "") ?: ""
    fun setTomtomKey(ctx: Context, v: String) = prefs(ctx).edit().putString("tomtom_key", v.trim()).apply()

    var lastFeedFetchMs: Long = 0
        private set
    var feedPoints: List<TrafficPoint> = emptyList()
        private set
    var feedOk: Boolean = false
        private set

    /** پس از تغییر نشانی فید، کش قدیمی نادیده گرفته شود */
    fun lastFeedFetchReset() {
        feedOk = false
        feedPoints = emptyList()
        lastFeedFetchMs = 0
    }

    /**
     * دریافت فید جامعه. در شکست: بی‌صدا (فقط لاگ) — کاربر پیام خطا نمی‌بیند.
     * قالب GeoJSON: FeatureCollection با Point یا LineString؛ رنگ از
     * ویژگی color (green/orange/red) یا congestion_level ۰-۳.
     */
    fun refreshFeed(ctx: Context) {
        if (!isEnabled(ctx)) return
        val url = feedUrl(ctx)
        if (url.isBlank()) return
        try {
            val body = Http.get(url, readTimeoutMs = 8000)
            val fc = JSONObject(body)
            val feats = fc.optJSONArray("features") ?: throw BadResponseException("no features")
            val out = ArrayList<TrafficPoint>(feats.length().coerceAtMost(400))
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val geom = f.optJSONObject("geometry") ?: continue
                val prop = f.optJSONObject("properties") ?: JSONObject()
                val type = geom.optString("type")
                val colorRaw = prop.optString("color", "").lowercase()
                val level = prop.optInt("congestion_level", -1)
                val color = when {
                    colorRaw in listOf("green", "سبز") -> "#23A455"
                    colorRaw in listOf("orange", "نارنجی") -> "#F5A623"
                    colorRaw in listOf("red", "قرمز") -> "#E0342C"
                    level >= 2 -> "#E0342C"
                    level == 1 -> "#F5A623"
                    level == 0 -> "#23A455"
                    else -> "#F5A623"
                }
                val title = prop.optString("title", prop.optString("note", ctx.getString(R.string.traffic_fallback_title)))
                when (type) {
                    "Point" -> {
                        val c = geom.optJSONArray("coordinates") ?: continue
                        if (c.length() >= 2) out.add(TrafficPoint(c.getDouble(1), c.getDouble(0), "feed", color, title))
                    }
                    "LineString" -> {
                        val c = geom.optJSONArray("coordinates") ?: continue
                        if (c.length() >= 1) {
                            // یک نقطه نشانگر در میانه خط کافی است؛ خط کامل در منبع خطی ترسیم می‌شود
                            val mid = c.getJSONArray(c.length() / 2)
                            out.add(TrafficPoint(mid.getDouble(1), mid.getDouble(0), "feed", color, title))
                        }
                    }
                }
            }
            feedPoints = out
            feedOk = true
            lastFeedFetchMs = System.currentTimeMillis()
            // ذخیره محلی برای نمایش آفلاین اخیر (حداکثر ۱۰۰ نقطه)
            try {
                val arr = JSONArray()
                for (p in out.take(100)) {
                    arr.put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("color", p.color).put("title", p.title))
                }
                File(ctx.cacheDir, "traffic_feed_cache.json").writeText(arr.toString())
            } catch (e: Exception) { }
        } catch (e: Exception) {
            // بی‌صدا — لایه با گزارش‌های کاربران ادامه می‌یابد
            feedOk = false
            android.util.Log.d("TrafficModule", "فید ترافیک در دسترس نیست: ${e.message}")
            // استفاده از آخرین داده کش‌شده (نمایش موقت داده اخیر، با علامت زمان)
            try {
                val f = File(ctx.cacheDir, "traffic_feed_cache.json")
                if (f.isFile && System.currentTimeMillis() - f.lastModified() < 30 * 60 * 1000L) {
                    val arr = JSONArray(f.readText())
                    val out = ArrayList<TrafficPoint>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out.add(TrafficPoint(o.getDouble("lat"), o.getDouble("lon"), "feed", o.getString("color"), o.getString("title")))
                    }
                    feedPoints = out
                }
            } catch (e2: Exception) { }
        }
    }

    /**
     * همه نقاط نمایش‌پذیر ترافیک: گزارش‌های کاربران + وضعیت‌های زندهٔ موتور ترافیک + فید.
     * رنگ‌ها فقط بر اساس داده واقعی؛ مناطق بی‌داده یا «عادی» هیچ رنگی نمی‌گیرند
     * (قانون: هرگز دادهٔ جعلی یا «ناکافی» به‌عنوان ترافیک نمایش داده نمی‌شود).
     */
    fun allPoints(ctx: Context): List<TrafficPoint> {
        if (!isEnabled(ctx)) return emptyList()
        val out = ArrayList<TrafficPoint>()
        for (r in ReportsModule.active(ctx)) {
            out.add(TrafficPoint(r.lat, r.lon, r.type, r.mapColor(), r.typeLabel(ctx), rid = r.sid))
        }
        // وضعیت‌های زندهٔ موتور ترافیک (بند ۱۴) — فقط وضعیت‌های واقعیِ فعال؛
        // عادی/ناکافی/منقضی نمایش داده نمی‌شوند
        for (s in TrafficStore.cachedStates(ctx)) {
            if (s.state != TrafficRules.ST_SUSPECTED && s.state != TrafficRules.ST_MODERATE &&
                s.state != TrafficRules.ST_SEVERE && s.state != TrafficRules.ST_EXTREME) continue
            val c = s.center() ?: continue
            val label = ctx.getString(NavigationController.trafficStateRes(s.state))
            val road = s.roadName?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            val conf = ctx.getString(
                if (s.confidence == "higher") R.string.traffic_conf_higher
                else R.string.traffic_conf_preliminary
            )
            out.add(TrafficPoint(c.first, c.second, "engine", TrafficRules.colorFor(s.state), "$label$road • $conf"))
        }
        out.addAll(feedPoints)
        return out
    }

    /** وضعیت واقعی منابع برای نمایش در تنظیمات (بدون جعلی‌سازی) */
    fun statusLine(ctx: Context): String {
        if (!isEnabled(ctx)) return ctx.getString(R.string.traffic_disabled_state)
        val rep = ReportsModule.active(ctx).size
        val parts = ArrayList<String>()
        parts.add(ctx.getString(R.string.traffic_src_reports, rep))
        // موتور ترافیک خودکار (بخش ۱۰ تا ۱۴) — وضعیت صادقانه و مُهر زمانی (بخش ۲۰)
        val live = TrafficStore.cachedStates(ctx).count {
            it.state == TrafficRules.ST_SUSPECTED || it.state == TrafficRules.ST_MODERATE ||
                it.state == TrafficRules.ST_SEVERE || it.state == TrafficRules.ST_EXTREME
        }
        parts.add(ctx.getString(R.string.traffic_src_engine, live))
        val lastLive = TrafficStore.lastRefreshMs(ctx)
        if (lastLive > 0) {
            val fresh = System.currentTimeMillis() - lastLive <= TrafficStore.STATE_TTL_MS
            parts.add(
                ctx.getString(
                    if (fresh) R.string.traffic_src_engine_live else R.string.traffic_src_engine_old,
                    NavigationController.formatTimeOfDay(lastLive)
                )
            )
        } else {
            parts.add(ctx.getString(R.string.traffic_src_engine_none))
        }
        if (feedOk) parts.add(ctx.getString(R.string.traffic_src_feed_ok))
        else if (lastFeedFetchMs > 0) parts.add(ctx.getString(R.string.traffic_src_feed_old))
        else parts.add(ctx.getString(R.string.traffic_src_feed_none))
        val line = parts.joinToString(" • ")
        return if (lastFeedFetchMs > 0)
            line + " • " + ctx.getString(R.string.traffic_updated, NavigationController.formatTimeOfDay(lastFeedFetchMs))
        else line
    }

    /** قالب کاشی جریان TomTom برای سبک نقشه (فقط با کلید کاربر) */
    fun tomtomTileUrl(ctx: Context): String? {
        val k = tomtomKey(ctx)
        if (k.isBlank()) return null
        return "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=$k"
    }
}
