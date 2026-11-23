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
 *  - مهاجرت دیتابیس نسخهٔ ۱→۳: فقط «افزودن» جدول/ستون جدید؛ هیچ جدول یا دادهٔ
 *    موجودی حذف، بازسازی یا تغییر نمی‌کند (قانون ۹ سند جامع).
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
    /** انواع گزارش سند جامع بخش ۷ — Worker زنده هر پنج را می‌پذیرد (LIVE_REPORT_TYPES) */
    const val TYPE_MEDIUM_TRAFFIC = "medium_traffic"   // ترافیک متوسط (سند بخش ۷: «ترافیک»)
    const val TYPE_TEMP_BLOCK = "temporary_restriction" // انسداد موقت
    const val TYPE_LANDSLIDE = "landslide"              // ریزش کوه و سنگ
    const val TYPE_FLOOD = "flood"                      // سیلاب و آب‌گرفتگی
    const val TYPE_ROAD_WORKS = "road_works"            // عملیات ساختمانی و تعمیرات

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
        TYPE_BROKEN_TRAFFIC_LIGHT, TYPE_SPEED_LIMIT_CHANGE, TYPE_STEEP_DOWN, TYPE_STEEP_UP,
        TYPE_MEDIUM_TRAFFIC, TYPE_TEMP_BLOCK, TYPE_LANDSLIDE, TYPE_FLOOD, TYPE_ROAD_WORKS
    )

    /** عمر سروری انواع انسدادی/طبیعی — سرور برای این سه ۴۸ ساعت اعتبار می‌دهد (worker/src/index.js) */
    val SERVER_48H_TYPES = setOf(TYPE_ROAD_CLOSED, TYPE_LANDSLIDE, TYPE_FLOOD)

    /** سقف تلاش برای ارسال هر گزارش صف‌شده؛ پس از آن گزارش در صف می‌ماند اما دیگر تلاش نمی‌شود */
    const val MAX_SEND_TRIES = 10

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
        val fromServer: Boolean = false,
        val sid: Long = 0,                       // شناسهٔ سرور (گزارش‌های زندهٔ دیگران)
        val severity: String? = null,            // شدت (بخش ۸): low / medium / high
        val seg: String? = null,                 // شناسهٔ قطعهٔ جاده (بخش ۸ و ۱۰)
        val dirBin: Int? = null,                 // خانهٔ جهت ۰ تا ۷
        val confirm: Int = 0, val deny: Int = 0, // شمار رأی رانندگان (بخش ۹)
        val verification: String? = null         // unverified / confirmed / rejected
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
            TYPE_MEDIUM_TRAFFIC -> ctx.getString(R.string.report_type_medium_traffic)
            TYPE_TEMP_BLOCK -> ctx.getString(R.string.report_type_temp_block)
            TYPE_LANDSLIDE -> ctx.getString(R.string.report_type_landslide)
            TYPE_FLOOD -> ctx.getString(R.string.report_type_flood)
            TYPE_ROAD_WORKS -> ctx.getString(R.string.report_type_roadworks)
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
                TYPE_MEDIUM_TRAFFIC -> "ترافیک متوسط"
                TYPE_TEMP_BLOCK -> "انسداد موقت"
                TYPE_LANDSLIDE -> "ریزش کوه و سنگ"
                TYPE_FLOOD -> "سیلاب و آب‌گرفتگی"
                TYPE_ROAD_WORKS -> "عملیات ساختمانی"
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
            TYPE_MEDIUM_TRAFFIC -> "#F5A623"
            TYPE_TEMP_BLOCK -> "#E0342C"
            TYPE_LANDSLIDE -> "#8D6E63"
            TYPE_FLOOD -> "#0288D1"
            TYPE_ROAD_WORKS -> "#FB8C00"
            else -> "#F5A623"
        }
    }

    /**
     * مهاجرت‌های امن — هرگز جدول یا دادهٔ موجود حذف/بازسازی نمی‌شود:
     *  - نسخهٔ ۲: افزودن صف ارسال (pending_reports) و کش گزارش‌های سرور (remote_reports)
     *    با CREATE TABLE IF NOT EXISTS.
     *  - نسخهٔ ۳: افزودن ستون client_uuid به صف — شناسهٔ یکتای هر عملیات برای
     *    جلوگیری از ثبت تکراری گزارش در سرور (بند ۱۹ سند جامع؛ عملیات idempotent).
     *    فقط ADD COLUMN + پرکردن ردیف‌های قدیمی با UUID تصادفی؛ داده‌ها دست‌نخورده می‌مانند.
     *    بازگشت (rollback): ستون nullable است؛ نسخهٔ قبلی برنامه آن را نادیده می‌گیرد.
     *  - نسخهٔ ۴ (بخش ۸ و ۹): شدت (severity)، شناسهٔ قطعه (seg) و خانهٔ جهت (dir)
     *    برای گزارش‌های صف‌شده + شمار رأی/وضعیت تأیید برای گزارش‌های دریافتی.
     *    همه فقط ADD COLUMN با nullable؛ نسخهٔ قبلی برنامه آن‌ها را نادیده می‌گیرد.
     */
    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "reports.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS reports(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, note TEXT, lat REAL NOT NULL, lon REAL NOT NULL, ts INTEGER NOT NULL)")
            createV2(db)
            createV3(db)
            createV4(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createV2(db)
            if (oldVersion < 3) createV3(db)
            if (oldVersion < 4) createV4(db)
        }
        private fun createV2(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_reports(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, note TEXT, lat REAL NOT NULL, lon REAL NOT NULL, ts INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS remote_reports(sid INTEGER PRIMARY KEY, type TEXT NOT NULL, note TEXT, lat REAL NOT NULL, lon REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
        }
        private fun createV3(db: SQLiteDatabase) {
            // اگر ستون از قبل موجود باشد (نصب پیشین نسخهٔ ۳) خطای SQLite گرفته و نادیده می‌شود
            try {
                db.execSQL("ALTER TABLE pending_reports ADD COLUMN client_uuid TEXT")
            } catch (e: Exception) { }
            // ردیف‌های قدیمی (بدون شناسه) یک UUID پایدار می‌گیرند تا دوباره‌فرستانی هم تکراری نسازد
            try {
                db.execSQL("UPDATE pending_reports SET client_uuid = lower(hex(randomblob(16))) WHERE client_uuid IS NULL")
            } catch (e: Exception) { }
        }
        private fun createV4(db: SQLiteDatabase) {
            // بخش ۸ و ۹ سند جامع — همهٔ تغییرها فقط «افزودن» و بازگشت‌پذیر
            for (sql in arrayOf(
                "ALTER TABLE pending_reports ADD COLUMN severity TEXT",
                "ALTER TABLE pending_reports ADD COLUMN seg TEXT",
                "ALTER TABLE pending_reports ADD COLUMN dir INTEGER",
                "ALTER TABLE remote_reports ADD COLUMN severity TEXT",
                "ALTER TABLE remote_reports ADD COLUMN seg TEXT",
                "ALTER TABLE remote_reports ADD COLUMN dir INTEGER",
                "ALTER TABLE remote_reports ADD COLUMN confirm INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE remote_reports ADD COLUMN deny INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE remote_reports ADD COLUMN verification TEXT"
            )) {
                try { db.execSQL(sql) } catch (e: Exception) { }
            }
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
        TYPE_TOLL, TYPE_BORDER, TYPE_BRIDGE, TYPE_TUNNEL, TYPE_TRAFFIC_LIGHT, TYPE_SURVEILLANCE,
        TYPE_MEDIUM_TRAFFIC, TYPE_TEMP_BLOCK, TYPE_LANDSLIDE, TYPE_FLOOD, TYPE_ROAD_WORKS
    )

    /** آیا متن شامل اطلاعات حساس امنیتی است؟ (چنین گزارشی فقط محلی ذخیره می‌شود) */
    fun isSensitive(text: String): Boolean {
        val n = text.lowercase()
        return SENSITIVE_PATTERNS.any { n.contains(it) }
    }

    /**
     * ثبت گزارش؛ خروجی: id یا خطا. هم‌زمان در صف سرور هم قرار می‌گیرد.
     *  @param severity شدت حادثه (بخش ۸) — low/medium/high یا null
     *  @param bearing جهت حرکت هنگام ثبت (بخش ۸ و ۱۵) — اگر معلوم باشد شناسهٔ قطعه
     *                 ساخته می‌شود تا هشدارهای مسیرمحور جهت‌دار شوند
     */
    fun add(
        ctx: Context, type: String, note: String, lat: Double, lon: Double,
        severity: String? = null, bearing: Double? = null
    ): Long {
        require(validType(type)) { ctx.getString(R.string.report_err_bad_type) }
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { ctx.getString(R.string.report_err_bad_coords) }
        require(note.length <= 300) { ctx.getString(R.string.report_err_long_note) }
        require(severity == null || severity in listOf("low", "medium", "high")) {
            ctx.getString(R.string.report_err_bad_type)
        }
        val ts = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("type", type); put("note", note); put("lat", lat); put("lon", lon); put("ts", ts)
        }
        val id = getDb(ctx).writableDatabase.insert("reports", null, cv)
        // ارسال به سیستم گزارش زندهٔ سراسری — گزارش حساس هرگز از گوشی خارج نمی‌شود
        if (type in SERVER_TYPES && !isSensitive(note) && RoadPois.serverConfigured(ctx)) {
            // شناسهٔ قطعهٔ شبکه‌ای — فقط وقتی جهت حرکت معلوم است (بخش ۸)
            val seg = bearing?.let { RoadSegments.cellId(lat, lon, it) }
            val dirBin = bearing?.let { RoadSegments.bearingBin(it) }
            try {
                getDb(ctx).writableDatabase.insert("pending_reports", null, ContentValues().apply {
                    put("type", type); put("note", note); put("lat", lat); put("lon", lon); put("ts", ts)
                    put("client_uuid", java.util.UUID.randomUUID().toString()) // شناسهٔ یکتای عملیات (بند ۱۹)
                    if (severity != null) put("severity", severity)
                    if (seg != null) put("seg", seg)
                    if (dirBin != null) put("dir", dirBin)
                })
            } catch (e: Exception) { }
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
                "SELECT sid, type, note, lat, lon, created_at, severity, seg, dir, confirm, deny, verification FROM remote_reports WHERE expires_at > ? LIMIT 300",
                arrayOf(now.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val sid = c.getLong(0); val type = c.getString(1)
                    val note = c.getString(2) ?: ""
                    val lat = c.getDouble(3); val lon = c.getDouble(4); val ts = c.getLong(5)
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    // انقضا با «expires_at» سرور حاکم است؛ برای انواع انسدادی/طبیعی
                    // سرور ۴۸ ساعت اعتبار می‌دهد و فیلتر ثابت ۲۴ ساعتهٔ محلی آن‌ها را نمی‌کُشد
                    if (type in SERVER_48H_TYPES && now - ts > 2 * EXPIRY_MS) continue
                    if (nearLat != null && nearLon != null) {
                        val d = Geo.dist(nearLat, nearLon, lat, lon) / 1000.0
                        if (d > maxKm) continue
                    }
                    if (list.any { it.type == type && Geo.dist(it.lat, it.lon, lat, lon) < 150.0 && Math.abs(it.ts - ts) < 45 * 60 * 1000L }) continue
                    val severity = if (c.isNull(6)) null else c.getString(6)
                    val seg = if (c.isNull(7)) null else c.getString(7)
                    val dirBin = if (c.isNull(8)) null else c.getInt(8)
                    val verification = if (c.isNull(11)) null else c.getString(11)
                    list.add(Report(
                        -sid, type, note, lat, lon, ts, fromServer = true,
                        sid = sid, severity = severity, seg = seg, dirBin = dirBin,
                        confirm = c.getInt(9), deny = c.getInt(10), verification = verification
                    ))
                }
            }
        } catch (e: Exception) { }
        return list
    }

    /**
     * تأیید/رد گزارش زندهٔ دیگران توسط راننده — بخش ۹ سند جامع.
     * رأی در سرور ثبت می‌شود؛ ۲ تأیید = تأییدشده، ۳ رد = کنار گذاشته.
     * پس از رأی، کش گزارش‌ها دوباره تازه‌سازی می‌شود.
     */
    fun verifyReport(ctx: Context, sid: Long, vote: String, onDone: ((Boolean) -> Unit)? = null) {
        require(vote == "confirm" || vote == "deny")
        val app = ctx.applicationContext
        try {
            net.execute {
                var ok = false
                try {
                    if (RoadPois.serverConfigured(app)) {
                        val base = RoadPois.serverUrl(app).trimEnd('/')
                        val body = Http.postJson(
                            "$base/api/v1/reports/$sid/verify",
                            JSONObject()
                                .put("device_id", RoadPois.deviceId(app))
                                .put("vote", vote).toString(),
                            readTimeoutMs = 10000
                        )
                        ok = JSONObject(body).optBoolean("ok", false)
                        if (ok) refreshRemote(app)
                    }
                } catch (e: Exception) { }
                onDone?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(ok) } }
            }
        } catch (e: Exception) { onDone?.invoke(false) }
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

    /**
     * همگام‌سازی روی نخِ فراخواننده — فقط برای ReportsSyncWorker (WorkManager)
     * که خودش روی نخ پس‌زمینه اجرا می‌شود (بند ۱۹ سند جامع).
     */
    fun syncBlocking(ctx: Context) {
        val app = ctx.applicationContext
        try { flushPending(app); refreshRemote(app) } catch (e: Exception) { }
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
            // سقف تلاش: ردیف‌های بیش از MAX_SEND_TRIES بار کوشیده موقتاً کنار گذاشته
            // می‌شوند تا یک خطای دائمی، کل صف را بلوکه نکند (داده در جدول reports محفوظ است)
            db.rawQuery("SELECT id, type, note, lat, lon, ts, client_uuid, severity, seg, dir FROM pending_reports WHERE tries < ? ORDER BY ts ASC LIMIT 80", arrayOf(MAX_SEND_TRIES.toString())).use { c ->
                while (c.moveToNext()) {
                    // ردیف‌های قدیمیِ بدون شناسه: تولید و ثبت پایدار UUID پیش از ارسال —
                    // در تلاش‌های بعدی همین UUID دوباره فرستاده می‌شود (idempotent)
                    var uuid = if (c.isNull(6)) null else c.getString(6)
                    if (uuid == null || uuid.length < 8) {
                        uuid = java.util.UUID.randomUUID().toString()
                        try { db.execSQL("UPDATE pending_reports SET client_uuid=? WHERE id=?", arrayOf(uuid, c.getLong(0).toString())) } catch (e: Exception) { }
                    }
                    val payload = JSONObject()
                        .put("type", c.getString(1))
                        .put("note", c.getString(2) ?: "")
                        .put("lat", c.getDouble(3))
                        .put("lon", c.getDouble(4))
                        .put("device_id", device)
                        .put("client_uuid", uuid)
                    // بخش ۸: شدت + قطعهٔ جاده + جهت — اگر موقع ثبت معلوم بود
                    if (!c.isNull(7)) payload.put("severity", c.getString(7))
                    if (!c.isNull(8)) payload.put("seg", c.getString(8))
                    if (!c.isNull(9)) payload.put("dir", c.getInt(9))
                    rows.add(arrayOf(c.getLong(0), payload, c.getLong(5)))
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
                val o = JSONObject(body)
                // ok=true حتی وقتی duplicate=true — یعنی سرور پیش‌تر همین گزارش را با همین
                // شناسهٔ یکتا پذیرفته است؛ عملیات موفق است و از صف حذف می‌شود (بند ۱۹)
                if (o.optBoolean("ok", false)) {
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
                        // بخش ۸ و ۹: شدت/قطعه/جهت + شمار رأی و وضعیت تأیید
                        if (!r.isNull("severity")) put("severity", r.optString("severity"))
                        if (!r.isNull("seg")) put("seg", r.optString("seg"))
                        if (!r.isNull("dir")) put("dir", r.optInt("dir"))
                        put("confirm", r.optInt("confirm_count", 0))
                        put("deny", r.optInt("deny_count", 0))
                        if (!r.isNull("verification")) put("verification", r.optString("verification"))
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
