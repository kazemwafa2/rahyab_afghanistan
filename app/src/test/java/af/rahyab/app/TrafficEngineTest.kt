package af.rahyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * آزمون‌های موتور ترافیک — بخش ۱۰ تا ۱۶ سند جامع.
 * همهٔ منطق‌های آزمون‌شده کاملاً خالص‌اند (بدون اندروید).
 */
class TrafficEngineTest {

    // ---------- بخش ۱۰: قطعه‌بندی شبکه‌ای ----------

    @Test
    fun `segment id is stable for same location and bearing`() {
        val a = RoadSegments.cellId(34.5281, 69.1723, 90.0)
        val b = RoadSegments.cellId(34.5281, 69.1723, 90.0)
        assertEquals(a, b)
        // بارگذاری دوباره/رانندهٔ دیگر → همان شناسه
        assertEquals(a, RoadSegments.cellId(34.52835, 69.17245, 92.0))
    }

    @Test
    fun `opposite directions get different segment ids`() {
        val fwd = RoadSegments.cellId(34.5281, 69.1723, 90.0)   // شرق
        val bwd = RoadSegments.cellId(34.5281, 69.1723, 270.0)  // غرب
        assertTrue(fwd != bwd)
    }

    @Test
    fun `segment id format and center roundtrip`() {
        val id = RoadSegments.cellId(34.5281, 69.1723, 45.0)
        assertTrue(Regex("^g-?\\d+_-?\\d+_[0-7]$").matches(id))
        val c = RoadSegments.centerOf(id)
        assertNotNull(c)
        // مرکز خانه در فاصلهٔ کمتر از نیم‌خانه (≈۲۸۰ متر) از نقطهٔ اصلی
        val d = Geo.dist(34.5281, 69.1723, c!!.first, c.second)
        assertTrue("center distance $d", d < 400.0)
    }

    @Test
    fun `bearing bins are centered on multiples of 45`() {
        assertEquals(2, RoadSegments.bearingBin(90.0))   // شرق
        assertEquals(0, RoadSegments.bearingBin(0.0))    // شمال
        assertEquals(0, RoadSegments.bearingBin(359.0))  // نزدیک شمال → خانهٔ ۰
        assertEquals(4, RoadSegments.bearingBin(180.0))  // جنوب
    }

    @Test
    fun `adjacent bins are same direction`() {
        // مسیر با محور ۹۰ → خانهٔ ۲؛ قطعهٔ خانهٔ ۳ (۱۳۵) هم جهتِ همین حرکت شمرده می‌شود
        assertTrue(RoadSegments.sameDirection(2, 3))
        assertTrue(RoadSegments.sameDirection(2, 1))
        assertFalse(RoadSegments.sameDirection(2, 6)) // مقابل
    }

    // ---------- بخش ۱۲: سرعت پایه ----------

    @Test
    fun `baseline follows document ranges`() {
        // میانهٔ بازه‌ها در میانهٔ روز
        assertEquals(27.5, TrafficRules.baselineKmh("city", 12), 0.01)
        assertEquals(40.0, TrafficRules.baselineKmh("main", 12), 0.01)
        assertEquals(55.0, TrafficRules.baselineKmh("intercity", 12), 0.01)
        assertEquals(30.0, TrafficRules.baselineKmh("mountain", 12), 0.01)
        assertEquals(25.0, TrafficRules.baselineKmh("dirt", 12), 0.01)
    }

    @Test
    fun `rush hour lowers baseline`() {
        val noon = TrafficRules.baselineKmh("main", 12)
        val evening = TrafficRules.baselineKmh("main", 17) // ساعت شلوغی عصر
        assertTrue(evening < noon)
        assertTrue(evening >= 5.0)
    }

    // ---------- بخش ۱۳: آستانه‌ها و اعتماد ----------

    @Test
    fun `thresholds match document anchors`() {
        assertEquals(TrafficRules.ST_NORMAL, TrafficRules.stateFor(0.60))
        assertEquals(TrafficRules.ST_SUSPECTED, TrafficRules.stateFor(0.59))
        assertEquals(TrafficRules.ST_SUSPECTED, TrafficRules.stateFor(0.45))
        assertEquals(TrafficRules.ST_MODERATE, TrafficRules.stateFor(0.44))
        assertEquals(TrafficRules.ST_SEVERE, TrafficRules.stateFor(0.34))
        assertEquals(TrafficRules.ST_SEVERE, TrafficRules.stateFor(0.20))
        assertEquals(TrafficRules.ST_EXTREME, TrafficRules.stateFor(0.19))
    }

    @Test
    fun `driver confidence rules`() {
        assertEquals("insufficient", TrafficRules.confidenceFor(2))
        assertEquals("preliminary", TrafficRules.confidenceFor(3))
        assertEquals("preliminary", TrafficRules.confidenceFor(5))
        assertEquals("higher", TrafficRules.confidenceFor(6))
    }

    // ---------- بخش ۱۶: فاصلهٔ هشدار ----------

    @Test
    fun `alert distances follow document ranges`() {
        // شهری: احتمال/متوسط ۵۰۰ متر؛ سنگین/بسیار شدید ۱ کیلومتر
        assertEquals(500.0, TrafficRules.alertAheadM(TrafficRules.ST_SUSPECTED, "city"), 0.1)
        assertEquals(1000.0, TrafficRules.alertAheadM(TrafficRules.ST_EXTREME, "city"), 0.1)
        // برون‌شهری: متوسط ۲ کیلومتر؛ سنگین ۳ کیلومتر؛ خطر جدی ۴ کیلومتر (۳–۵ک سند)
        assertEquals(2000.0, TrafficRules.alertAheadM(TrafficRules.ST_MODERATE, "intercity"), 0.1)
        assertEquals(3000.0, TrafficRules.alertAheadM(TrafficRules.ST_SEVERE, "intercity"), 0.1)
        assertEquals(4000.0, TrafficRules.alertAheadM(TrafficRules.ST_EXTREME, "intercity"), 0.1)
        // عادی و ناکافی هشدار ندارند
        assertEquals(0.0, TrafficRules.alertAheadM(TrafficRules.ST_NORMAL, "main"), 0.1)
        assertEquals(0.0, TrafficRules.alertAheadM(TrafficRules.ST_INSUFFICIENT, "main"), 0.1)
    }

    // ---------- بخش ۱۵: جهت‌محوری گزارش‌ها ----------

    @Test
    fun `closure affects both directions but accident does not`() {
        assertTrue(TrafficRules.affectsBothDirections(ReportsModule.TYPE_ROAD_CLOSED))
        assertTrue(TrafficRules.affectsBothDirections(ReportsModule.TYPE_LANDSLIDE))
        assertTrue(TrafficRules.affectsBothDirections(ReportsModule.TYPE_FLOOD))
        assertFalse(TrafficRules.affectsBothDirections(ReportsModule.TYPE_ACCIDENT))
        // حادثهٔ شدید فقط هشدار دور دارد
        assertEquals(2500.0, TrafficRules.farReportAlertM(ReportsModule.TYPE_ACCIDENT, "high"), 0.1)
        assertEquals(0.0, TrafficRules.farReportAlertM(ReportsModule.TYPE_ACCIDENT, "low"), 0.1)
        assertEquals(3000.0, TrafficRules.farReportAlertM(ReportsModule.TYPE_ROAD_CLOSED, null), 0.1)
    }

    // ---------- بخش ۱۵: محاسبهٔ جلوِ رو ----------

    private val route = listOf(
        69.16 to 34.50, // (lon, lat)
        69.17 to 34.50,
        69.18 to 34.50,
        69.19 to 34.50
    ) // مسیر مستقیم شرق‌رو ≈ ۲٫۷ کیلومتر (هر قطعه ≈ ۹۱۷ متر)

    @Test
    fun `point ahead on route returns positive distance`() {
        // مسیر مستقیم شرق‌رو: هر قطعهٔ ۰٫۰۰۱ درجه ≈ ۹۱۷ متر؛ نقطهٔ lon=69.18
        // دو قطعه از مبدأ ≈ ۱۸۳۴ متر جلوتر است
        val ahead = RouteAlerts.aheadOnRoute(route, 0.0, 34.50, 69.18)
        assertNotNull(ahead)
        assertTrue("ahead=$ahead", ahead!! > 1500.0 && ahead < 2200.0)
    }

    @Test
    fun `point behind vehicle returns null`() {
        // پیشرفت ۲٫۵ کیلومتر → نقطهٔ ≈۱٫۸ کیلومتری پشت سر است
        assertNull(RouteAlerts.aheadOnRoute(route, 2500.0, 34.50, 69.18))
    }

    @Test
    fun `far away point from route line returns null`() {
        // نقطهٔ حدوداً ۱۱ کیلومتر شمالِ مسیر
        assertNull(RouteAlerts.aheadOnRoute(route, 0.0, 34.60, 69.18))
    }

    // ---------- بخش ۱۵–۱۶: ارزیابی کامل ----------

    @Test
    fun `congestion in same direction and within distance alerts once`() {
        val seg = RoadSegments.cellId(34.50, 69.18, 90.0) // جلوِ رو، هم‌جهت شرق
        val c = RouteAlerts.Congestion(seg, TrafficRules.ST_SEVERE, "main", "کابل–جلال‌آباد")
        val (st, events) = RouteAlerts.evaluate(route, 0.0, 90.0, listOf(c), emptyList(), RouteAlerts.St())
        assertEquals(1, events.size)
        assertEquals(seg, events[0].key.split(":")[0])
        // اعلام دوباره در گام بعدی نمی‌شود (ضدتکرار)
        val (st2, events2) = RouteAlerts.evaluate(route, 100.0, 90.0, listOf(c), emptyList(), st)
        assertEquals(0, events2.size)
        // تشدید وضعیت → هشدار تازه
        val extreme = RouteAlerts.Congestion(seg, TrafficRules.ST_EXTREME, "main", null)
        val (st3, events3) = RouteAlerts.evaluate(route, 200.0, 90.0, listOf(extreme), emptyList(), st2)
        assertEquals(1, events3.size)
    }

    @Test
    fun `opposite direction congestion does not alert`() {
        // قطعهٔ شرق‌رو اما مسیر غرب‌رو
        val seg = RoadSegments.cellId(34.50, 69.18, 90.0)
        val c = RouteAlerts.Congestion(seg, TrafficRules.ST_SEVERE, "main", null)
        val (_, events) = RouteAlerts.evaluate(route, 0.0, 270.0, listOf(c), emptyList(), RouteAlerts.St())
        assertEquals(0, events.size)
    }

    @Test
    fun `closure report warns opposite direction too`() {
        // انسداد در خانهٔ جهت‌دار شرقی؛ مسیر غربی — انسداد هر دو جهت را خبر می‌کند
        // اما اینجا بدون قطعه‌سازی گزارش (dirBin=null) و از فاصلهٔ ۶٫۲ کیلومتری…
        // مرز MAX_LOOKAHEAD_M = ۶ کیلومتر → نقطهٔ نزدیک‌تر می‌سازیم
        val nearRoute = listOf(69.16 to 34.50, 69.17 to 34.50)
        val rep = RouteAlerts.ReportAhead(
            "rp:1:road_closed", ReportsModule.TYPE_ROAD_CLOSED, null, 34.50, 69.165, null
        )
        val (_, events) = RouteAlerts.evaluate(nearRoute, 0.0, 270.0, emptyList(), listOf(rep), RouteAlerts.St())
        assertEquals(1, events.size)
    }
}
