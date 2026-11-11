package af.rahyab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * رادیو — بخش دهم چک‌لیست.
 * منبع: Radio Browser API (رایگان، بدون کلید) + فهرست ایستگاه‌های افغان
 * تأییدشده (هر جریان با دانلود اولیه آزمایش شده است؛ تاریخ آزمون: ۲۰۲۶-۱۱-۱۰).
 *
 * نکته مهم داده: در Radio Browser بسیاری از ایستگاه‌های خارجی به اشتباه با
 * کد کشور AF ثبت شده‌اند؛ به همین دلیل فهرست پایه فقط ایستگاه‌هایی است که
 * هویت‌شان به‌طور مستقل تأیید شده. جست‌وجوی زنده API هم انجام می‌شود و
 * نتایج با فهرست تأییدشده ادغام می‌گردند.
 */
object RadioModule {

    class Station(val name: String, val url: String, val tag: String, val verified: Boolean)

    /** ایستگاه‌های افغان تأییدشده — جریان آزمایش‌شده با HTTP 200 */
    val verifiedAfghan = listOf(
        Station("آریانا اف‌ام ۹۳٫۵ کابل", "https://streams.radio.co/sa3345aaa8/listen", "دری", true),
        Station("رادیو کابل", "http://23.81.35.187:8054/stream", "دری", true),
        Station("رادیو سرود", "https://stream.radiosrood.com/", "دری", true),
        Station("بی‌بی‌سی پشتو", "http://stream.live.vc.bbcmedia.co.uk/bbc_pashto_radio", "پشتو", true),
        Station("افغان بیتس رادیو", "https://solid41.streamupsolutions.com/proxy/rvfskeow?mp=/;type=mp3", "دری/موسیقی", true)
    )

    /** دسته‌های رادیو برای فهرست سریع */
    val categories = listOf("همه", "دری", "پشتو", "دین و قرآن", "خبر")

    private fun categorize(name: String, tags: String): String {
        val n = name.lowercase() + " " + tags.lowercase()
        return when {
            n.contains("quran") || n.contains("قرآن") || n.contains("قران") || n.contains("islamic") || n.contains("قرائت") -> "دین و قرآن"
            n.contains("پشتو") || n.contains("pashto") || n.contains("pushto") || n.contains("khogyani") || n.contains("azadi") -> "پشتو"
            n.contains("news") || n.contains("خبر") || n.contains("bbc") || n.contains("آزادی") -> "خبر"
            else -> "دری"
        }
    }

    /** فهرست پایه: ایستگاه‌های تأییدشده، دسته‌بندی‌شده */
    fun baseStations(): List<Station> = verifiedAfghan.map {
        Station(it.name, it.url, categorize(it.name, ""), it.verified)
    }

    /**
     * جست‌وجوی زنده در Radio Browser (اگر انترنت باشد). خطا بی‌صدا →
     * فهرست پایه همیشه در دسترس است.
     */
    fun searchOnline(query: String = "", countryOnly: Boolean = true): List<Station> {
        val q = if (query.isBlank()) "" else "&name=" + java.net.URLEncoder.encode(query, "UTF-8")
        val cc = if (countryOnly && query.isBlank()) "&countrycode=AF" else ""
        val url = "https://de1.api.radio-browser.info/json/stations/search?limit=50&hidebroken=true&order=votes&reverse=true$cc$q"
        return try {
            val body = Http.get(url, readTimeoutMs = 10000)
            val arr = JSONArray(body)
            val out = ArrayList<Station>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val name = s.optString("name", "").trim()
                val stream = s.optString("url_resolved", "").ifEmpty { s.optString("url", "") }
                if (name.isEmpty() || stream.isEmpty()) continue
                // فیلتر آلودگی داده: فقط ایستگاه واقعاً افغان یا با نام مرتبط
                val ccCode = s.optString("countrycode", "")
                val tags = s.optString("tags", "")
                val keep = ccCode == "AF" || name.lowercase().contains("afghan") ||
                    name.lowercase().contains("ariana") || name.lowercase().contains("kabul") ||
                    name.lowercase().contains("watandar") || name.lowercase().contains("killid") ||
                    name.lowercase().contains("arman") || name.lowercase().contains("banu") ||
                    name.lowercase().contains("srood") || name.lowercase().contains("pashto") ||
                    name.lowercase().contains("پشتو") || name.lowercase().contains("کابل") ||
                    tags.lowercase().contains("afghan")
                if (!keep) continue
                out.add(Station(name, stream, categorize(name, tags), false))
            }
            out
        } catch (e: Exception) {
            android.util.Log.d("RadioModule", "جست‌وجوی آنلاین رادیو ناموفق: ${e.message}")
            emptyList()
        }
    }

    /** ترکیب فهرست پایه + نتیجه زنده، بدون تکرار */
    fun list(ctx: Context, query: String = "", category: String? = null): List<Station> {
        val base = baseStations()
        val online = searchOnline(query)
        val all = ArrayList<Station>(base.size + online.size)
        val seen = HashSet<String>()
        for (s in base + online) {
            if (seen.add(s.url)) all.add(s)
        }
        return if (category == null || category == "همه") all
        else all.filter { it.tag == category }
    }

    // ---------- توابع صفحه رادیو (RadioActivity) ----------

    /** ایستگاه‌های تأییدشده — بدون انترنت همیشه نمایش داده می‌شوند */
    fun curated(): List<Station> = baseStations()

    /** فهرست زنده: تأییدشده‌ها + نتایج Radio Browser (با انترنت) — بدون نیاز به Context */
    fun fetchAll(): List<Station> {
        val base = baseStations()
        val online = searchOnline("")
        val all = ArrayList<Station>(base.size + online.size)
        val seen = HashSet<String>()
        for (s in base + online) {
            if (seen.add(s.url)) all.add(s)
        }
        return all
    }

    /** فیلتر بر اساس زبانه‌های صفحه رادیو: همه / دری / پشتو / سایر */
    fun byCategory(stations: List<Station>, cat: String): List<Station> {
        val isPashto = { s: Station ->
            s.tag == "پشتو" || s.name.contains("پشتو") || s.name.lowercase().contains("pashto")
        }
        val isDariLike = { s: Station ->
            s.tag.contains("دری") || s.tag.contains("موسیقی") ||
                (s.tag == "خبر" && !isPashto(s))
        }
        return when (cat) {
            "all" -> stations
            "dari" -> stations.filter(isDariLike)
            "pashto" -> stations.filter(isPashto)
            else -> stations.filterNot { isDariLike(it) || isPashto(it) }
        }
    }
}
