package af.rahyab.app

import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * دریافت مبدأ/مقصد از برنامه‌های دیگر — بخش ششم چک‌لیست.
 *
 * پشتیبانی‌شده:
 *  ۱) Deep Link اختصاصی: rahyab://navigate?... (مستند در docs/INTEGRATION.md)
 *  ۲) geo:lat,lon?q=... (استاندارد اندروید؛ از مرورگر و برنامه‌های دیگر)
 *  ۳) لینک‌های Google Maps: https://maps.google.com/?q=…&ll=…،
 *     https://www.google.com/maps?q=…/@lat,lon، https://maps.app.goo.gl/… (کوتاه)
 *  ۴) لینک OpenStreetMap: …?mlat=…&mlon=…
 *  ۵) اشتراک متن (ACTION_SEND) از واتساپ و… : استخراج مختصات خام
 *     «34.53, 69.17»، لینک نقشه داخل متن، یا نام مکان برای جست‌وجو.
 *
 * هسته تحلیل با رشته محض (بدون android.net.Uri) نوشته شده تا بدون شبیه‌ساز
 * اندروید هم قابل آزمون باشد؛ توابع Uri فقط پوش نازک هستند.
 */
object Integration {

    class Parsed(
        val lat: Double?, val lon: Double?, val name: String?,
        val searchQuery: String?, val needResolveUrl: String?
    )

    /** تحلیل Intent ورودی؛ null یعنی این برنامه موردنظر نیست */
    fun parse(intent: Intent?): Parsed? {
        if (intent == null) return null
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                return fromText(text)
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return null
                return fromUriString(uri.toString())
            }
            else -> return null
        }
    }

    /** تحلیل هر متن: مختصات، لینک نقشه یا نام مکان */
    fun fromText(text: String): Parsed {
        val t = text.trim()

        // ۱) مختصات خام: «34.5281, 69.1723» یا «34.5281، 69.1723»
        val coord = Regex("(?<![\\d.\\-])(-?\\d{1,2}\\.\\d{3,})[\\s,،]+(-?\\d{1,3}\\.\\d{3,})(?![\\d])")
            .find(t)
        if (coord != null) {
            val la = coord.groupValues[1].toDoubleOrNull()
            val lo = coord.groupValues[2].toDoubleOrNull()
            if (valid(la, lo)) return Parsed(la, lo, null, null, null)
        }

        // ۲) لینک نقشه داخل متن
        val link = Regex("https?://\\S+").find(t)?.value
        if (link != null) {
            val p = fromUriString(link.trimEnd('.', ')', '،'))
            if (p.hasResult()) return p
        }

        // ۳) geo: داخل متن
        val geo = Regex("geo:[-\\d.]+[\\s,،]+[-\\d.]+").find(t)?.value
        if (geo != null) {
            val p = fromUriString(geo.replaceFirst("geo:", "geo:") .replace(Regex("[\\s،]+"), ","))
            if (p.hasResult()) return p
        }

        // ۴) متن به‌عنوان نام مکان (برای جست‌وجو)
        if (t.length in 2..100) return Parsed(null, null, null, t, null)
        return Parsed(null, null, null, null, null)
    }

    private fun Parsed.hasResult(): Boolean =
        (lat != null && lon != null) || !searchQuery.isNullOrBlank()

    /** تحلیل URI ورودی اندروید */
    fun fromUri(uri: Uri): Parsed = fromUriString(uri.toString())

    /** تحلیل رشته نشانی (geo: یا لینک وب) */
    fun fromUriString(url: String): Parsed {
        val u = url.trim()
        val lower = u.lowercase()
        return when {
            lower.startsWith("geo:") -> parseGeo(u) ?: Parsed(null, null, null, null, null)
            lower.startsWith("rahyab://") -> parseRahyab(u)
            lower.startsWith("http://") || lower.startsWith("https://") -> parseWebUrl(u)
            else -> Parsed(null, null, null, null, null)
        }
    }

    private fun parseRahyab(u: String): Parsed {
        val q = queryParams(u.substringAfter("?", ""))
        val dLat = q["destination_lat"]?.toDoubleOrNull()
        val dLon = q["destination_lon"]?.toDoubleOrNull()
        val name = q["name"]?.takeIf { it.isNotBlank() }
        if (valid(dLat, dLon)) return Parsed(dLat, dLon, name, null, null)
        return Parsed(null, null, null, null, null)
    }

    private fun parseGeo(u: String): Parsed? {
        val body = u.substringAfter("geo:").substringBefore('?')
        val q = queryParams(u.substringAfter('?', ""), semiColonAware = true)
        val parts = body.split(",")
        if (parts.size < 2) return null
        val la = parts[0].trim().toDoubleOrNull()
        val lo = parts[1].trim().toDoubleOrNull()
        if (!valid(la, lo)) return null
        // q= می‌تواند مختصات جایگزین یا نام مکان باشد
        val qq = q["q"]?.trim()
        if (qq != null && qq.isNotEmpty()) {
            val qp = qq.split(",")
            if (qp.size >= 2) {
                val qa = qp[0].trim().toDoubleOrNull()
                val qb = qp[1].trim().toDoubleOrNull()
                if (valid(qa, qb)) return Parsed(qa, qb, null, null, null)
            }
            return Parsed(la, lo, qq, null, null)
        }
        return Parsed(la, lo, null, null, null)
    }

    private fun parseWebUrl(u: String): Parsed {
        val host = hostOf(u).lowercase()
        val path = pathOf(u)

        // OpenStreetMap: ?mlat=..&mlon=.. یا #map=z/lat/lon
        if (host.contains("openstreetmap")) {
            val q = queryParams(u.substringAfter('?', ""))
            val mlat = q["mlat"]?.toDoubleOrNull()
            val mlon = q["mlon"]?.toDoubleOrNull()
            if (valid(mlat, mlon)) return Parsed(mlat, mlon, null, null, null)
            val hash = u.substringAfter('#', "")
            val m = Regex("map=(\\d+)/(-?\\d+\\.\\d+)/(-?\\d+\\.\\d+)").find(hash)
            if (m != null) {
                val la = m.groupValues[2].toDoubleOrNull(); val lo = m.groupValues[3].toDoubleOrNull()
                if (valid(la, lo)) return Parsed(la, lo, null, null, null)
            }
            return Parsed(null, null, null, null, null)
        }

        // لینک‌های کوتاه — نیازمند باز شدن از اینترنت
        if (host.endsWith("goo.gl") || host.endsWith("tinyurl.com") || host.endsWith("bit.ly")) {
            return Parsed(null, null, null, null, u)
        }

        // Google Maps
        val isMaps = host.contains("maps.google") || (host.contains("google.") && path.startsWith("/maps"))
        if (isMaps) {
            val q = queryParams(u.substringAfter('?', ""))
            fun pair(s: String?): Pair<Double, Double>? {
                if (s == null) return null
                val pp = s.split(",").map { it.trim().toDoubleOrNull() }
                if (pp.size >= 2 && pp[0] != null && pp[1] != null && valid(pp[0], pp[1])) return Pair(pp[0]!!, pp[1]!!)
                return null
            }
            pair(q["destination"] ?: q["daddr"])?.let { return Parsed(it.first, it.second, null, null, null) }
            pair(q["ll"])?.let { return Parsed(it.first, it.second, null, null, null) }
            pair(q["saddr"]?.takeIf { it.contains(",") })?.let { return Parsed(it.first, it.second, null, null, null) }
            val qq = q["q"] ?: q["query"]
            if (qq != null && qq.isNotBlank()) {
                val qp = pair(qq)
                if (qp != null) return Parsed(qp.first, qp.second, null, null, null)
                return Parsed(null, null, null, qq, null)
            }
            val at = Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").find(u)
            if (at != null) {
                val la = at.groupValues[1].toDoubleOrNull(); val lo = at.groupValues[2].toDoubleOrNull()
                if (valid(la, lo)) return Parsed(la, lo, null, null, null)
            }
            return Parsed(null, null, null, null, null)
        }
        return Parsed(null, null, null, null, null)
    }

    /** بازکردن لینک کوتاه و خواندن نشانی نهایی (سقف چند ثانیه) */
    fun resolveShortLink(url: String): Parsed? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 4000
            c.readTimeout = 3000
            c.setRequestProperty("User-Agent", Config.USER_AGENT)
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (loc.isNullOrBlank()) null else fromUriString(loc)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- کمکی‌های رشته‌محض (قابل آزمون بدون اندروید) ----------
    private fun hostOf(url: String): String {
        return try {
            val u = url.substringAfter("://")
            u.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore('@')
        } catch (e: Exception) { "" }
    }

    private fun pathOf(url: String): String {
        val afterHost = url.substringAfter("://")
        val pathPart = afterHost.substringAfter('/', "")
        return "/" + pathPart.substringBefore('?').substringBefore('#')
    }

    private fun queryParams(query: String, semiColonAware: Boolean = false): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            val k = if (idx > 0) pair.substring(0, idx) else pair
            val v = if (idx > 0) pair.substring(idx + 1) else ""
            try {
                out[java.net.URLDecoder.decode(k, "UTF-8")] =
                    java.net.URLDecoder.decode(v.replace("+", " "), "UTF-8")
            } catch (e: Exception) {
                out[k] = v
            }
            // برخی لینک‌ها از ; به‌جای & استفاده می‌کنند
            if (semiColonAware && v.contains(";")) {
                val semi = v.split(";").getOrNull(1)?.trim()?.toDoubleOrNull()
                // مثلاً «34.5,69.1;20» — زوم در انتها؛ نادیده گرفته می‌شود
            }
        }
        return out
    }

    private fun valid(la: Double?, lo: Double?): Boolean =
        la != null && lo != null && la.isFinite() && lo.isFinite() &&
            la in -90.0..90.0 && lo in -180.0..180.0 && !(la == 0.0 && lo == 0.0)
}
