package af.rahyab.app

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * هشدار هوشمند مسیرمحور — بخش ۱۵ و ۱۶ سند جامع.
 *
 * ورودی: مسیر فعال + پیشرفت جاری روی مسیر + وضعیت‌های ترافیک و گزارش‌های قطعه‌دار.
 * خروجی: هشدارهایی که «در جلوِ رو» و «در جهت حرکت» و داخل فاصلهٔ هشدار وضعیت‌اند.
 *
 * قواعد کلیدی سند:
 *  - رانندهٔ بی‌ربط هشدار نمی‌گیرد: فقط قطعه‌های جلوِ رو و هم‌جهت مسیر (بند ۱۵)
 *  - حادثهٔ یک‌طرفه به‌طور خودکار به جهت مقابل نسبت داده نمی‌شود؛ فقط انواعی که
 *    واقعاً هر دو جهت را می‌بندند (انسداد/ریزش/سیلاب) هر دو جهت را خبر می‌کنند
 *  - فاصلهٔ هشدار بر اساس نوع سرک + شدت (بخش ۱۶): شهری ۵۰۰م–۱ک، برون‌شهری ۲–۳ک،
 *    خطر جدی ۳–۵ک — در TrafficRules.alertAheadM
 *  - ضدتکرار: هر قطعه/وضعیت فقط یک‌بار اعلام؛ با تشدید وضعیت دوباره اعلام؛
 *    با دور شدن (بازگشت به مسیر) دوباره مسلح می‌شود (هم‌سان با AlertEngine)
 *
 * کاملاً خالص تا در آزمون واحد JVM پوشش شود.
 */
object RouteAlerts {

    /** بیشینهٔ نگاه به جلو — فراتر از آن بررسی نمی‌شود (صرفه‌جویی محاسبه) */
    const val MAX_LOOKAHEAD_M = 6000.0

    /** یک هشدار تولیدشده */
    data class Event(
        val key: String,      // کلید یکتا برای ضدتکرار
        val clip: String,     // کلید عبارت صوتی/متنی (همان کلیدهای AlertEngine)
        val state: String?,   // وضعیت ترافیک (برای ساختن جمله) یا null برای گزارش
        val roadName: String?,
        val aheadM: Double
    )

    /** وضعیت مسلح موتور میان قرائت‌ها */
    data class St(val armed: Set<String> = emptySet())

    /** وضعیت ترافیک یک قطعه برای ارزیابی */
    data class Congestion(
        val seg: String, val state: String, val roadType: String, val roadName: String?
    )

    /** گزارش قطعه‌دار دیگران برای هشدار زودهنگام */
    data class ReportAhead(
        val key: String, val type: String, val severity: String?,
        val lat: Double, val lon: Double, val dirBin: Int?
    )

    /**
     * فاصلهٔ «جلوِ رو» یک نقطه روی مسیر نسبت به پیشرفت جاری.
     * خروجی null = نقطه پشت سر یا روی مسیر نیست.
     * نقاط مسیر (lon, lat) — پیشرفت جاری progressM (متر از مبدأ مسیر).
     */
    fun aheadOnRoute(
        points: List<Pair<Double, Double>>,
        progressM: Double, lat: Double, lon: Double
    ): Double? {
        if (points.size < 2) return null
        val k = cos(Math.toRadians(lat))
        var cum = 0.0
        var best = Double.MAX_VALUE
        var bestProg = 0.0
        for (i in 0 until points.size - 1) {
            val (aLon, aLat) = points[i]
            val (bLon, bLat) = points[i + 1]
            val ax = (aLon - lon) * 111320.0 * k
            val ay = (aLat - lat) * 110540.0
            val bx = (bLon - lon) * 111320.0 * k
            val by = (bLat - lat) * 110540.0
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            val segLen = sqrt(l2)
            if (d < best) {
                best = d
                bestProg = cum + t * segLen
            }
            cum += segLen
        }
        if (best == Double.MAX_VALUE) return null
        // نقطهٔ نزدیکِ مسیر (کمتر از ۱٫۵ کیلومتر از خط مسیر) جلوِ رو شمرده می‌شود
        if (best > 1500.0) return null
        val ahead = bestProg - progressM
        return if (ahead > 0.0) ahead else null
    }

    /**
     * یک گام ارزیابی. خروجی: وضعیت تازه + هشدارهای تازه.
     */
    fun evaluate(
        points: List<Pair<Double, Double>>,
        progressM: Double,
        routeBearing: Double,
        congestions: List<Congestion>,
        reports: List<ReportAhead>,
        st: St
    ): Pair<St, List<Event>> {
        val out = ArrayList<Event>()
        val armed = HashSet(st.armed)
        val routeBin = RoadSegments.bearingBin(routeBearing)

        // ---------- ترافیک سرعت‌محور (موتور بخش ۱۰ تا ۱۴) ----------
        for (c in congestions) {
            if (c.state == TrafficRules.ST_NORMAL || c.state == TrafficRules.ST_INSUFFICIENT ||
                c.state == TrafficRules.ST_EXPIRED) continue
            val center = RoadSegments.centerOf(c.seg) ?: continue
            // جهت‌محوری (بند ۱۵): خانهٔ جهت قطعه باید با جهت مسیر هم‌راستا باشد
            val bin = c.seg.substringAfterLast('_').toIntOrNull()
            if (bin != null && !RoadSegments.sameDirection(bin, routeBin)) continue
            val ahead = aheadOnRoute(points, progressM, center.first, center.second) ?: continue
            val thr = TrafficRules.alertAheadM(c.state, c.roadType)
            if (ahead > MAX_LOOKAHEAD_M) { armed.removeAll { it.startsWith("${c.seg}:") }; continue }
            if (ahead <= thr) {
                val key = "${c.seg}:${c.state}"
                if (!armed.contains(key)) {
                    armed.add(key)
                    out.add(Event(key, clipForState(c.state), c.state, c.roadName, ahead))
                }
            } else if (ahead > thr * 1.6) {
                // دور شد — برای هشدار دوباره در بازگشت مسلح شود
                armed.removeAll { it.startsWith("${c.seg}:") }
            }
        }

        // ---------- هشدار زودهنگام گزارش‌های قطعه‌دار دیگران ----------
        for (r in reports) {
            val far = TrafficRules.farReportAlertM(r.type, r.severity)
            if (far <= 0.0) continue
            // انواع بسته‌کنندهٔ جاده هر دو جهت؛ بقیه فقط جهت هم‌راستا (بند ۱۵)
            if (r.dirBin != null && !TrafficRules.affectsBothDirections(r.type) &&
                !RoadSegments.sameDirection(r.dirBin, routeBin)
            ) continue
            val ahead = aheadOnRoute(points, progressM, r.lat, r.lon) ?: continue
            if (ahead > MAX_LOOKAHEAD_M) { armed.remove(r.key); continue }
            if (ahead <= far) {
                if (!armed.contains(r.key)) {
                    armed.add(r.key)
                    val clip = AlertEngine.clipFor(r.type) ?: "alert_hazard"
                    out.add(Event(r.key, clip, null, null, ahead))
                }
            } else if (ahead > far * 1.6) {
                armed.remove(r.key)
            }
        }
        return Pair(St(armed), out)
    }

    /** کلیپ هشدار بر اساس شدت وضعیت ترافیک */
    fun clipForState(state: String): String = when (state) {
        TrafficRules.ST_SEVERE, TrafficRules.ST_EXTREME, TrafficRules.ST_CLOSED -> "slow_down"
        else -> "alert_traffic"
    }
}
