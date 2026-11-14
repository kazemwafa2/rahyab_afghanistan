package af.rahyab.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * گزارش حوادث جاده‌ای — بخش هشتم چک‌لیست.
 *
 * انواع گزارش: حادثه (تصادف)، خرابی جاده، بسته‌بودن جاده، ترافیک سنگین، خطر جاده‌ای.
 *  - ذخیره فقط روی خود گوشی (SQLite) + اشتراک‌گذاری اختیاری به‌صورت متن/لینک.
 *  - انقضای خودکار: گزارش‌های بیشتر از ۲۴ ساعت به‌طور خودکار نامعتبر می‌شوند.
 *  - اعتبارسنجی: نوع باید معتبر و یادداشت کوتاه‌تر از ۳۰۰ نویسه باشد.
 *  - حفاظت اطلاعات حساس (قانون ۸ چک‌لیست): هر یادداشتی که درباره ایست بازرسی
 *    (چک‌پست)، موقعیت نیروهای امنیتی/نظامی یا پایگاه‌ها باشد، برای اشتراک‌گذاری
 *    یا ارسال به سرور مسدود می‌شود — فقط روی همین گوشی می‌ماند.
 *  - همگام‌سازی با سرور اختیاری است و فقط اگر کاربر نشانی سرور را ثبت کند.
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

    /** واژه‌های ممنوع برای انتشار — حفاظت نیروهای امنیتی و ایست‌های بازرسی */
    val SENSITIVE_PATTERNS = listOf(
        "چک پوست", "چک‌پوست", "چک پست", "چک‌پست", "چک پوینت", "چک‌پوینت",
        "checkpoint", "check post", "checkpost", "بازرسی", "ایست بازرسی",
        "پوسته", "پوسته امنیتی", "نیروهای امنیتی", "پایگاه", "اردوگاه",
        "نظامی", "زره‌پوش", "تانک جنگی", "هوایی دولت", "سرباز", "تسلیم‌گاه"
    )

    class Report(
        val id: Long, val type: String, val note: String,
        val lat: Double, val lon: Double, val ts: Long
    ) {
        val expired: Boolean get() = System.currentTimeMillis() - ts > EXPIRY_MS

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

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "reports.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE reports(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, note TEXT, lat REAL NOT NULL, lon REAL NOT NULL, ts INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }
    }

    private val lock = Any()
    private var db: Db? = null

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

    /** ثبت گزارش؛ خروجی: id یا خطا */
    fun add(ctx: Context, type: String, note: String, lat: Double, lon: Double): Long {
        require(validType(type)) { "نوع گزارش نامعتبر است" }
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "مختصات نامعتبر" }
        require(note.length <= 300) { "یادداشت بلندتر از ۳۰۰ نویسه" }
        val cv = ContentValues().apply {
            put("type", type); put("note", note); put("lat", lat); put("lon", lon)
            put("ts", System.currentTimeMillis())
        }
        return getDb(ctx).writableDatabase.insert("reports", null, cv)
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
        return list
    }

    /** پاک‌سازی گزارش‌های منقضی (هر بار باز شدن صفحه گزارش‌ها) */
    fun purgeExpired(ctx: Context) {
        try {
            getDb(ctx).writableDatabase.delete("reports", "ts < ?", arrayOf((System.currentTimeMillis() - EXPIRY_MS).toString()))
        } catch (e: Exception) { }
    }

    /** متن اشتراک‌گذاری گزارش — با حفاظت اطلاعات حساس */
    fun shareText(ctx: Context, r: Report): String? {
        if (isSensitive(r.note)) return null
        val link = "https://www.openstreetmap.org/?mlat=${r.lat}&mlon=${r.lon}#map=17/${r.lat}/${r.lon}"
        return "${r.typeLabel}\n${r.note.ifEmpty { ctx.getString(R.string.traffic_report) }}\n$link"
    }
}
