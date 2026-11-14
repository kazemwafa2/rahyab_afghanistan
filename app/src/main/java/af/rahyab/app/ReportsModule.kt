package af.rahyab.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * گزارش حوادث جاده‌ای — بخش هشتم چک‌لیست + بخش ۳ و ۱۰ سند جامع.
 *
 * انواع گزارش: حادثه (تصادف)، خرابی جاده، بسته‌بودن جاده، ترافیک سنگین، خطر جاده‌ای.
 *  - ذخیرهٔ محلی روی خود گوشی (SQLite) + اشتراک‌گذاری اختیاری به‌صورت متن/لینک.
 *  - همگام‌سازی دوطرفه با سرور زندهٔ Cloudflare (POST/GET /api/v1/reports):
 *      * هر گزارش پس از ثبت محلی در صف «pending_reports» قرار می‌گیرد و در
 *        نخستین فرصت ارسال می‌شود؛ در نبود انترنت گزارش هرگز حذف نمی‌شود و
 *        پس از اتصال دوباره فرستاده می‌شود.
 *      * گزارش‌های دیگر رانندگان از سرور دریافت و در «remote_reports» نگهداری
 *        و همراه گزارش‌های محلی روی نقشه و در هشدارهای مسیر نمایش داده می‌شوند.
 *      * گزارش‌های منقضی پیش از ارسال دوباره بررسی و حذف می‌شوند.
 *  - انقضای خودکار: گزارش‌های بیشتر از ۲۴ ساعت نامعتبر می‌شوند.
 *  - اعتبارسنجی: نوع باید معتبر و یادداشت کوتاه‌تر از ۳۰۰ نویسه باشد.
 *  - حفاظت اطلاعات حساس: هر یادداشتی دربارهٔ ایست بازرسی/نیروهای امنیتی باشد
 *    فقط روی همین گوشی می‌ماند و هرگز به سرور ارسال نمی‌شود.
 *  - مهاجرت دیتابیس نسخهٔ ۱→۲: فقط «افزودن» جدول‌های جدید؛ هیچ جدول یا دادهٔ
 *    موجودی حذف، بازسازی یا تغییر نمی‌کند.
 */
object ReportsModule {

    const val TYPE_ACCIDENT = "accident"
    const val TYPE_ROAD_DAMAGE = "road_damage"
    const val TYPE_ROAD_CLOSED = "road_closed"
    const val TYPE_HEAVY_TRAFFIC = "heavy_traffic"
    const val TYPE_HAZARD = "hazard"
    /** هشدارهای سبک نشان: سرعت‌گیر و کمره سرعت (فقط از گزارش کاربران، بدون داده جعلی) */
    const val TYPE_SPEED_BUMP = "speed_bump"
    const val TYPE_SPEED_CAMERA = "speed_camera"
    /** هشدارهای واژه‌نامه دری (۰٫۹٫۷) — بخش ۵ راهنمای صوتی و متنی */
    const val TYPE_POLICE = "police"
    const val TYPE_RED_LIGHT_CAMERA = "red_light_camera"
    const val TYPE_SLIPPERY = "slippery"
    const val TYPE_NARROW = "narrow"
    const val TYPE_SCHOOL_ZONE = "school_zone"
    const val TYPE_CROSSWALK = "crosswalk"
    const val TYPE_RAIL_CROSSING = "rail_crossing"
    const val TYPE_ANIMALS = "animals"
    const val TYPE_BROKEN_TRAFFIC_LIGHT = "broken_traffic_light"
    const val TYPE_SPEED_LIMIT_CHANGE = "speed_limit_change"
    const val TYPE_STEEP_DOWN = "steep_down"
    const val TYPE_STEEP_UP = "steep_up"
    /** هشدارهای مکان‌های ثابت ضمیمهٔ ۱ — عوارض، مرز، پل، تونل، چراغ */
    const val TYPE_TOLL = "toll"
    const val TYPE_BORDER = "border"
    const val TYPE_BRIDGE = "bridge"
    const val TYPE_TUNNEL = "tunnel"
    const val TYPE_TRAFFIC_LIGHT = "traffic_light"
    const val TYPE_SURVEILLANCE = "surveillance"

    /** عمر اعتبار گزارش: ۲۴ ساعت (انقضای خودکار) */
    const val EXPIRY_MS = 24 * 60 * 60 * 1000L

    /**
     * انواعی که سرور زنده (ورکر Cloudflare) می‌پذیرد — فهرست LIVE_REPORT_TYPES
     * در worker/src/index.js. انواع مکان ثابت (عوارض، مرز، پل، تونل و…) در
     * لایهٔ POI مدیریت می‌شوند و به گزارش زنده ارسال نمی‌گردند.
     */
    val SERVER_TYPES = setOf(
        TYPE_ACCIDENT, TYPE_ROAD_DAMAGE, TYPE_ROAD_CLOSED, TYPE_HEAVY_TRAFFIC, TYPE_HAZARD,
        TYPE_SPEED_BUMP, TYPE_SPEED_CAMERA, TYPE_POLICE, TYPE_RED_LIGHT_CAMERA, TYPE_SLIPPERY,
        TYPE_NARROW, TYPE_SCHOOL_ZONE, TYPE_CROSSWALK, TYPE_RAIL_CROSSING, TYPE_ANIMALS,
        TYPE_BROKEN_TRAFFIC_LIGHT, TYPE_SPEED_LIMIT_CHANGE, TYPE_STEEP_DOWN, TYPE_STEEP_UP
    )

    /** واژه‌های ممنوع برای انتشار — حفاظت نیروهای امنیتی و ایست‌های بازرسی */
    val SENSITIVE_PATTERNS = listOf(
        "چک پوست", "چک‌پوست", "چک پست", "چک‌پست", "چک پوینت", "چک‌پوینت",
        "checkpoint", "check post", "checkpost", "بازرسی", "ایست بازرسی",
        "پوسته", "پوسته امنیتی", "نیروهای امنیتی", "پایگاه", "اردوگاه",
        "نظامی", "زره‌پوش", "تانک جنگی", "هوایی دولت", "سرباز", "تسلیم‌گاه"
    )

    class Report(
        val id: Long, val type: String, val note: String,
        val lat: Double, val lon: Double, val ts: Long,
        val fromServer: Boolean = false
    ) {
        val expired: Boolean get() = System.currentTimeMillis() - ts > EXPIRY_MS

        /** برچسب نوع گزارش به زبان انتخابی برنامه */
        fun typeLabel(ctx: Context): String = when (type) {
            TYPE_ACCIDENT -> ctx.getString(R.string.report_type_accident)
            TYPE_ROAD_DAMAGE -> ctx.getString(R.string.report_type_road_damage)
            TYPE_ROAD_CLOSED -> ctx.getString(R.string.report_type_road_closed)
            TYPE_HEAVY_TRAFFIC -> ctx.getString(R.string.report_type_heavy_traffic)
            TYPE_SPEED_BUMP -> ctx.getString(R.string.report_type_speed_bump)
            TYPE_SPEED_CAMERA -> ctx.getString(R.string.report_type_speed_camera)
            TYPE_POLICE -> ctx.getString(R.string.report_type_police)
            TYPE_RED_LIGHT_CAMERA -> ctx.getString(R.string.report_type_red_light_camera)
            TYPE_SLIPPERY -> ctx.getString(R.string.report_type_slippery)
            TYPE_NARROW -> ctx.getString(R.string.report_type_narrow)
            TYPE_SCHOOL_ZONE -> ctx.getString(R.string.report_type_school_zone)
            TYPE_CROSSWALK -> ctx.getString(R.string.report_type_crosswalk)
            TYPE_RAIL_CROSSING -> ctx.getString(R.string.report_type_rail_crossing)
            TYPE_ANIMALS -> ctx.getString(R.string.report_type_animals)
            TYPE_BROKEN_TRAFFIC_LIGHT -> ctx.getString(R.string.report_type_broken_traffic_light)
            TYPE_SPEED_LIMIT_CHANGE -> ctx.getString(R.string.report_type_speed_limit_change)
            TYPE_STEEP_DOWN -> ctx.getString(R.string.report_type_steep_down)
            TYPE_STEEP_UP -> ctx.getString(R.string.report_type_steep_up)
            TYPE_TOLL -> ctx.getString(R.string.report_type_toll)
            TYPE_BORDER -> ctx.getString(R.string.report_type_border)
            TYPE_BRIDGE -> ctx.getString(R.string.report_type_bridge)
            TYPE_TUNNEL -> ctx.getString(R.string.report_type_tunnel)
            TYPE_TRAFFIC_LIGHT -> ctx.getString(R.string.report_type_traffic_light)
            TYPE_SURVEILLANCE -> ctx.getString(R.string.report_type_surveillance)
            else -> ctx.getString(R.string.report_type_hazard)
        }

        /** برچسب پیش‌فرض (دری) — برای زمینه‌هایی بدون Context */
        val typeLabel: String
            get() = when (type) {
                TYPE_ACCIDENT -> "حادثه ترافیکی"
                TYPE_ROAD_DAMAGE -> "خرابی جاده"
                TYPE_ROAD_CLOSED -> "بسته‌بودن جاده"
                TYPE_HEAVY_TRAFFIC -> "ترافیک سنگین"
                TYPE_SPEED_BUMP -> "دست‌انداز"
                TYPE_SPEED_CAMERA -> "کمره کنترول سرعت"
                TYPE_POLICE -> "احتمال حضور پولیس"
                TYPE_RED_LIGHT_CAMERA -> "کمره چراغ سرخ"
                TYPE_SLIPPERY -> "سرک لغزنده"
                TYPE_NARROW -> "باریک شدن سرک"
                TYPE_SCHOOL_ZONE -> "منطقه مکتب"
                TYPE_CROSSWALK -> "گذرگاه عابران پیاده"
                TYPE_RAIL_CROSSING -> "گذرگاه ریل"
                TYPE_ANIMALS -> "عبور حیوانات"
                TYPE_BROKEN_TRAFFIC_LIGHT -> "چراغ ترافیکی خراب"
                TYPE_SPEED_LIMIT_CHANGE -> "تغییر محدودیت سرعت"
                TYPE_STEEP_DOWN -> "سرازیری تند"
                TYPE_STEEP_UP -> "سربالایی تند"
                TYPE_TOLL -> "ایستگاه عوارض"
                TYPE_BORDER -> "گذرگاه مرزی"
                TYPE_BRIDGE -> "پل در پیش رو"
                TYPE_TUNNEL -> "تونل در پیش رو"
                TYPE_TRAFFIC_LIGHT -> "چراغ ترافیکی"
                TYPE_SURVEILLANCE -> "کمره نظارتی"
                else -> "خطر جاده‌ای"
            }

        /** رنگ نشانگر روی نقشه — سبز/نارنجی/قرمز فقط برای داده واقعی گزارش */
        fun mapColor(): String = when (type) {
            TYPE_ACCIDENT -> "#E0342C"
            TYPE_ROAD_CLOSED -> "#E0342C"
            TYPE_SPEED_CAMERA -> "#E0342C"
            TYPE_RED_LIGHT_CAMERA -> "#E0342C"
            TYPE_POLICE -> "#2A6FB4"
            TYPE_HEAVY_TRAFFIC -> "#F5A623"
            TYPE_ROAD_DAMAGE -> "#F5A623"
            TYPE_SPEED_BUMP -> "#F5A623"
            TYPE_TOLL -> "#C62828"
            TYPE_BORDER -> "#AD1457"
            TYPE_BRIDGE -> "#5C6BC0"
            TYPE_TUNNEL -> "#455A64"
            TYPE_TRAFFIC_LIGHT -> "#E0342C"
            TYPE_SURVEILLANCE -> "#7E57C2"
            else -> "#F5A623"
        }
    }

    /**
     * نسخهٔ ۲: افزودن صف ارسال (pending_reports) و کش گزارش‌های سرور
     * (remote_reports). مهاجرت امن — فقط CREATE TABLE IF NOT EXISTS؛
     * هیچ داده یا جدول موجودی تغییر نمی‌کند.
     */
    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "reports.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS reports(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, note TEXT, lat REAL NOT NULL, lon REAL NOT NULL, ts INTEGER NOT NULL)")
            createV2(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createV2(db)
        }
        private fun createV2(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_reports(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, note TEXT, lat REAL NOT NULL, lon REAL NOT NULL, ts INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS remote_reports(sid INTEGER PRIMARY KEY, type TEXT NOT NULL, note TEXT, lat REAL NOT NULL, lon REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
        }
    }

    private val lock = Any()
    private val net = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var db: Db? = null
    var lastRemoteRefreshMs: Long = 0
        private set

    private fun getDb(ctx: Context): Db = synchronized(lock) {
        if (db == null) db = Db(ctx.applicationContext)
        db!!
    }

    /** اعتبارسنجی نوع گزارش */
    fun validType(t: String): Boolean = t in setOf(
        TYPE_ACCIDENT, TYPE_ROAD_DAMAGE, TYPE_ROAD_CLOSED, TYPE_HEAVY_TRAFFIC, TYPE_HAZARD,
        TYPE_SPEED_BUMP, TYPE_SPEED_CAMERA,
        TYPE_POLICE, TYPE_RED_LIGHT_CAMERA, TYPE_SLIPPERY, TYPE_NARROW, TYPE_SCHOOL_ZONE,
        TYPE_CROSSWALK, TYPE_RAIL_CROSSING, TYPE_ANIMALS, TYPE_BROKEN_TRAFFIC_LIGHT,
        TYPE_SPEED_LIMIT_CHANGE, TYPE_STEEP_DOWN, TYPE_STEEP_UP,
        TYPE_TOLL, TYPE_BORDER, TYPE_BRIDGE, TYPE_TUNNEL, TYPE_TRAFFIC_LIGHT, TYPE_SURVEILLANCE
    )

    /** آیا متن شامل اطلاعات حساس امنیتی است؟ (چنین گزارشی فقط محلی ذخیره می‌شود) */
    fun isSensitive(text: String): Boolean {
        val n = text.lowercase()
        return SENSITIVE_PATTERNS.any { n.contains(it) }
    }

    /** ثبت گزارش؛ خروجی: id یا خطا. هم‌زمان در صف سرور هم قرار می‌گیرد. */
    fun add(ctx: Context, type: String, note: String, lat: Double, lon: Double): Long {
        require(validType(type)) { ctx.getString(R.string.report_err_bad_type) }
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { ctx.getString(R.string.report_err_bad_coords) }
        require(note.length <= 300) { ctx.getString(R.string.report_err_long_note) }
        val ts = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("type", type); put("note", note); put("lat", lat); put("lon", lon); put("ts", ts)
        }
        val id = getDb(ctx).writableDatabase.insert("reports", null, cv)
        // ارسال به سیستم گزارش زندهٔ سراسری — گزارش حساس هرگز از گوشی خارج نمی‌شود
        if (type in SERVER_TYPES && !isSensitive(note) && RoadPois.serverConfigured(ctx)) {
            try { getDb(ctx).writableDatabase.insert("pending_reports", null, cv) } catch (e: Exception) { }
            syncNow(ctx)
        }
        return id
    }

    /** گزارش‌های معتبر (نه منقضی) — انقضای خودکار به‌صورت پرس‌وجو */
    fun active(ctx: Context, nearLat: Double? = null, nearLon: Double? = null, maxKm: Double = 60.0): List<Report> {
        val now = System.currentTimeMillis()
        val list = ArrayList<Report>()
        try {
            getDb(ctx).readableDatabase.rawQuery(
                "SELECT id, type, note, lat, lon, ts FROM reports WHERE ts > ? ORDER BY ts DESC LIMIT 300",
                arrayOf((now - EXPIRY_MS).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val r = Report(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getDouble(3), c.getDouble(4), c.getLong(5))
                    if (r.expired) continue
                    if (nearLat != null && nearLon != null) {
                        val d = Geo.dist(nearLat, nearLon, r.lat, r.lon) / 1000.0
                        if (d > maxKm) continue
                    }
                    list.add(r)
                }
            }
        } catch (e: Exception) { }
        // ادغام گزارش‌های زندهٔ دیگر رانندگان (از سرور) — با حذف تکرار گزارش محلی خودمان
        try {
            getDb(ctx).readableDatabase.rawQuery(
                "SELECT sid, type, note, lat, lon, created_at FROM remote_reports WHERE expires_at > ? LIMIT 300",
                arrayOf(now.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val sid = c.getLong(0); val type = c.getString(1)
                    val note = c.getString(2) ?: ""
                    val lat = c.getDouble(3); val lon = c.getDouble(4); val ts = c.getLong(5)
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    if (now - ts > EXPIRY_MS) continue
                    if (nearLat != null && nearLon != null) {
                        val d = Geo.dist(nearLat, nearLon, lat, lon) / 1000.0
                        if (d > maxKm) continue
                    }
                    if (list.any { it.type == type && Geo.dist(it.lat, it.lon, lat, lon) < 150.0 && Math.abs(it.ts - ts) < 45 * 60 * 1000L }) continue
                    list.add(Report(-sid, type, note, lat, lon, ts, fromServer = true))
                }
            }
        } catch (e: Exception) { }
        return list
    }

    /** پاک‌سازی گزارش‌های منقضی (هر بار باز شدن صفحه گزارش‌ها) */
    fun purgeExpired(ctx: Context) {
        try {
            val db = getDb(ctx).writableDatabase
            db.delete("reports", "ts < ?", arrayOf((System.currentTimeMillis() - EXPIRY_MS).toString()))
            db.delete("remote_reports", "expires_at < ?", arrayOf(System.currentTimeMillis().toString()))
        } catch (e: Exception) { }
    }

    /** همگام‌سازی کامل در نخِ پس‌زمینه: ارسال صف + دریافت گزارش‌های جدید */
    fun syncNow(ctx: Context) {
        val app = ctx.applicationContext
        try { net.execute { try { flushPending(app); refreshRemote(app) } catch (e: Exception) { } } } catch (e: Exception) { }
    }

    /** تعداد گزارش‌های در انتظار ارسال (برای نمایش وضعیت در تنظیمات) */
    fun pendingCount(ctx: Context): Int = try {
        getDb(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_reports", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (e: Exception) { 0 }

    /**
     * ارسال گزارش‌های در صف به سرور. حذف از صف فقط پس از تأیید سرور؛
     * در خطا گزارش در صف می‌ماند و بعداً دوباره تلاش می‌شود (بخش ۱۰ سند جامع).
     */
    private fun flushPending(ctx: Context) {
        if (!RoadPois.serverConfigured(ctx)) return
        val base = RoadPois.serverUrl(ctx).trimEnd('/')
        val device = RoadPois.deviceId(ctx)
        val now = System.currentTimeMillis()
        val db = try { getDb(ctx).writableDatabase } catch (e: Exception) { return }
        val rows = ArrayList<Array<Any>>() // [id: Long, payload: JSONObject, ts: Long]
        try {
            db.rawQuery("SELECT id, type, note, lat, lon, ts FROM pending_reports ORDER BY ts ASC LIMIT 80", null).use { c ->
                while (c.moveToNext()) {
                    rows.add(arrayOf(
                        c.getLong(0),
                        JSONObject()
                            .put("type", c.getString(1))
                            .put("note", c.getString(2) ?: "")
                            .put("lat", c.getDouble(3))
                            .put("lon", c.getDouble(4))
                            .put("device_id", device),
                        c.getLong(5)
                    ))
                }
            }
        } catch (e: Exception) { return }
        for (row in rows) {
            val id = row[0] as Long
            val payload = row[1] as JSONObject
            val ts = row[2] as Long
            // گزارش‌هایی که پیش از ارسال منقضی شده‌اند دیگر ارسال نمی‌شوند
            if (now - ts > EXPIRY_MS) {
                try { db.delete("pending_reports", "id=?", arrayOf(id.toString())) } catch (e: Exception) { }
                continue
            }
            try {
                val body = Http.postJson("$base/api/v1/reports", payload.toString(), readTimeoutMs = 10000)
                if (JSONObject(body).optBoolean("ok", false)) {
                    db.delete("pending_reports", "id=?", arrayOf(id.toString()))
                } else {
                    bumpTries(db, id)
                }
            } catch (e: Exception) { bumpTries(db, id) }
        }
    }

    private fun bumpTries(db: SQLiteDatabase, id: Long) {
        try { db.execSQL("UPDATE pending_reports SET tries = tries + 1 WHERE id=?", arrayOf(id.toString())) } catch (e: Exception) { }
    }

    /** دریافت گزارش‌های زندهٔ سایر کاربران — در خطا بی‌صدا (دادهٔ قبلی می‌ماند) */
    private fun refreshRemote(ctx: Context) {
        if (!RoadPois.serverConfigured(ctx)) return
        val base = RoadPois.serverUrl(ctx).trimEnd('/')
        try {
            val body = Http.get("$base/api/v1/reports?limit=300", readTimeoutMs = 10000)
            val o = JSONObject(body)
            if (!o.optBoolean("ok", false)) return
            val arr = o.optJSONArray("reports") ?: return
            val db = getDb(ctx).writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM remote_reports")
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val lat = r.optDouble("lat", Double.NaN)
                    val lon = r.optDouble("lon", Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    val cv = ContentValues().apply {
                        put("sid", r.optLong("id"))
                        put("type", r.optString("type", ""))
                        put("note", r.optString("note", ""))
                        put("lat", lat); put("lon", lon)
                        put("created_at", r.optLong("created_at"))
                        put("expires_at", r.optLong("expires_at"))
                    }
                    db.insertWithOnConflict("remote_reports", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
                lastRemoteRefreshMs = System.currentTimeMillis()
            } finally { db.endTransaction() }
        } catch (e: Exception) { }
    }

    /** متن اشتراک‌گذاری گزارش — با حفاظت اطلاعات حساس */
    fun shareText(ctx: Context, r: Report): String? {
        if (isSensitive(r.note)) return null
        val link = "https://www.openstreetmap.org/?mlat=${r.lat}&mlon=${r.lon}#map=17/${r.lat}/${r.lon}"
        return "${r.typeLabel(ctx)}\n${r.note.ifEmpty { ctx.getString(R.string.traffic_report) }}\n$link"
    }
}
