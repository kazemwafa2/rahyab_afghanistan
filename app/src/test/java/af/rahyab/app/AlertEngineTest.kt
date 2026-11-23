package af.rahyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون‌های موتور هشدارها — منطق خالص بدون وابستگی اندروید.
 *  - اعلام یک‌باره هر گزارش در شعاع ۹۰۰ متر
 *  - مسلح‌شدن دوباره پس از خروج از شعاع ۱۴۰۰ متر
 *  - رعایت کلیدهای خاموش/روشن هر نوع هشدار
 *  - هشدار سرعت زیاد با فاصله حداقل ۴۵ ثانیه
 */
class AlertEngineTest {

    private val cfg = AlertEngine.Config()
    private val rep = AlertEngine.ReportPt(11, ReportsModule.TYPE_SPEED_BUMP, 34.5300, 69.1700)

    @Test
    fun reportsAheadAlertOnce() {
        val st = AlertEngine.State()
        val now = 1_000_000L
        val (s1, ev1) = AlertEngine.evaluate(34.5281, 69.1723, 40.0, listOf(rep), cfg, st, now)
        assertEquals(1, ev1.size)
        assertEquals("alert_bump", ev1[0].clip)
        assertEquals(11L, ev1[0].reportId)

        // قرائت بعدی در همان شعاع: هشدار تکرار نمی‌شود
        val (s2, ev2) = AlertEngine.evaluate(34.5285, 69.1725, 40.0, listOf(rep), cfg, s1, now + 1000)
        assertEquals(0, ev2.size)

        // خروج از شعاع مسلح‌شدن: دوباره داخل شعاع → دوباره هشدار
        val far = AlertEngine.evaluate(34.54, 69.20, 40.0, listOf(rep), cfg, s2, now + 2000).first
        assertTrue(far.alerted.isEmpty())
        val (s3, ev3) = AlertEngine.evaluate(34.5281, 69.1723, 40.0, listOf(rep), cfg, far, now + 3000)
        assertEquals(1, ev3.size)
        assertTrue(s3.alerted.contains("11:speed_bump"))
    }

    @Test
    fun disabledTypeNeverAlerts() {
        val off = cfg.copy(bump = false)
        val (st, ev) = AlertEngine.evaluate(34.5281, 69.1723, 40.0, listOf(rep), off, AlertEngine.State(), 0L)
        assertEquals(0, ev.size)
        assertFalse(st.alerted.contains("11:speed_bump"))
    }

    @Test
    fun speedAlertRepeatsAtLeast45sApart() {
        val now = 5_000_000L
        val (s1, ev1) = AlertEngine.evaluate(34.5, 69.1, 110.0, emptyList(), cfg, AlertEngine.State(), now)
        assertEquals(1, ev1.size)
        assertEquals("alert_speed", ev1[0].clip)
        // ۱۰ ثانیه بعد: تکرار نمی‌شود
        val (s2, ev2) = AlertEngine.evaluate(34.5, 69.1, 110.0, emptyList(), cfg, s1, now + 10_000)
        assertEquals(0, ev2.size)
        // ۵۰ ثانیه بعد: دوباره هشدار می‌دهد
        val (_, ev3) = AlertEngine.evaluate(34.5, 69.1, 110.0, emptyList(), cfg, s2, now + 50_000)
        assertEquals(1, ev3.size)
    }

    @Test
    fun speedUnderLimitNoAlert() {
        val (_, ev) = AlertEngine.evaluate(34.5, 69.1, 80.0, emptyList(), cfg, AlertEngine.State(), 0L)
        assertEquals(0, ev.size)
    }

    @Test
    fun everyTypeHasClipAndDisabledKeys() {
        val types = listOf(
            ReportsModule.TYPE_SPEED_BUMP, ReportsModule.TYPE_ACCIDENT,
            ReportsModule.TYPE_HEAVY_TRAFFIC, ReportsModule.TYPE_ROAD_CLOSED,
            ReportsModule.TYPE_HAZARD, ReportsModule.TYPE_SPEED_CAMERA
        )
        for (t in types) {
            assertEquals(t, AlertEngine.clipFor(t) != null, true)
            val off = cfg.copy(
                bump = false, accident = false, traffic = false,
                closed = false, hazard = false, cam = false
            )
            val r = AlertEngine.ReportPt(1, t, 34.5, 69.1)
            val (_, ev) = AlertEngine.evaluate(34.5, 69.1, 0.0, listOf(r), off, AlertEngine.State(), 0L)
            assertEquals("all report alerts must respect disabled config", 0, ev.size)
        }
        assertEquals(null, AlertEngine.clipFor("unknown_type"))
    }
}
