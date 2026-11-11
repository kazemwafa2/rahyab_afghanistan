package af.rahyab.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class Place(val name: String, val lat: Double, val lon: Double)

/** یک مرحله ناوبری: نوع و جهت پیچ، نام خیابان، طول این مرحله و محل پیچ */
data class Step(
    val type: String, val modifier: String?, val name: String,
    val distance: Double, val lat: Double, val lon: Double
)

/** نقاط مسیر به شکل (lon, lat) */
data class RouteResult(
    val distanceM: Double, val durationS: Double,
    val points: List<Pair<Double, Double>>, val steps: List<Step>
)

/** وسیله نقلیه/نوع مسیر — بخش هفتم چک‌لیست */
enum class Profile { CAR, MOTO, BIKE, FOOT }

// ---------- خطاهای شبکه با پیام مشخص ----------
sealed class ServiceException(message: String) : Exception(message)
/** سرویس شلوغ یا محدود شده (HTTP 429) */
class RateLimitedException : ServiceException("rate_limited")
/** دسترسی رد شد (HTTP 403) */
class ForbiddenException : ServiceException("forbidden")
/** مشکل شبکه، قطع انترنت یا تایم‌اوت */
class NetworkException(cause: Exception? = null) : ServiceException("network")
/** پاسخ نامعتبر سرویس */
class BadResponseException(message: String = "bad_response") : ServiceException(message)

/** رابط سرویس جست‌وجو (آنلاین یا آفلاین، قابل تعویض بدون تغییر رابط کاربری) */
interface GeocoderService {
    fun search(query: String, lang: String, userLat: Double? = null, userLon: Double? = null): List<Place>
}

/** رابط سرویس مسیریابی (آنلاین یا آفلاین، قابل تعویض بدون تغییر رابط کاربری) */
interface RoutingService {
    fun route(profile: Profile, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult?
}

// ---------- HTTP ----------
object Http {
    fun get(url: String, readTimeoutMs: Int = Config.HTTP_READ_TIMEOUT_MS): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = Config.HTTP_CONNECT_TIMEOUT_MS
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", Config.USER_AGENT)
        c.setRequestProperty("Accept-Language", "fa,en")
        try {
            when (c.responseCode) {
                429 -> throw RateLimitedException()
                403 -> throw ForbiddenException()
                !in 200..299 -> throw BadResponseException("HTTP ${c.responseCode}")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            throw NetworkException(e)
        } finally {
            c.disconnect()
        }
    }
}

/** محدودکننده نرخ برای رعایت سیاست Nominatim (حداکثر یک درخواست در ثانیه) */
class RateLimiter(private val minIntervalMs: Long) {
    private var last = 0L
    fun acquire() {
        while (true) {
            val now = System.currentTimeMillis()
            val wait = last + minIntervalMs - now
            if (wait <= 0) { last = now; return }
            try { Thread.sleep(wait) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }
}

/**
 * کش دیسکی ساده (JSON) برای درخواست‌های تکراری جست‌وجو و مسیر.
 * طبق سیاست Nominatim کش کردن نتایج توصیه می‌شود تا درخواست تکراری ارسال نشود.
 */
class DiskJsonCache(dirName: String, private val ttlMs: Long, private val maxEntries: Int) {
    private val file: File
    private val map = LinkedHashMap<String, Pair<Long, String>>()
    private val lock = Any()

    init {
        val dir = File(cacheDir, "http_cache")
        dir.mkdirs()
        file = File(dir, "$dirName.json")
        try {
            if (file.isFile) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) {
                    val idx = k.indexOf('\u0001')
                    if (idx <= 0) continue
                    val ts = k.substring(0, idx).toLong()
                    val realKey = k.substring(idx + 1)
                    map[realKey] = Pair(ts, o.getString(k))
                }
            }
        } catch (e: Exception) { map.clear() }
    }

    fun get(key: String): String? = synchronized(lock) {
        val hit = map[key] ?: return null
        if (System.currentTimeMillis() - hit.first > ttlMs) { map.remove(key); return null }
        hit.second
    }

    fun put(key: String, value: String) = synchronized(lock) {
        map[key] = Pair(System.currentTimeMillis(), value)
        // حذف قدیمی‌ترین‌ها
        val iter = map.entries.iterator()
        while (map.size > maxEntries && iter.hasNext()) { iter.next(); iter.remove() }
        persistLocked()
    }

    private fun persistLocked() {
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put("${v.first.toString()}\u0001$k", v.second)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) { /* کش حیاتی نیست */ }
    }

    companion object {
        lateinit var cacheDir: File
        fun init(ctx: Context) { cacheDir = ctx.cacheDir }
    }
}

// ---------- سرویس‌های آنلاین ----------
class NominatimGeocoder(private val base: String) : GeocoderService {
    private val limiter = RateLimiter(Config.GEOCODER_MIN_INTERVAL_MS)
    private val cache by lazy { DiskJsonCache("search", Config.SEARCH_CACHE_TTL_MS, Config.SEARCH_CACHE_MAX) }

    private fun cacheKey(q: String, lang: String, lat: Double?, lon: Double?): String {
        // نتایج Nominatim وابسته به زبان است؛ موقعیت کاربر فقط رتبه‌بندی محلی را عوض می‌کند
        val raw = "s|$lang|$q"
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    override fun search(query: String, lang: String, userLat: Double?, userLon: Double?): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val key = cacheKey(q, lang, userLat, userLon)
        cache.get(key)?.let { json ->
            return try { parse(JSONArray(json)) } catch (e: Exception) { emptyList() }
        }
        limiter.acquire()
        val qq = URLEncoder.encode(q, "UTF-8")
        val body = Http.get(
            "$base/search?format=jsonv2&limit=8&countrycodes=af&addressdetails=0&accept-language=$lang&q=$qq"
        )
        val list = parse(JSONArray(body))
        cache.put(key, body)
        return list
    }

    private fun parse(arr: JSONArray): List<Place> {
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("display_name", "").ifEmpty { o.optString("name", "") }
            if (name.isEmpty()) continue
            out.add(Place(name, o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }
        return out
    }
}

class OsrmRouting(private val carBase: String, private val footBase: String, private val bikeBase: String? = null) : RoutingService {
    private val cache by lazy { DiskJsonCache("route", Config.ROUTE_CACHE_TTL_MS, Config.ROUTE_CACHE_MAX) }

    override fun route(profile: Profile, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? {
        val base = when (profile) {
            Profile.CAR, Profile.MOTO -> carBase   // موتورسکل روی سرویس عمومی مثل موتر نزدیک‌تر است
            Profile.BIKE -> bikeBase ?: carBase
            Profile.FOOT -> footBase
        }
        val url = "$base${fromLon},${fromLat};${toLon},${toLat}?overview=full&geometries=geojson&steps=true"
        val key = java.util.zip.CRC32().apply { update(url.toByteArray()) }.value.toString()
        cache.get(key)?.let { json ->
            return try { parse(JSONObject(json)) } catch (e: Exception) { null }
        }
        val json = Http.get(url)
        val r = parse(JSONObject(json))
        cache.put(key, json)
        return r
    }

    private fun parse(json: JSONObject): RouteResult? {
        val routes = json.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val r = routes.getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map {
            val c = coords.getJSONArray(it)
            Pair(c.getDouble(0), c.getDouble(1))
        }
        if (pts.size < 2) return null

        val steps = ArrayList<Step>()
        val legs = r.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val st = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until st.length()) {
                    val s = st.getJSONObject(si)
                    val man = s.getJSONObject("maneuver")
                    val loc = man.getJSONArray("location")
                    steps.add(
                        Step(
                            man.optString("type", "continue"),
                            if (man.has("modifier")) man.getString("modifier") else null,
                            s.optString("name", ""),
                            s.optDouble("distance", 0.0),
                            loc.getDouble(1), loc.getDouble(0)
                        )
                    )
                }
            }
        }
        if (steps.isEmpty()) {
            val last = pts.last()
            steps.add(Step("arrive", null, "", 0.0, last.second, last.first))
        }
        return RouteResult(r.getDouble("distance"), r.getDouble("duration"), pts, steps)
    }
}

// ---------- سرویس‌های آفلاین ----------
class OfflineGeocoderService(private val ix: OfflineGeocoder.Index) : GeocoderService {
    override fun search(query: String, lang: String, userLat: Double?, userLon: Double?): List<Place> =
        ix.search(query, userLat, userLon)
}

class OfflineRoutingService(private val g: OfflineRouter.Graph) : RoutingService {
    override fun route(profile: Profile, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? =
        OfflineRouter.route(g, profile, fromLat, fromLon, toLat, toLon)
}

// ---------- دروازه انتخاب سرویس: آفلاین اول، آنلاین جایگزین ----------
object Services {
    private val onlineGeocoder by lazy { NominatimGeocoder(Config.GEOCODER_URL) }
    private val onlineRouting by lazy { OsrmRouting(Config.ROUTING_CAR_URL, Config.ROUTING_FOOT_URL, Config.ROUTING_BIKE_URL) }

    fun init(ctx: Context) {
        DiskJsonCache.init(ctx)
        OfflineData.init(ctx)
    }

    /** اگر بسته آفلاین نصب باشد، جست‌وجوی محلی اولویت دارد */
    val searchIsOffline: Boolean get() = OfflineData.indexForSearch() != null
    val routingIsOffline: Boolean get() = OfflineData.graphForRouting() != null
    val offlineSourceName: String? get() = OfflineData.dataSourceName

    enum class Source { OFFLINE, ONLINE }

    data class SearchResult(val places: List<Place>, val source: Source)

    /**
     * جست‌وجوی ترکیبی:
     *  ۱) بسته آفلاین نصب باشد → جست‌وجوی محلی
     *  ۲) اگر نتیجه محلی کم بود یا بسته نبود → آنلاین (با کش و محدودسازی نرخ)
     * خطاهای آنلاین به بالا منتقل می‌شود تا رابط کاربری پیام مناسب نشان دهد.
     */
    fun search(query: String, lang: String, userLat: Double? = null, userLon: Double? = null): SearchResult {
        val ix = OfflineData.indexForSearch()
        val local = if (ix != null) ix.search(query, userLat, userLon) else emptyList()
        // اگر جست‌وجوی محلی جواب داد، به سرور عمومی کاری نداریم
        if (local.size >= 3) return SearchResult(local, Source.OFFLINE)

        var online: List<Place> = emptyList()
        var onlineOk = false
        try {
            online = onlineGeocoder.search(query, lang, userLat, userLon)
            onlineOk = true
        } catch (e: ServiceException) {
            if (local.isEmpty()) throw e
            Log.d("Services", "جست‌وجوی آنلاین ناموفق؛ نتیجه آفلاین برگردانده شد: ${e.message}")
        }
        if (!onlineOk) return SearchResult(local, Source.OFFLINE)
        if (local.isEmpty()) return SearchResult(online, Source.ONLINE)
        // ادغام: محلی اول، آنلاین بعد، بدون تکرار
        val merged = ArrayList<Place>(local.size + online.size)
        merged.addAll(local)
        val seen = HashSet<Pair<Long, Long>>()
        for (p in merged) seen.add(Math.round(p.lat * 1e4) to Math.round(p.lon * 1e4))
        for (p in online) {
            val k = Math.round(p.lat * 1e4) to Math.round(p.lon * 1e4)
            if (seen.add(k)) merged.add(p)
        }
        return SearchResult(merged.take(15), Source.OFFLINE)
    }

    /**
     * جست‌وجوی دسته‌ای (مسجد، شفاخانه، دواخانه و…) روی همه بسته‌های نصب‌شده،
     * مرتب‌شده بر اساس نزدیکی به کاربر. فقط آفلاین؛ نتیجه خالی یعنی داده موجود نیست.
     */
    fun searchCategory(cat: Int, userLat: Double?, userLon: Double?): List<Place> {
        if (cat <= 0) return emptyList()
        val merged = ArrayList<Place>(32)
        val seen = HashSet<Pair<Long, Long>>()
        for (ix in OfflineData.indexesForSearch()) {
            for (p in ix.searchCategory(cat, userLat, userLon, 15)) {
                val k = Math.round(p.lat * 1e4) to Math.round(p.lon * 1e4)
                if (seen.add(k)) merged.add(p)
            }
        }
        if (userLat != null && userLon != null) {
            val la = userLat; val lo = userLon
            merged.sortBy { p ->
                val dLat = p.lat - la; val dLon = p.lon - lo
                dLat * dLat + dLon * dLon
            }
        }
        return merged.take(20)
    }

    /**
     * مسیریابی ترکیبی: اول موتور آفلاین روی گوشی؛ اگر بسته نصب نبود یا مسیر نیافت
     * (مثلاً مقصد بیرون محدوده بسته)، سرویس آنلاین آزمایش می‌شود.
     */
    fun route(profile: Profile, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Pair<RouteResult?, Source> {
        // انتخاب هوشمند گراف: مسیر درون‌ولایتی → گراف ولایت؛ مسیر بین‌ولایتی → گراف پایه کشوری
        val g = OfflineData.graphForRouting(fromLat, fromLon, toLat, toLon)
        if (g != null) {
            val r = OfflineRouter.route(g, profile, fromLat, fromLon, toLat, toLon)
            if (r != null) return Pair(r, Source.OFFLINE)
        }
        val r = onlineRouting.route(profile, fromLat, fromLon, toLat, toLon)
        return Pair(r, Source.ONLINE)
    }

    /**
     * مسیرهای جایگزین به سبک نشان/بلد: سریع‌ترین + جایگزین‌ها (تا ۳ مسیر).
     * آفلاین: موتور داخلی با روش جریمه؛ آنلاین: تک‌مسیر سرویس عمومی.
     * اگر هیچ مسیری نبود، فهرست خالی برمی‌گردد.
     */
    fun routeAlternatives(profile: Profile, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Pair<List<RouteResult>, Source> {
        val g = OfflineData.graphForRouting(fromLat, fromLon, toLat, toLon)
        if (g != null) {
            val list = try {
                OfflineRouter.routeWithAlternatives(g, profile, fromLat, fromLon, toLat, toLon, 3)
            } catch (e: Exception) {
                Log.w("Services", "محاسبه مسیرهای جایگزین ناموفق", e)
                emptyList()
            }
            if (list.isNotEmpty()) return Pair(list, Source.OFFLINE)
        }
        val r = onlineRouting.route(profile, fromLat, fromLon, toLat, toLon)
        return if (r != null) Pair(listOf(r), Source.ONLINE) else Pair(emptyList(), Source.ONLINE)
    }

    /** مسیر فقط با موتور آفلاین (برای محاسبه مجدد در ناوبری آفلاین) */
    fun routeOffline(profile: Profile, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): RouteResult? {
        val g = OfflineData.graphForRouting(fromLat, fromLon, toLat, toLon) ?: return null
        return OfflineRouter.route(g, profile, fromLat, fromLon, toLat, toLon)
    }
}
