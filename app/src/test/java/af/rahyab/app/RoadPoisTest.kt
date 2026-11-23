package af.rahyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون‌های ضمیمهٔ ۱ — سیستم جامع اطلاعات جاده‌ای
 *  - نقشهٔ دسته‌بندی → نوع هشدار و کلیپ صوتی
 *  - مکان‌های ثابت در شعاع اعلام می‌شوند (کمره، دست‌انداز، عوارض، مرز، پل، تونل)
 *  - مکان‌های بدون دستهٔ معتبر یا دستهٔ پنهان هشدار نمی‌شوند
 *  - سرعت مجاز فقط برای مکان تأییدشده قطعی است
 */
class RoadPoisTest {

    @Test
    fun categoriesAreConsistent() {
        for (c in RoadPois.CATEGORIES) {
            assertTrue("دستهٔ تکراری: ${c.id}", RoadPois.category(c.id) === c)
            assertTrue("رنگ نامعتبر: ${c.id}", c.color.startsWith("#") && c.color.length == 7)
            val at: String? = c.alertType
            if (at != null) {
                assertTrue("نوع هشدار نامعتبر برای ${c.id}: $at", ReportsModule.validType(at))
                assertTrue("کلیپ صوتی ندارد: ${c.id}", AlertEngine.clipFor(at) != null)
            }
        }
    }

    @Test
    fun alertClipsForPoiTypes() {
        assertEquals("alert_cam", AlertEngine.clipFor(ReportsModule.TYPE_SPEED_CAMERA))
        assertEquals("alert_toll", AlertEngine.clipFor(ReportsModule.TYPE_TOLL))
        assertEquals("alert_border", AlertEngine.clipFor(ReportsModule.TYPE_BORDER))
        assertEquals("alert_bridge", AlertEngine.clipFor(ReportsModule.TYPE_BRIDGE))
        assertEquals("alert_tunnel", AlertEngine.clipFor(ReportsModule.TYPE_TUNNEL))
        assertEquals("alert_trafficlight", AlertEngine.clipFor(ReportsModule.TYPE_TRAFFIC_LIGHT))
    }

    @Test
    fun poiNearRouteTriggersAlert() {
        // پل ثبت‌شده ۲۰۰ متری مسیر → هشدار alert_bridge یک بار
        val rep = AlertEngine.ReportPt(901L, ReportsModule.TYPE_BRIDGE, 34.5300, 69.1723)
        val cfg = AlertEngine.Config()
        val (st, events) = AlertEngine.evaluate(34.5281, 69.1723, 40.0, listOf(rep), cfg, AlertEngine.State(), 1L)
        assertEquals(1, events.size)
        assertEquals("alert_bridge", events[0].clip)
        assertEquals(901L, events[0].reportId)
        // در همان شعاع دوباره اعلام نمی‌شود
        val (_, ev2) = AlertEngine.evaluate(34.5282, 69.1724, 40.0, listOf(rep), cfg, st, 2000L)
        assertEquals(0, ev2.size)
    }

    @Test
    fun disabledTypeDoesNotAlert() {
        val cfg = AlertEngine.Config(toll = false)
        val rep = AlertEngine.ReportPt(902L, ReportsModule.TYPE_TOLL, 34.5300, 69.1723)
        val (_, events) = AlertEngine.evaluate(34.5281, 69.1723, 40.0, listOf(rep), cfg, AlertEngine.State(), 1L)
        assertEquals(0, events.size)
    }

    @Test
    fun confirmedSpeedLimitOnlyForVerified() {
        val props = org.json.JSONObject().put("speed_limit", 60)
        val official = RoadPois.Poi(1L, "پل", "bridge", null, 34.5, 69.1, null, null, null, null, null,
            props, "official", "official", 0L, null, true)
        val unverified = official.copy(verificationStatus = "unverified")
        assertEquals(60, official.confirmedSpeedLimit)
        assertEquals(null, unverified.confirmedSpeedLimit)
    }

    @Test
    fun unknownCategoryIsRejected() {
        assertFalse(RoadPois.isKnownCategory("not_a_category"))
        assertTrue(RoadPois.isKnownCategory("speed_camera"))
        assertTrue(RoadPois.isKnownCategory("toll_plaza"))
        assertTrue(RoadPois.isKnownCategory("border_crossing"))
    }
}
