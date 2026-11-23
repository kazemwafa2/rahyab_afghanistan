package af.rahyab.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * آزمون موتور آفلاین روی داده واقعی.
 *
 * این آزمون فایل‌های بسته کابل (kabul.rafgraph و kabul.rafidx) را بارگذاری می‌کند و
 * یک مسیر واقعی و یک جست‌وجوی واقعی را اجرا می‌کند. اگر فایل‌ها موجود نباشند
 * (مثلاً در ساخت GitHub Actions که داده ندارد) آزمون نادیده گرفته می‌شود.
 *
 * فایل‌های داده در متغیر محیطی RAHYAB_DATA_DIR مشخص می‌شوند.
 *
 * نکته: android.util.Log در آزمون واحد با mockable android.jar صدا زده می‌شود؛
 * build.gradle مقدار unitTests.isReturnDefaultValues = true را لازم دارد.
 */
class OfflineEngineTest {

    private fun dataDir(): File? {
        val p = System.getenv("RAHYAB_DATA_DIR") ?: return null
        val d = File(p)
        return if (d.isDirectory) d else null
    }

    @Test
    fun graphLoadsAndRoutesKabul() {
        val dir = dataDir() ?: return
        val gf = File(dir, "kabul.rafgraph")
        if (!gf.isFile) return // داده موجود نیست؛ آزمون رد می‌شود

        val g = OfflineRouter.Graph.load(gf)
        assertTrue("گراف خیلی کوچک است", g.nodeCount > 10_000)
        assertTrue("گراف یال ندارد", g.edgeCount > 20_000)

        // مسیر واقعی: دارالامان → شهرنو (نقاط عمومی کابل؛ فرودگاه منطقه ممنوعه است و
        // به‌درستی در snap موتر پذیرفته نمی‌شود)
        val fromLat = 34.5237; val fromLon = 69.1314   // دارالامان
        val toLat = 34.5287; val toLon = 69.1758       // شهرنو
        val r = OfflineRouter.route(g, Profile.CAR, fromLat, fromLon, toLat, toLon)
        assertNotNull("مسیر موتر یافت نشد", r)
        val car = r!!
        assertTrue("طول مسیر موتر غیرمعقول: ${car.distanceM}", car.distanceM in 1500.0..25000.0)
        assertTrue("زمان مسیر غیرمعقول", car.durationS > 60)
        assertTrue("نقاط مسیر کم است", car.points.size > 50)
        assertTrue("مرحله‌های ناوبری کم است", car.steps.size >= 2)

        // مسیر پیاده
        val f = OfflineRouter.route(g, Profile.FOOT, fromLat, fromLon, toLat, toLon)
        assertNotNull("مسیر پیاده یافت نشد", f)
        assertTrue("طول مسیر پیاده غیرمعقول", f!!.distanceM in 1500.0..30000.0)
    }

    @Test
    fun indexLoadsAndSearches() {
        val dir = dataDir() ?: return
        val xf = File(dir, "kabul.rafidx")
        if (!xf.isFile) return

        val ix = OfflineGeocoder.Index.load(xf)
        assertTrue("اندیس خیلی کوچک است", ix.recordCount > 1_000)

        // جست‌وجوی دری
        val r1 = ix.search("کابل", null, null)
        assertTrue("«کابل» پیدا نشد", r1.isNotEmpty())
        assertTrue(r1[0].name.contains("کابل"))

        // جست‌وجو با کاف عربی هم باید جواب دهد (نرمال‌سازی)
        val r2 = ix.search("كابل", null, null)
        assertTrue("جست‌وجو با ك عربی کار نکرد (نرمال‌سازی)", r2.isNotEmpty())

        // جست‌وجوی انگلیسی
        val r3 = ix.search("Kabul", null, null)
        assertTrue("Kabul پیدا نشد", r3.isNotEmpty())

        // جست‌وجوی بی‌معنی
        val r4 = ix.search("zzzzzzzz", null, null)
        assertTrue("نتیجه بی‌معنی برگشت", r4.isEmpty())
    }

    @Test
    fun categorySearchFindsPublicPlaces() {
        val dir = dataDir() ?: return
        val xf = File(dir, "kabul.rafidx")
        if (!xf.isFile) return

        val ix = OfflineGeocoder.Index.load(xf)
        val kabulLat = 34.5281; val kabulLon = 69.1723

        // جست‌وجوی دسته‌ای مکان‌های عمومی — دقیقاً همان مسیر Services.searchCategory
        val mosques = ix.searchCategory(OfflineGeocoder.C_RELIGION, kabulLat, kabulLon, 15)
        assertTrue("مسجد در جست‌وجوی دسته‌ای پیدا نشد", mosques.isNotEmpty())

        val hospitals = ix.searchCategory(OfflineGeocoder.C_HEALTH, kabulLat, kabulLon, 15)
        assertTrue("شفاخانه در جست‌وجوی دسته‌ای پیدا نشد", hospitals.isNotEmpty())

        val pharmacies = ix.searchCategory(OfflineGeocoder.C_PHARMACY, kabulLat, kabulLon, 15)
        assertTrue("دواخانه در جست‌وجوی دسته‌ای پیدا نشد", pharmacies.isNotEmpty())

        val markets = ix.searchCategory(OfflineGeocoder.C_MARKET, kabulLat, kabulLon, 15)
        assertTrue("بازار در جست‌وجوی دسته‌ای پیدا نشد", markets.isNotEmpty())

        // جست‌وجوی متنی نام عمومی
        val m = ix.search("مسجد", kabulLat, kabulLon)
        assertTrue("جست‌وجوی متنی «مسجد» نتیجه نداد", m.isNotEmpty())
    }

    /**
     * آزمون مسیر بین‌ولایتی روی گراف پایه کشوری: کابل → غزنی.
     * مسیر از کابل خارج و از میدان وردک می‌گذرد؛ اگر گراف کشوری نصب باشد
     * باید بدون قطع‌شدن در مرز بسته‌ها محاسبه شود.
     */
    @Test
    fun interProvinceRouteOnNationwideGraph() {
        val dir = dataDir() ?: return
        val gf = File(dir, "afghanistan.rafgraph")
        if (!gf.isFile) return // بسته پایه ساخته نشده؛ آزمون رد می‌شود

        val g = OfflineRouter.Graph.load(gf)
        g.nationwide = true // همان رفتار OfflineData در برنامه
        assertTrue("گراف کشوری خیلی کوچک است", g.nodeCount > 50_000)

        // کابل (شهرنو) → غزنی (مرکز ولایت) — دو ولایت، مسیر جاده اصلی
        val fromLat = 34.5287; val fromLon = 69.1758   // کابل
        val toLat = 33.5534; val toLon = 68.4231       // غزنی
        val r = OfflineRouter.route(g, Profile.CAR, fromLat, fromLon, toLat, toLon)
        assertNotNull("مسیر آفلاین کابل→غزنی یافت نشد", r)
        val car = r!!
        // فاصله جاده‌ای کابل-غزنی حدود ۱۳۰-۱۶۰ کیلومتر
        assertTrue("طول مسیر بین‌ولایتی غیرمعقول: ${car.distanceM / 1000}km",
            car.distanceM in 100_000.0..250_000.0)
        assertTrue("زمان مسیر بین‌ولایتی غیرمعقول", car.durationS in 3600.0..60_000.0)
        assertTrue("نقاط مسیر بین‌ولایتی کم است", car.points.size > 200)

        // مسیر طولانی‌تر: کابل → قندهار (جاده حلقوی، سه ولایت)
        val toKandaharLat = 31.6133; val toKandaharLon = 65.7100
        val r2 = OfflineRouter.route(g, Profile.CAR, fromLat, fromLon, toKandaharLat, toKandaharLon)
        assertNotNull("مسیر آفلاین کابل→قندهار یافت نشد", r2)
        assertTrue("طول مسیر کابل-قندهار غیرمعقول: ${r2!!.distanceM / 1000}km",
            r2.distanceM in 400_000.0..650_000.0)
    }

    /** مسیرهای جایگزین به سبک نشان/بلد: حداقل دو مسیر متمایز با زمان و فاصله */
    @Test
    fun alternativeRoutesAreDistinct() {
        val dir = dataDir() ?: return
        val gf = File(dir, "kabul.rafgraph")
        if (!gf.isFile) return

        val g = OfflineRouter.Graph.load(gf)
        // مسیر درون‌شهری با گزینه‌های جایگزین واقعی (دارالامان → شهرنو)
        val alts = OfflineRouter.routeWithAlternatives(
            g, Profile.CAR, 34.5237, 69.1314, 34.5287, 69.1758, 3
        )
        assertTrue("مسیری یافت نشد", alts.isNotEmpty())
        assertTrue("مسیر جایگزین تولید نشد (تعداد: ${alts.size})", alts.size >= 2)
        // مرتب‌سازی بر اساس زمان: اولین مسیر سریع‌ترین است
        for (i in 1 until alts.size) {
            assertTrue("مرتب‌سازی زمانی خراب است", alts[i].durationS >= alts[i - 1].durationS)
        }
        // مسیرها باید متمایز باشند (فاصله/زمان متفاوت)
        val d0 = alts[0].distanceM
        val distinct = alts.any { Math.abs(it.distanceM - d0) > 50.0 }
        assertTrue("همه مسیرها کپی هم‌اند", distinct)
    }

    /**
     * پیاده روی گراف کشوری — محدودیت مستند: گراف پایه فقط راه‌های اصلی دارد؛
     * مسیر پیاده باید روی دهلیز همان راه اصلی کار کند (مسیرهای محلی پیاده
     * با بسته ولایتی یا آنلاین پوشش داده می‌شوند).
     */
    @Test
    fun footRouteOnNationwideGraph() {
        val dir = dataDir() ?: return
        val gf = File(dir, "afghanistan.rafgraph")
        if (!gf.isFile) return

        val g = OfflineRouter.Graph.load(gf)
        g.nationwide = true
        // دو نقطه درون‌شهری کابل نزدیک راه اصلی (شهرنو → شاه دو شمشیره) — حدود ۸۰۰ متر
        val r = OfflineRouter.route(g, Profile.FOOT, 34.5287, 69.1758, 34.5310, 69.1690)
        assertNotNull("مسیر پیاده روی راه اصلی یافت نشد", r)
        assertTrue("طول مسیر پیاده غیرمعقول", r!!.distanceM in 300.0..15_000.0)
    }
}
