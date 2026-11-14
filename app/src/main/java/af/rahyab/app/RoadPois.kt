package af.rahyab.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/**
 * ضمیمهٔ ۱ — سیستم جامع اطلاعات جاده‌ای راه‌یاب افغانستان
 * (Afghanistan Road Intelligence & Points of Interest)
 *
 * این ماژول:
 *  - فهرست کامل دسته‌بندی‌های جاده‌ای (دوربین‌ها، موانع، پل/تونل، پولیس، عوارض/مرز،
 *    سوخت، ترمیم، درمانی، رفاهی) را نگه می‌دارد — بخش اول تا چهارم ضمیمه.
 *  - کش محلی SQLite برای کارکرد کامل آفلاین (بخش دوازدهم).
 *  - اتصال به سرور Cloudflare Worker + D1 (بخش دهم) با صفِ ارسال برای حالت بی‌انترنت.
 *  - تبدیل مکان‌های ثابت به هشدارهای مسیر برای NavigationController (بخش هشتم).
 *
 * قواعد صحت‌سنجی (بخش یازدهم):
 *  - مکان تازه همیشه «تأییدنشده» است؛ فقط منبع رسمی «official» می‌ماند.
 *  - سرعت مجاز فقط وقتی قطعی نمایش داده می‌شود که منبع رسمی داشته باشد.
 *  - مکان تکراری (همان دسته در ۳۵ متری) ثبت نمی‌شود.
 */
object RoadPois {

    // ---------- دسته‌بندی‌ها (بخش اول تا چهارم) ----------

    data class Category(
        val id: String,          // شناسهٔ یکسان با سرور (worker/src/index.js)
        val group: String,       // گروه برای فیلتر نمایش
        val color: String,       // رنگ نقطه روی نقشه
        val alertType: String?,  // نوع هشدار صوتی (ReportsModule type) یا null
        val equipment: Boolean = false // آیا در جدول road_equipment هم ثبت می‌شود؟
    )

    /** گروه‌های اصلی برای صفحه فیلتر (قابل فعال/غیرفعان‌کردن — بخش هشتم) */
    val GROUPS = listOf(
        "cameras", "obstacles", "bridges", "police", "toll", "fuel", "repair", "medical", "welfare"
    )

    val CATEGORIES: List<Category> = listOf(
        // بخش اول: دوربین‌ها و تجهیزات نظارتی
        Category("speed_camera", "cameras", "#E0342C", ReportsModule.TYPE_SPEED_CAMERA, true),
        Category("surveillance_camera", "cameras", "#7E57C2", ReportsModule.TYPE_SURVEILLANCE, true),
        Category("avg_speed_check", "cameras", "#E0342C", ReportsModule.TYPE_SPEED_CAMERA, true),
        Category("smart_speed_sign", "cameras", "#26A69A", ReportsModule.TYPE_SPEED_LIMIT_CHANGE, true),
        Category("speed_warning_display", "cameras", "#26A69A", null, true),
        Category("vehicle_counter", "cameras", "#607D8B", null, true),
        Category("traffic_sensor", "cameras", "#607D8B", null, true),
        // بخش دوم: دست‌اندازها و موانع
        Category("bump", "obstacles", "#F5A623", ReportsModule.TYPE_SPEED_BUMP),
        Category("speed_bump_nonstandard", "obstacles", "#F5A623", ReportsModule.TYPE_SPEED_BUMP),
        Category("bump_damaged", "obstacles", "#EF6C00", ReportsModule.TYPE_SPEED_BUMP),
        Category("bump_unmarked", "obstacles", "#EF6C00", ReportsModule.TYPE_SPEED_BUMP),
        Category("guardrail", "obstacles", "#8D6E63", null),
        Category("guardrail_damaged", "obstacles", "#EF6C00", ReportsModule.TYPE_HAZARD),
        Category("sign_warning", "obstacles", "#26A69A", ReportsModule.TYPE_HAZARD),
        Category("sign_speed_limit", "obstacles", "#26A69A", ReportsModule.TYPE_SPEED_LIMIT_CHANGE),
        Category("sign_danger", "obstacles", "#EF6C00", ReportsModule.TYPE_HAZARD),
        Category("sign_oneway", "obstacles", "#607D8B", null),
        Category("traffic_light", "obstacles", "#E0342C", ReportsModule.TYPE_TRAFFIC_LIGHT),
        Category("warning_light", "obstacles", "#E0342C", null),
        Category("crosswalk", "obstacles", "#2A6FB4", ReportsModule.TYPE_CROSSWALK),
        Category("school_line", "obstacles", "#2A6FB4", ReportsModule.TYPE_SCHOOL_ZONE),
        Category("roundabout", "obstacles", "#607D8B", null),
        Category("junction", "obstacles", "#607D8B", null),
        // بخش دوم-۳: پل، تونل، زیرگذر، روگذر
        Category("bridge", "bridges", "#5C6BC0", ReportsModule.TYPE_BRIDGE),
        Category("bridge_highway", "bridges", "#5C6BC0", ReportsModule.TYPE_BRIDGE),
        Category("bridge_pedestrian", "bridges", "#5C6BC0", null),
        Category("tunnel", "bridges", "#455A64", ReportsModule.TYPE_TUNNEL),
        Category("underpass", "bridges", "#455A64", ReportsModule.TYPE_TUNNEL),
        Category("overpass", "bridges", "#455A64", null),
        // بخش سوم: پولیس، کنترل رسمی
        Category("police_station", "police", "#2A6FB4", ReportsModule.TYPE_POLICE),
        Category("police_district", "police", "#2A6FB4", null),
        Category("traffic_police", "police", "#2A6FB4", ReportsModule.TYPE_POLICE),
        Category("accident_center", "police", "#2A6FB4", null),
        Category("emergency_center", "police", "#2A6FB4", null),
        Category("vehicle_check_center", "police", "#607D8B", null),
        Category("document_check_center", "police", "#607D8B", null),
        Category("weight_control", "police", "#607D8B", null),
        Category("weighbridge", "police", "#607D8B", null),
        Category("technical_inspection", "police", "#607D8B", null),
        Category("traffic_service_center", "police", "#607D8B", null),
        // بخش سوم-۳: عوارض و حق‌السیر
        Category("toll_plaza", "toll", "#C62828", ReportsModule.TYPE_TOLL),
        Category("fare_point", "toll", "#C62828", ReportsModule.TYPE_TOLL),
        // بخش سوم-۴: مرز و گمرک
        Category("border_crossing", "toll", "#AD1457", ReportsModule.TYPE_BORDER),
        Category("customs", "toll", "#AD1457", ReportsModule.TYPE_BORDER),
        Category("cargo_clearance", "toll", "#AD1457", null),
        // بخش چهارم: سوخت و انرژی
        Category("fuel_station", "fuel", "#F9A825", null),
        Category("diesel_station", "fuel", "#F9A825", null),
        Category("gas_station", "fuel", "#F9A825", null),
        Category("ev_charger", "fuel", "#2E7D32", null),
        // بخش چهارم: ترمیم و نگهداری
        Category("car_repair", "repair", "#8E24AA", null),
        Category("motorcycle_repair", "repair", "#8E24AA", null),
        Category("tire_shop", "repair", "#8E24AA", null),
        Category("battery_service", "repair", "#8E24AA", null),
        Category("parts_store", "repair", "#8E24AA", null),
        Category("tow_truck", "repair", "#8E24AA", null),
        Category("roadside_assist", "repair", "#8E24AA", null),
        Category("car_wash", "repair", "#8E24AA", null),
        Category("oil_change", "repair", "#8E24AA", null),
        // بخش چهارم: اضطراری و درمانی
        Category("hospital", "medical", "#E0342C", null),
        Category("clinic", "medical", "#E0342C", null),
        Category("emergency_medical", "medical", "#E0342C", null),
        Category("ambulance_station", "medical", "#E0342C", null),
        Category("pharmacy", "medical", "#D81B60", null),
        Category("fire_station", "medical", "#E0342C", null),
        Category("rescue_center", "medical", "#E0342C", null),
        Category("first_aid", "medical", "#E0342C", null),
        // بخش چهارم: رفاهی
        Category("parking", "welfare", "#2E7D32", null),
        Category("truck_parking", "welfare", "#2E7D32", null),
        Category("rest_area", "welfare", "#2E7D32", null),
        Category("guesthouse", "welfare", "#00897B", null),
        Category("hotel", "welfare", "#00897B", null),
        Category("restaurant", "welfare", "#00897B", null),
        Category("mosque", "welfare", "#6A1B9A", null),
        Category("public_toilet", "welfare", "#607D8B", null),
        Category("market", "welfare", "#2E7D32", null),
        Category("bus_stop", "welfare", "#1E88E5", null),
        Category("terminal", "welfare", "#1E88E5", null),
    )

    private val catById = CATEGORIES.associateBy { it.id }
    fun category(id: String): Category? = catById[id]
    fun isKnownCategory(id: String): Boolean = catById.containsKey(id)

    // ---------- مدل مکان ----------

    data class Poi(
        val id: Long,
        val name: String,
        val category: String,
        val subcategory: String?,
        val lat: Double,
        val lon: Double,
        val province: String?,
        val district: String?,
        val roadName: String?,
        val direction: String?,
        val description: String?,
        val properties: JSONObject?,
        val verificationStatus: String,   // unverified / user_verified / official
        val source: String,               // user / osm / official
        val updatedAt: Long,
        val lastVerifiedAt: Long?,
        val active: Boolean,
        val distanceM: Int = -1
    ) {
        val cat: Category? get() = category(category)
        /** نمایش قطعی سرعت مجاز فقط برای منبع رسمی تأییدشده (بخش یازدهم) */
        val confirmedSpeedLimit: Int?
            get() = if (verificationStatus == "official" || verificationStatus == "user_verified")
                properties?.optInt("speed_limit", -1)?.takeIf { it > 0 } else null
    }

    // ---------- پایگاه‌داده محلی (آفلاین) ----------

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "roadpois.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE pois(id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL," +
                    "subcategory TEXT, lat REAL NOT NULL, lon REAL NOT NULL, province TEXT, district TEXT," +
                    "road_name TEXT, direction TEXT, description TEXT, properties TEXT," +
                    "verification_status TEXT NOT NULL DEFAULT 'unverified', source TEXT NOT NULL DEFAULT 'user'," +
                    "created_at INTEGER, updated_at INTEGER, last_verified_at INTEGER, is_active INTEGER NOT NULL DEFAULT 1)"
            )
            db.execSQL("CREATE INDEX idx_pois_cat ON pois(category)")
            db.execSQL("CREATE INDEX idx_pois_coord ON pois(lat, lon)")
            db.execSQL(
                "CREATE TABLE pending_ops(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL," +
                    "payload TEXT NOT NULL, created_at INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }
    }

    private val lock = Any()
    private var db: Db? = null
    private fun getDb(ctx: Context): Db = synchronized(lock) {
        if (db == null) db = Db(ctx.applicationContext)
        db!!
    }

    // ---------- شناسه دستگاه (برای رأی و گزارش — بدون اطلاعات شخصی) ----------

    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = p.getString("device_id", null)
        if (saved != null) return saved
        val id = UUID.randomUUID().toString()
        p.edit().putString("device_id", id).apply()
        return id
    }

    // ---------- تنظیمات سرور (بخش دوازدهم — کنترل مصرف) ----------

    /** نشانی سرور Worker؛ کاربر در تنظیمات پیشرفته می‌تواند عوضش کند */
    fun serverUrl(ctx: Context): String =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("poi_api_base", Config.POI_API_BASE)?.trim() ?: Config.POI_API_BASE

    fun setServerUrl(ctx: Context, v: String) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("poi_api_base", v.trim()).apply()
    }

    /** سرور تنظیم شده؟ — نشانی جای‌نگهدار و خالی هرگز «تنظیم‌شده» شمرده نمی‌شود */
    fun serverConfigured(ctx: Context): Boolean {
        val u = serverUrl(ctx)
        return u.startsWith("http") && !u.contains("YOUR-ACCOUNT") && !u.isBlank()
    }

    /** لایه نقشه فعال است؟ */
    fun layerVisible(ctx: Context): Boolean =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("poi_layer", true)

    fun setLayerVisible(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("poi_layer", v).apply()
    }

    /** گروه‌های پنهان‌شده توسط کاربر (بخش هشتم: امکان مخفی‌کردن دسته‌های غیرضروری) */
    fun hiddenGroups(ctx: Context): Set<String> =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getStringSet("poi_hidden_groups", emptySet()) ?: emptySet()

    fun setHiddenGroups(ctx: Context, hidden: Set<String>) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putStringSet("poi_hidden_groups", hidden).apply()
    }

    // ---------- کش و پرس‌وجو ----------

    private fun poiFromCursor(c: android.database.Cursor): Poi {
        val props = c.getString(11)
        return Poi(
            id = c.getLong(0), name = c.getString(1) ?: "", category = c.getString(2) ?: "",
            subcategory = c.getString(3), lat = c.getDouble(4), lon = c.getDouble(5),
            province = c.getString(6), district = c.getString(7), roadName = c.getString(8),
            direction = c.getString(9), description = c.getString(10),
            properties = if (!props.isNullOrEmpty()) try { JSONObject(props) } catch (e: Exception) { null } else null,
            verificationStatus = c.getString(12) ?: "unverified", source = c.getString(13) ?: "user",
            updatedAt = c.getLong(14), lastVerifiedAt = if (c.isNull(15)) null else c.getLong(15),
            active = c.getInt(16) == 1
        )
    }

    private val POI_COLS = "id, name, category, subcategory, lat, lon, province, district, road_name, direction, description, properties, verification_status, source, updated_at, last_verified_at, is_active"

    private fun upsert(ctx: Context, p: JSONObject) {
        val id = p.optLong("id", -1)
        if (id <= 0) return
        val cv = ContentValues().apply {
            put("id", id)
            put("name", p.optString("name", ""))
            put("category", p.optString("category", ""))
            put("subcategory", p.optString("subcategory", null as String?))
            put("lat", p.optDouble("latitude", Double.NaN))
            put("lon", p.optDouble("longitude", Double.NaN))
            put("province", p.optString("province", null as String?))
            put("district", p.optString("district", null as String?))
            put("road_name", p.optString("road_name", null as String?))
            put("direction", p.optString("direction", null as String?))
            put("description", p.optString("description", null as String?))
            put("properties", p.optString("properties_json", null as String?))
            put("verification_status", p.optString("verification_status", "unverified"))
            put("source", p.optString("source", "user"))
            put("created_at", p.optLong("created_at", 0))
            put("updated_at", p.optLong("updated_at", 0))
            put("last_verified_at", if (p.isNull("last_verified_at") || !p.has("last_verified_at")) null else p.optLong("last_verified_at"))
            put("is_active", if (p.optInt("is_active", 1) == 0) 0 else 1)
        }
        if (cv.getAsDouble("lat").isNaN()) return
        getDb(ctx).writableDatabase.insertWithOnConflict("pois", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** ذخیره پاسخ سرور در کش */
    fun cacheResponse(ctx: Context, arr: org.json.JSONArray) {
        try {
            val db = getDb(ctx).writableDatabase
            db.beginTransaction()
            try {
                for (i in 0 until arr.length()) upsert(ctx, arr.getJSONObject(i))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (e: Exception) { }
    }

    /** همه نقاط معتبر در محدوده نقشه — برای لایه نقشه */
    fun visibleInBbox(ctx: Context, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Poi> {
        val hidden = hiddenGroups(ctx)
        val out = ArrayList<Poi>()
        try {
            getDb(ctx).readableDatabase.rawQuery(
                "SELECT $POI_COLS FROM pois WHERE is_active=1 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 800",
                arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val p = poiFromCursor(c)
                    if (p.cat == null || p.cat!!.group in hidden) continue
                    out.add(p)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun nearbyCached(ctx: Context, lat: Double, lon: Double, radiusM: Double, category: String? = null): List<Poi> {
        val d = radiusM / 111320.0
        val raw = visibleInBbox(ctx, lat - d, lon - d, lat + d, lon + d)
        return raw.map { it.copy(distanceM = Geo.dist(lat, lon, it.lat, it.lon).toInt()) }
            .filter { it.distanceM <= radiusM && (category == null || it.category == category) }
            .sortedBy { it.distanceM }
    }

    /** جست‌وجوی محلی آفلاین (بخش نهم) */
    fun searchCached(ctx: Context, q: String, limit: Int = 40): List<Poi> {
        if (q.length < 2) return emptyList()
        val out = ArrayList<Poi>()
        val hidden = hiddenGroups(ctx)
        try {
            getDb(ctx).readableDatabase.rawQuery(
                "SELECT $POI_COLS FROM pois WHERE is_active=1 AND (name LIKE ? OR description LIKE ? OR road_name LIKE ? OR province LIKE ? OR district LIKE ?) LIMIT ?",
                arrayOf("%$q%", "%$q%", "%$q%", "%$q%", "%$q%", (limit * 3).toString())
            ).use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val p = poiFromCursor(c)
                    if (p.cat == null || p.cat!!.group in hidden) continue
                    out.add(p)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun byId(ctx: Context, id: Long): Poi? {
        try {
            getDb(ctx).readableDatabase.rawQuery(
                "SELECT $POI_COLS FROM pois WHERE id=?", arrayOf(id.toString())
            ).use { c -> if (c.moveToFirst()) return poiFromCursor(c) }
        } catch (e: Exception) { }
        return null
    }

    fun localCount(ctx: Context): Int = try {
        getDb(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM pois WHERE is_active=1", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (e: Exception) { 0 }

    /** نقاط کنار مسیر — از کش محلی (بدون شبکه) با فاصله تا پاره‌خط‌های مسیر */
    fun alongRouteCached(ctx: Context, points: List<Pair<Double, Double>>, corridorM: Double = 300.0): List<Poi> {
        if (points.size < 2) return emptyList()
        val minLat = points.minOf { it.first } - corridorM / 111320.0
        val maxLat = points.maxOf { it.first } + corridorM / 111320.0
        val cosLat = Math.cos(Math.toRadians(points[0].first)).coerceAtLeast(0.2)
        val minLon = points.minOf { it.second } - corridorM / (111320.0 * cosLat)
        val maxLon = points.maxOf { it.second } + corridorM / (111320.0 * cosLat)
        val cands = visibleInBbox(ctx, minLat, minLon, maxLat, maxLon)
        return cands.mapNotNull { p ->
            var best = Double.MAX_VALUE
            for (i in 0 until points.size - 1) {
                val d = distToSegment(p.lat, p.lon, points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
                if (d < best) best = d
            }
            if (best <= corridorM) p.copy(distanceM = best.toInt()) else null
        }.sortedBy { it.distanceM }
    }

    private fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) return Geo.dist(px, py, ax, ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / l2
        t = t.coerceIn(0.0, 1.0)
        return Geo.dist(px, py, ax + t * dx, ay + t * dy)
    }

    // ---------- هشدارهای مسیر (بخش هشتم — اتصال به NavigationController) ----------

    /**
     * نقاط مکان‌های ثابت قابل هشدار در اطراف موقعیت فعلی.
     * فقط دسته‌هایی که alertType دارند و کاربر فعال کرده، برمی‌گردند.
     */
    fun alertPoints(ctx: Context, lat: Double, lon: Double, radiusM: Double = 1200.0): List<AlertEngine.ReportPt> {
        val out = ArrayList<AlertEngine.ReportPt>()
        try {
            val pois = nearbyCached(ctx, lat, lon, radiusM)
            val cfgP = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            for (p in pois) {
                val t = p.cat?.alertType ?: continue
                if (!AlertEngine.enabledType(cfgP, t)) continue
                out.add(AlertEngine.ReportPt(p.id, t, p.lat, p.lon))
            }
        } catch (e: Exception) { }
        return out
    }

    // ---------- شبکه: دریافت و همگام‌سازی (بخش دهم و دوازدهم) ----------

    /** دریافت مکان‌های نزدیک از سرور و ذخیره در کش. شکست شبکه بی‌صدا است (آفلاین می‌مانیم). */
    fun refreshAround(ctx: Context, lat: Double, lon: Double, radiusM: Int = 4000): Boolean {
        val base = serverUrl(ctx)
        if (!base.startsWith("http")) return false
        return try {
            val body = Http.get("$base/api/v1/pois/nearby?lat=$lat&lon=$lon&radius=$radiusM&limit=400", readTimeoutMs = 9000)
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("pois") ?: return false
            cacheResponse(ctx, arr)
            putMeta(ctx, "last_sync", System.currentTimeMillis().toString())
            true
        } catch (e: Exception) { false }
    }

    /** همگام‌سازی فقط تغییرهای جدید (since) — مصرف کم D1 */
    fun syncDeltas(ctx: Context): Boolean {
        val base = serverUrl(ctx)
        if (!base.startsWith("http")) return false
        val since = getMeta(ctx, "last_sync") ?: "0"
        return try {
            val body = Http.get("$base/api/v1/pois?since=$since&limit=1000", readTimeoutMs = 10000)
            val arr = JSONObject(body).optJSONArray("pois") ?: return false
            cacheResponse(ctx, arr)
            putMeta(ctx, "last_sync", System.currentTimeMillis().toString())
            true
        } catch (e: Exception) { false }
    }

    /** جست‌وجوی سرور — نتایج هم در کش ذخیره می‌شوند تا آفلاین باقی بمانند */
    fun searchRemote(ctx: Context, q: String): List<Poi> {
        val base = serverUrl(ctx)
        if (!base.startsWith("http") || q.length < 2) return emptyList()
        return try {
            val body = Http.get("$base/api/v1/pois/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=40", readTimeoutMs = 9000)
            val arr = JSONObject(body).optJSONArray("pois") ?: return emptyList()
            cacheResponse(ctx, arr)
            (0 until arr.length()).mapNotNull { poiFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    private fun poiFromJson(o: JSONObject): Poi? {
        val id = o.optLong("id", -1)
        val lat = o.optDouble("latitude", Double.NaN)
        val lon = o.optDouble("longitude", Double.NaN)
        if (id <= 0 || lat.isNaN() || lon.isNaN()) return null
        val props = o.optString("properties_json", null as String?)
        return Poi(
            id = id, name = o.optString("name", ""), category = o.optString("category", ""),
            subcategory = o.optString("subcategory", null as String?), lat = lat, lon = lon,
            province = o.optString("province", null as String?), district = o.optString("district", null as String?),
            roadName = o.optString("road_name", null as String?), direction = o.optString("direction", null as String?),
            description = o.optString("description", null as String?),
            properties = if (!props.isNullOrEmpty()) try { JSONObject(props) } catch (e: Exception) { null } else null,
            verificationStatus = o.optString("verification_status", "unverified"),
            source = o.optString("source", "user"), updatedAt = o.optLong("updated_at", 0),
            lastVerifiedAt = if (o.isNull("last_verified_at")) null else o.optLong("last_verified_at"),
            active = o.optInt("is_active", 1) == 1
        )
    }

    // ---------- ثبت توسط کاربر (بخش هفتم) ----------

    /**
     * ثبت مکان جدید: اگر انترنت هست مستقیم؛ وگرنه در صف می‌ماند تا همگام‌سازی.
     * جلوگیری از تکرار: همان دسته در ۳۵ متری در کش → همان id برگردانده می‌شود.
     * خروجی: Pair(سرور انجام شد, id یا پیام خطا)
     */
    fun submitPoi(ctx: Context, name: String, category: String, lat: Double, lon: Double,
                  province: String?, district: String?, roadName: String?, direction: String?,
                  description: String?, properties: JSONObject? = null): Pair<Boolean, Any> {
        if (name.isBlank()) return Pair(false, "name_required")
        if (!isKnownCategory(category)) return Pair(false, "bad_category")
        if (description != null && ReportsModule.isSensitive(description)) return Pair(false, "sensitive")
        // تکراری محلی
        val dup = nearbyCached(ctx, lat, lon, 35.0, category).firstOrNull()
        if (dup != null) return Pair(true, dup.id)

        val payload = JSONObject().apply {
            put("name", name.take(120))
            put("category", category)
            put("latitude", lat); put("longitude", lon)
            province?.let { put("province", it) }
            district?.let { put("district", it) }
            roadName?.let { put("road_name", it) }
            direction?.let { put("direction", it) }
            description?.let { put("description", it.take(400)) }
            properties?.let { put("properties", it) }
            put("device_id", deviceId(ctx))
            put("source", "user")
        }
        val base = serverUrl(ctx)
        if (base.startsWith("http")) {
            try {
                val body = Http.postJson("$base/api/v1/pois", payload.toString())
                val obj = JSONObject(body)
                if (obj.optBoolean("ok")) {
                    val id = obj.optLong("id", -1)
                    if (id > 0) {
                        // مکان تازه در کش محلی هم ذخیره می‌شود تا آفلاین دیده شود
                        cacheResponse(ctx, org.json.JSONArray().put(payload.let {
                            JSONObject().apply {
                                put("id", id)
                                put("name", name.take(120))
                                put("category", category)
                                put("latitude", lat); put("longitude", lon)
                                province?.let { put("province", it) }
                                district?.let { put("district", it) }
                                roadName?.let { put("road_name", it) }
                                direction?.let { put("direction", it) }
                                description?.let { put("description", it.take(400)) }
                                properties?.let { put("properties_json", it.toString()) }
                                put("verification_status", "unverified")
                                put("source", "user")
                                put("updated_at", System.currentTimeMillis())
                            }
                        }))
                    }
                    return Pair(true, id)
                }
            } catch (e: Exception) { /* آفلاین → صف */ }
        }
        queueOp(ctx, "add", payload)
        return Pair(true, -1L) // در صف ارسال — بعداً همگام می‌شود
    }

    /** رأی تأیید یا رد مکان (بخش هفتم) */
    fun votePoi(ctx: Context, poiId: Long, confirm: Boolean): Boolean {
        val payload = JSONObject()
            .put("device_id", deviceId(ctx))
            .put("vote", if (confirm) "confirm" else "deny")
        val base = serverUrl(ctx)
        if (base.startsWith("http")) {
            try {
                val body = Http.postJson("$base/api/v1/pois/$poiId/verify", payload.toString())
                return JSONObject(body).optBoolean("ok")
            } catch (e: Exception) { }
        }
        queueOp(ctx, "verify", payload.put("poi_id", poiId))
        return true // در صف
    }

    /** گزارش اطلاعات اشتباه / حذف‌شده / خراب (بخش هفتم) */
    fun reportPoi(ctx: Context, poiId: Long, type: String, description: String?): Boolean {
        if (type !in setOf("broken", "removed", "wrong_info", "moved", "closed", "reopened", "price_changed")) return false
        if (description != null && ReportsModule.isSensitive(description)) return false
        val payload = JSONObject()
            .put("device_id", deviceId(ctx))
            .put("type", type)
            description?.let { payload.put("description", it.take(400)) }
        val base = serverUrl(ctx)
        if (base.startsWith("http")) {
            try {
                val body = Http.postJson("$base/api/v1/pois/$poiId/report", payload.toString())
                return JSONObject(body).optBoolean("ok")
            } catch (e: Exception) { }
        }
        queueOp(ctx, "report", payload.put("poi_id", poiId))
        return true
    }

    /** ارسال صف عملیات ذخیره‌شده وقتی انترنت برگشت (بخش دوازدهم) */
    fun flushPending(ctx: Context) {
        val base = serverUrl(ctx)
        if (!base.startsWith("http")) return
        val db = getDb(ctx).writableDatabase
        val ops = ArrayList<Triple<Long, String, JSONObject>>()
        try {
            db.rawQuery("SELECT id, kind, payload FROM pending_ops ORDER BY id LIMIT 80", null).use { c ->
                while (c.moveToNext()) {
                    try { ops.add(Triple(c.getLong(0), c.getString(1), JSONObject(c.getString(2)))) } catch (e: Exception) { }
                }
            }
            for ((id, kind, payload) in ops) {
                val ok = try {
                    when (kind) {
                        "add" -> JSONObject(Http.postJson("$base/api/v1/pois", payload.toString())).optBoolean("ok")
                        "verify" -> {
                            val pid = payload.optLong("poi_id")
                            val p = JSONObject(payload.toString()); p.remove("poi_id")
                            pid > 0 && JSONObject(Http.postJson("$base/api/v1/pois/$pid/verify", p.toString())).optBoolean("ok")
                        }
                        "report" -> {
                            val pid = payload.optLong("poi_id")
                            val p = JSONObject(payload.toString()); p.remove("poi_id")
                            pid > 0 && JSONObject(Http.postJson("$base/api/v1/pois/$pid/report", p.toString())).optBoolean("ok")
                        }
                        else -> false
                    }
                } catch (e: Exception) { false }
                if (ok) db.execSQL("DELETE FROM pending_ops WHERE id = ?", arrayOf(id.toString()))
                else db.execSQL("UPDATE pending_ops SET tries = tries + 1 WHERE id = ?", arrayOf(id.toString()))
            }
        } catch (e: Exception) { }
    }

    private fun queueOp(ctx: Context, kind: String, payload: JSONObject) {
        try {
            val cv = ContentValues().apply {
                put("kind", kind)
                put("payload", payload.toString())
                put("created_at", System.currentTimeMillis())
            }
            getDb(ctx).writableDatabase.insert("pending_ops", null, cv)
        } catch (e: Exception) { }
    }

    fun pendingCount(ctx: Context): Int = try {
        getDb(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_ops", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (e: Exception) { 0 }

    private fun putMeta(ctx: Context, k: String, v: String) {
        try {
            val cv = ContentValues().apply { put("k", k); put("v", v) }
            getDb(ctx).writableDatabase.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) { }
    }

    private fun getMeta(ctx: Context, k: String): String? = try {
        getDb(ctx).readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    fun lastSync(ctx: Context): Long = getMeta(ctx, "last_sync")?.toLongOrNull() ?: 0L
}
