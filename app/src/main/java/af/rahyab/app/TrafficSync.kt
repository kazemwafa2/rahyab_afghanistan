package af.rahyab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * لایهٔ شبکهٔ موتور ترافیک — ارسال نمونه‌ها و دریافت وضعیت‌های زنده.
 *  - ارسال صف آفلاین: حذف از صف فقط پس از تأیید سرور (بند ۱۸/۱۹)
 *  - دریافت وضعیت: نزدیک موقعیت فعلی (نقشه/رانندگی آزاد) یا در طول مسیر فعال (ناوبری)
 *  - در شکست شبکه بی‌صدا — کش قبلی با مُهر «آخرین به‌روزرسانی» می‌ماند (بخش ۲۰)
 */
object TrafficSync {

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun flushSamplesAsync(ctx: Context) {
        val app = ctx.applicationContext
        try { io.execute { try { flushSamplesBlocking(app) } catch (e: Exception) { } } } catch (e: Exception) { }
    }

    /** ارسال صف نمونه‌ها — عملیات idempotent در سرور (seg+device+دقیقه) */
    fun flushSamplesBlocking(ctx: Context): Boolean {
        if (!RoadPois.serverConfigured(ctx)) return false
        val base = RoadPois.serverUrl(ctx).trimEnd('/')
        val device = RoadPois.deviceId(ctx)
        val rows = TrafficStore.takePending(ctx, 40)
        if (rows.isEmpty()) return true
        val arr = JSONArray()
        for (r in rows) {
            val o = JSONObject()
                .put("seg", r.seg)
                .put("speed", r.speedKmh)
                .put("bearing", r.bearing)
                .put("acc", r.acc)
                .put("ts", r.ts)
            if (!r.road.isNullOrBlank()) o.put("road", r.road!!.take(120))
            arr.put(o)
        }
        return try {
            val body = Http.postJson(
                "$base/api/v1/traffic/samples",
                JSONObject().put("device_id", device).put("samples", arr).toString(),
                readTimeoutMs = 12000
            )
            val ok = JSONObject(body).optBoolean("ok", false)
            if (ok) for (r in rows) TrafficStore.deleteSample(ctx, r.id)
            else for (r in rows) TrafficStore.bumpTries(ctx, r.id)
            ok
        } catch (e: Exception) {
            for (r in rows) TrafficStore.bumpTries(ctx, r.id)
            false
        }
    }

    // ---------- دریافت وضعیت‌ها ----------

    /** وضعیت‌های پیرامون یک نقطه (نقشه/رانندگی آزاد) — جعبهٔ ±۰٫۱۵ درجه ≈ ۱۶ کیلومتر */
    fun refreshNearAsync(ctx: Context, lat: Double, lon: Double) {
        val app = ctx.applicationContext
        try { io.execute { try { refreshNearBlocking(app, lat, lon) } catch (e: Exception) { } } } catch (e: Exception) { }
    }

    fun refreshNearBlocking(ctx: Context, lat: Double, lon: Double): Boolean {
        if (!RoadPois.serverConfigured(ctx)) return false
        val base = RoadPois.serverUrl(ctx).trimEnd('/')
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(
            java.util.Locale.US, lat - 0.15, lon - 0.15, lat + 0.15, lon + 0.15
        )
        return try {
            val body = Http.get("$base/api/v1/traffic/states?bbox=$bbox&limit=200", readTimeoutMs = 12000)
            parseAndStore(ctx, body)
        } catch (e: Exception) { false }
    }

    /**
     * وضعیت‌ها در طول مسیر فعال — بند ۱۵ (مسیر فعال → قطعه‌ها → هشدار مسیرمحور).
     * تا ۴۰ نقطهٔ نمونه از مسیر؛ راهرو ۱۲۰۰ متر.
     */
    fun refreshAlongRouteAsync(ctx: Context, route: RouteResult) {
        val app = ctx.applicationContext
        try { io.execute { try { refreshAlongRouteBlocking(app, route) } catch (e: Exception) { } } } catch (e: Exception) { }
    }

    fun refreshAlongRouteBlocking(ctx: Context, route: RouteResult): Boolean {
        if (!RoadPois.serverConfigured(ctx)) return false
        val base = RoadPois.serverUrl(ctx).trimEnd('/')
        val pts = route.points
        if (pts.size < 2) return false
        val step = Math.max(1, pts.size / 40)
        val sb = StringBuilder()
        var i = 0
        while (i < pts.size) {
            if (i > 0) sb.append(';')
            // قالب سرور: lat,lon — نقاط مسیر (lon,lat)
            sb.append("%.5f,%.5f".format(java.util.Locale.US, pts[i].second, pts[i].first))
            i += step
        }
        val routeParam = URLEncoder.encode(sb.toString(), "UTF-8")
        return try {
            val body = Http.get(
                "$base/api/v1/traffic/along-route?route=$routeParam&corridor=1200&limit=200",
                readTimeoutMs = 12000
            )
            parseAndStore(ctx, body)
        } catch (e: Exception) { false }
    }

    private fun parseAndStore(ctx: Context, body: String): Boolean {
        val o = JSONObject(body)
        if (!o.optBoolean("ok", false)) return false
        val arr = o.optJSONArray("states") ?: return false
        val now = System.currentTimeMillis()
        val rows = ArrayList<TrafficStore.StateRow>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val seg = s.optString("seg", "")
            if (!seg.startsWith("g")) continue
            rows.add(TrafficStore.StateRow(
                seg = seg,
                state = s.optString("state", TrafficRules.ST_INSUFFICIENT),
                intensity = s.optDouble("intensity", 0.0),
                drivers = s.optInt("drivers", 0),
                baseline = s.optDouble("baseline", 0.0),
                avgSpeed = s.optDouble("avg_speed", 0.0),
                confidence = s.optString("confidence", "insufficient"),
                startedAt = s.optLong("started_at", now),
                lastObserved = s.optLong("last_observed", now),
                roadType = s.optString("road_type", "main"),
                roadName = if (s.isNull("road_name")) null else s.optString("road_name", null)
            ))
        }
        TrafficStore.saveStates(ctx, rows)
        return true
    }
}
