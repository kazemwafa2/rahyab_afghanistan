package af.rahyab.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * انبار محلی موتور ترافیک — فایل جداگانهٔ «traffic.db».
 *
 * چرا فایل جدا؟ قانون ۹ سند: هر تغییر دیتابیس باید امن باشد. دیتابیس‌های موجود
 * (reports.db و …) دست‌نخورده می‌مانند و هیچ مهاجرتی روی دادهٔ کاربر اجرا
 * نمی‌شود؛ این فایل از صفر ساخته می‌شود و پاک‌شدنش هیچ دادهٔ کاربر را نمی‌کشد.
 *
 * جدول‌ها:
 *  - pending_samples: نمونه‌های سرعت در انتظار ارسال (صف آفلاین بند ۱۸/۱۹ —
 *    حذف فقط پس از تأیید سرور؛ سقف تلاش برای ردیف‌های معیوب)
 *  - states_cache: آخرین وضعیت زندهٔ قطعه‌ها از سرور — برای نمایش آفلاین
 *    با مُهر زمانی «زنده نیست» (بخش ۲۰: دادهٔ قدیمی هرگز به‌عنوان زنده نمایش داده نمی‌شود)
 */
object TrafficStore {

    /** سقف تلاش ارسال هر نمونه */
    const val MAX_SEND_TRIES = 8

    /** عمر نگهداری نمونهٔ محلی در انتظار ارسال */
    const val SAMPLE_KEEP_MS = 48 * 60 * 60 * 1000L

    /** عمر معتبر بودن کش وضعیت‌ها (پنجرهٔ زندهٔ سرور ۲۰ دقیقه + حاشیه) */
    const val STATE_TTL_MS = 30 * 60 * 1000L

    data class Sample(
        val id: Long, val seg: String, val speedKmh: Double,
        val bearing: Int, val acc: Int, val ts: Long, val road: String?
    )

    data class StateRow(
        val seg: String, val state: String, val intensity: Double,
        val drivers: Int, val baseline: Double, val avgSpeed: Double,
        val confidence: String, val startedAt: Long, val lastObserved: Long,
        val roadType: String, val roadName: String?
    ) {
        fun center(): Pair<Double, Double>? = RoadSegments.centerOf(seg)
        val expired: Boolean get() = System.currentTimeMillis() - lastObserved > STATE_TTL_MS
    }

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "traffic.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_samples(id INTEGER PRIMARY KEY AUTOINCREMENT, seg TEXT NOT NULL, speed REAL NOT NULL, bearing INTEGER NOT NULL, acc INTEGER NOT NULL, ts INTEGER NOT NULL, road TEXT, tries INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS states_cache(seg TEXT PRIMARY KEY, state TEXT NOT NULL, intensity REAL, drivers INTEGER, baseline REAL, avg_speed REAL, confidence TEXT, started_at INTEGER, last_observed INTEGER, road_type TEXT, road_name TEXT, updated_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }
    }

    private val lock = Any()
    private var db: Db? = null

    private fun getDb(ctx: Context): Db = synchronized(lock) {
        if (db == null) db = Db(ctx.applicationContext)
        db!!
    }

    // ---------- کلید مشارکت در دادهٔ ترافیک (حریم خصوصی: کاربر کنترل دارد) ----------

    fun contributionEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("traffic", Context.MODE_PRIVATE).getBoolean("contribute", true)

    fun setContribution(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("traffic", Context.MODE_PRIVATE)
            .edit().putBoolean("contribute", v).apply()
    }

    // ---------- صف نمونه‌ها ----------

    fun queueSample(ctx: Context, seg: String, speedKmh: Double, bearing: Int, accM: Int, road: String?) {
        try {
            getDb(ctx).writableDatabase.insert("pending_samples", null, ContentValues().apply {
                put("seg", seg); put("speed", speedKmh); put("bearing", bearing)
                put("acc", accM); put("ts", System.currentTimeMillis()); put("road", road)
            })
        } catch (e: Exception) { }
    }

    fun pendingCount(ctx: Context): Int = try {
        getDb(ctx).readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_samples", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (e: Exception) { 0 }

    /** نگاه به صف (بدون حذف) — ارسال دسته‌ای حداکثر ۴۰ نمونه */
    fun takePending(ctx: Context, limit: Int = 40): List<Sample> {
        val out = ArrayList<Sample>()
        try {
            getDb(ctx).readableDatabase.rawQuery(
                "SELECT id, seg, speed, bearing, acc, ts, road FROM pending_samples WHERE tries < ? ORDER BY ts ASC LIMIT ?",
                arrayOf(MAX_SEND_TRIES.toString(), limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(Sample(
                        c.getLong(0), c.getString(1), c.getDouble(2),
                        c.getInt(3), c.getInt(4), c.getLong(5), c.getString(6)
                    ))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun deleteSample(ctx: Context, id: Long) {
        try { getDb(ctx).writableDatabase.delete("pending_samples", "id=?", arrayOf(id.toString())) } catch (e: Exception) { }
    }

    fun bumpTries(ctx: Context, id: Long) {
        try {
            getDb(ctx).writableDatabase.execSQL(
                "UPDATE pending_samples SET tries = tries + 1 WHERE id=?", arrayOf(id.toString())
            )
        } catch (e: Exception) { }
    }

    /** پاک‌سازی نمونه‌های کهنه (بیش از ۴۸ ساعت؛ دیگر برای «زنده» به کار نمی‌آیند) */
    fun purgeOld(ctx: Context) {
        try {
            getDb(ctx).writableDatabase.delete(
                "pending_samples", "ts < ?",
                arrayOf((System.currentTimeMillis() - SAMPLE_KEEP_MS).toString())
            )
        } catch (e: Exception) { }
    }

    // ---------- کش وضعیت‌های زنده ----------

    fun saveStates(ctx: Context, rows: List<StateRow>) {
        try {
            val db = getDb(ctx).writableDatabase
            db.beginTransaction()
            try {
                for (r in rows) {
                    db.insertWithOnConflict("states_cache", null, ContentValues().apply {
                        put("seg", r.seg); put("state", r.state); put("intensity", r.intensity)
                        put("drivers", r.drivers); put("baseline", r.baseline); put("avg_speed", r.avgSpeed)
                        put("confidence", r.confidence); put("started_at", r.startedAt)
                        put("last_observed", r.lastObserved); put("road_type", r.roadType)
                        put("road_name", r.roadName); put("updated_at", System.currentTimeMillis())
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (e: Exception) { }
    }

    /** همهٔ وضعیت‌های کش‌شده (منقضی‌ها صدا زده نمی‌شوند — بخش ۲۰) */
    fun cachedStates(ctx: Context): List<StateRow> {
        val out = ArrayList<StateRow>()
        try {
            getDb(ctx).readableDatabase.rawQuery(
                "SELECT seg, state, intensity, drivers, baseline, avg_speed, confidence, started_at, last_observed, road_type, road_name FROM states_cache ORDER BY intensity DESC LIMIT 400",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val r = StateRow(
                        c.getString(0), c.getString(1), c.getDouble(2), c.getInt(3),
                        c.getDouble(4), c.getDouble(5), c.getString(6), c.getLong(7),
                        c.getLong(8), c.getString(9) ?: "main", c.getString(10)
                    )
                    if (!r.expired) out.add(r)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** آخرین تازه‌سازی موفق کش (۰ = هرگز) */
    fun lastRefreshMs(ctx: Context): Long = try {
        getDb(ctx).readableDatabase.rawQuery(
            "SELECT MAX(updated_at) FROM states_cache", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    } catch (e: Exception) { 0L }

    /** پاک‌کردن کش (با تغییر سرور یا خروج کاربر از لایه) */
    fun clearStates(ctx: Context) {
        try { getDb(ctx).writableDatabase.delete("states_cache", null, null) } catch (e: Exception) { }
    }
}
