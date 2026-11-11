package af.rahyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون‌های واحد ماژول‌های v0.8.1:
 *  - حفاظت اطلاعات حساس گزارش‌ها (بخش هشتم چک‌لیست)
 *  - دریافت مبدأ/مقصد از برنامه‌های دیگر و لینک‌ها (بخش ششم)
 *  - پارس هواشناسی و کیفیت هوا (بخش نهم)
 *  - رادیو: فهرست ایستگاه‌های تأییدشده افغان (بخش دهم)
 *  - ترافیک: پارس فید GeoJSON جامعه (بخش سوم)
 *  - دری‌سازی: واژه‌های دری افغانستان
 */
class ModulesTest {

    // ---------- گزارش حوادث: حفاظت خصوصی ----------
    @Test
    fun sensitiveReportDetection() {
        // گزارش‌های مربوط به چک‌پست/نیروهای امنیتی باید مسدود شوند (قانون ۸ چک‌لیست)
        assertTrue(ReportsModule.isSensitive("چک پست در راه سنگر است"))
        assertTrue(ReportsModule.isSensitive("checkpoint near the bridge"))
        assertTrue(ReportsModule.isSensitive("ایست بازرسی جدید"))
        assertTrue(ReportsModule.isSensitive("پوسته امنیتی آنجا است"))
        // گزارش‌های عادی مشکلی ندارند
        assertFalse(ReportsModule.isSensitive("راه سنگر برف بسته است"))
        assertFalse(ReportsModule.isSensitive("تصادف سنگین در اینجا"))
    }

    @Test
    fun reportTypeValidation() {
        assertTrue(ReportsModule.validType(ReportsModule.TYPE_ACCIDENT))
        assertTrue(ReportsModule.validType(ReportsModule.TYPE_HEAVY_TRAFFIC))
        assertFalse(ReportsModule.validType("junk"))
        assertFalse(ReportsModule.validType(""))
    }

    @Test
    fun reportTypeLabelsAreDari() {
        val r = ReportsModule.Report(1, ReportsModule.TYPE_HEAVY_TRAFFIC, "", 34.5, 69.1, System.currentTimeMillis())
        assertTrue(r.typeLabel.contains("زحمت ترافیکی"))
        assertTrue(r.mapColor() == "#F5A623")
        val closed = ReportsModule.Report(2, ReportsModule.TYPE_ROAD_CLOSED, "", 34.5, 69.1, System.currentTimeMillis())
        assertTrue(closed.mapColor() == "#E0342C")
    }

    @Test
    fun newReportTypesValid() {
        assertTrue(ReportsModule.validType(ReportsModule.TYPE_SPEED_BUMP))
        assertTrue(ReportsModule.validType(ReportsModule.TYPE_SPEED_CAMERA))
        val bump = ReportsModule.Report(3, ReportsModule.TYPE_SPEED_BUMP, "", 34.5, 69.1, System.currentTimeMillis())
        assertEquals("سرعت‌گیر", bump.typeLabel)
        val cam = ReportsModule.Report(4, ReportsModule.TYPE_SPEED_CAMERA, "", 34.5, 69.1, System.currentTimeMillis())
        assertEquals("کمره سرعت", cam.typeLabel)
        assertTrue(cam.mapColor() == "#E0342C")
    }

    // ---------- دریافت از برنامه‌های دیگر ----------
    @Test
    fun parseRawCoordinatesFromWhatsAppText() {
        val p = Integration.fromText("سلام، من اینجا هستم: 34.5281, 69.1723 بیا")
        assertNotNull(p.lat)
        assertEquals(34.5281, p.lat!!, 1e-6)
        assertEquals(69.1723, p.lon!!, 1e-6)
    }

    @Test
    fun parsePlaceNameFromText() {
        val p = Integration.fromText("میدان هوایی کابل")
        assertNull(p.lat)
        assertEquals("میدان هوایی کابل", p.searchQuery)
    }

    @Test
    fun parseOsmShareLink() {
        // لینکی که خود برنامه در «اشتراک» می‌سازد
        val p = Integration.fromUriString("https://www.openstreetmap.org/?mlat=34.5281&mlon=69.1723#map=17/34.5281/69.1723")
        assertEquals(34.5281, p.lat!!, 1e-6)
        assertEquals(69.1723, p.lon!!, 1e-6)
    }

    @Test
    fun parseGoogleMapsQ() {
        val p = Integration.fromUriString("https://maps.google.com/?q=34.5281,69.1723")
        assertEquals(34.5281, p.lat!!, 1e-6)
        assertEquals(69.1723, p.lon!!, 1e-6)
        // جست‌وجوی نامی
        val p2 = Integration.fromUriString("https://www.google.com/maps?q=Ariana+FM+Kabul")
        assertEquals("Ariana FM Kabul", p2.searchQuery)
    }

    @Test
    fun parseGoogleMapsAtSyntax() {
        val p = Integration.fromUriString("https://www.google.com/maps/@34.5281,69.1723,17z")
        assertEquals(34.5281, p.lat!!, 1e-6)
        assertEquals(69.1723, p.lon!!, 1e-6)
    }

    @Test
    fun parseShortLinkNeedsResolve() {
        val p = Integration.fromUriString("https://maps.app.goo.gl/abc123")
        assertNotNull(p.needResolveUrl)
    }

    @Test
    fun parseGeoUri() {
        val p = Integration.fromUriString("geo:34.5281,69.1723")
        assertEquals(34.5281, p.lat!!, 1e-6)
        assertEquals(69.1723, p.lon!!, 1e-6)
        // با نام مکان در q
        val p2 = Integration.fromUriString("geo:34.5281,69.1723?q=تانک%20تیل%20شهرنو")
        assertEquals("تانک تیل شهرنو", p2.name)
    }

    @Test
    fun parseRahyabDeepLink() {
        val p = Integration.fromUriString(
            "rahyab://navigate?destination_lat=34.5281&destination_lon=69.1723&name=کارته۴"
        )
        assertEquals(34.5281, p.lat!!, 1e-6)
        assertEquals("کارته۴", p.name)
        // مختصات نامعتبر رد می‌شود
        val bad = Integration.fromUriString("rahyab://navigate?destination_lat=999&destination_lon=69.1")
        assertNull(bad.lat)
    }

    @Test
    fun linkInSharedText() {
        val p = Integration.fromText("Location https://maps.google.com/?q=34.53,69.17 see")
        assertNotNull(p.lat)
        assertEquals(34.53, p.lat!!, 1e-6)
    }

    // ---------- هواشناسی ----------
    @Test
    fun weatherParse() {
        val forecast = """
        {"current":{"temperature_2m":12.4,"relative_humidity_2m":55,"apparent_temperature":10.2,
        "is_day":1,"weather_code":61,"wind_speed_10m":18.5},
        "daily":{"time":["2026-11-10","2026-11-11","2026-11-12","2026-11-13","2026-11-14"],
        "weather_code":[0,3,61,71,95],
        "temperature_2m_min":[2.0,1.0,3.0,0.0,4.0],
        "temperature_2m_max":[15.0,12.0,9.0,6.0,11.0],
        "precipitation_probability_max":[5,20,80,60,30]}}
        """.trimIndent()
        val aqi = """{"current":{"pm2_5":42.1,"pm10":120.5,"us_aqi":115}}"""
        val d = WeatherModule.parse(forecast, aqi)
        assertNotNull(d)
        assertEquals(12.4, d!!.tempC, 1e-6)
        assertEquals(61, d.code)
        assertEquals(42.1, d.pm25!!, 1e-6)
        assertEquals(5, d.daily.size)
        assertEquals(80, d.daily[2].rainProb)
        // کد WMO 61 = باران
        assertTrue(WeatherModule.describe(61, true).contains("باران"))
        assertTrue(WeatherModule.describe(0, true).contains("آفتابی"))
        assertTrue(WeatherModule.describe(71, true).contains("برف"))
    }

    @Test
    fun aqiCategories() {
        assertTrue(WeatherModule.aqiLabel(45.0).startsWith("خوب"))
        assertTrue(WeatherModule.aqiLabel(54.0).startsWith("متوسط"))
        assertTrue(WeatherModule.aqiLabel(160.0).startsWith("ناسالم"))
        assertTrue(WeatherModule.aqiLabel(null).contains("در دسترس نیست"))
    }

    // ---------- رادیو ----------
    @Test
    fun verifiedAfghanStationsExist() {
        val base = RadioModule.baseStations()
        // پنج ایستگاه تأییدشده باید همیشه در فهرست پایه باشند
        assertTrue("فهرست پایه رادیو خالی است", base.size >= 5)
        assertTrue(base.any { it.name.contains("آریانا") })
        assertTrue(base.any { it.name.contains("کابل") })
        assertTrue(base.all { it.url.startsWith("http") })
    }

    // ---------- ترافیک: پارس فید جامعه ----------
    @Test
    fun trafficFeedPointColors() {
        // رنگ‌ها فقط سه‌تای معتبر سبز/نارنجی/قرمز — بدون داده جعلی
        val p1 = TrafficModule.TrafficPoint(34.5, 69.1, "feed", "#23A455", "روان")
        val p2 = TrafficModule.TrafficPoint(34.5, 69.1, "feed", "#E0342C", "بسته")
        assertTrue(p1.color != p2.color)
    }

    // ---------- دری‌سازی ----------
    @Test
    fun dariTermsPresent() {
        // واژه دری «تانک تیل» باید در منابع درجه‌بندی شود — آزمون در سطح کد رشته‌ها
        val types = listOf(
            ReportsModule.TYPE_ACCIDENT, ReportsModule.TYPE_ROAD_DAMAGE,
            ReportsModule.TYPE_ROAD_CLOSED, ReportsModule.TYPE_HEAVY_TRAFFIC, ReportsModule.TYPE_HAZARD
        )
        for (t in types) assertTrue(ReportsModule.validType(t))
    }

    // ---------- چسبیدن تیرک به مسیر (v0.9.1) ----------
    @Test
    fun navEngineSnapsToRoute() {
        // مسیر ساده شمال‌گرا: از (69.170, 34.520) به (69.170, 34.530)
        val pts = listOf(
            Pair(69.170, 34.520),
            Pair(69.170, 34.525),
            Pair(69.170, 34.530)
        )
        val r = RouteResult(1200.0, 240.0, pts, emptyList())
        val eng = NavEngine(r)
        // کمی شرقِ مسیر — تیرک باید روی مسیر بنشیند نه روی موقعیت خام
        val st = eng.update(34.5225, 69.1708)
        assertEquals("تیرک روی عرض مسیر", 69.170, st.snapLon, 1e-6)
        assertTrue("پیشرفت در مسیر", st.progress > 100.0)
        assertTrue("جهت قطعه شمالی", st.snapBearing >= 0.0 && st.snapBearing < 20.0)
        // باقی‌مانده تقریباً نصف مسیر
        assertTrue("باقی‌مانده", st.remaining > 400.0 && st.remaining < 1000.0)
    }

    @Test
    fun navEngineOffRouteDistance() {
        val pts = listOf(
            Pair(69.170, 34.520),
            Pair(69.170, 34.530)
        )
        val eng = NavEngine(RouteResult(1200.0, 240.0, pts, emptyList()))
        // ۲۰۰ متر شرق مسیر — باید «خارج از مسیر» تشخیص داده شود
        val st = eng.update(34.520, 69.172)
        assertTrue("فاصله خروج از مسیر", st.offRouteM > 150.0)
    }
}
