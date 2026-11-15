package af.rahyab.app

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * قطعه‌بندی جاده‌ها — بخش ۱۰ سند جامع.
 *
 * طراحی شبکه‌ای (نه وابسته به مسیر): دنیا به خانه‌های ≈۵۵۵ متری تقسیم می‌شود
 * (۰٫۰۰۵ درجهٔ عرض) و جهت حرکت به ۸ خانهٔ ۴۵ درجه‌ای. شناسهٔ هر قطعه از
 * فرمول ساخته می‌شود، پس:
 *  - شناسهٔ ثابت است و با بارگذاری دوباره/رانندهٔ دیگر تغییر نمی‌کند (بند ۱۰)
 *  - دو جهت یک جاده شناسهٔ جدا دارند (بند ۱۰: تفکیک دوطرفه)
 *  - محاسبهٔ مرکز قطعه بدون هیچ سروری ممکن است (هم کلاینت هم سرور)
 *
 * قالب شناسه: «g{lonIdx}_{latIdx}_{dirBin}» — مثال g19714_6932_3
 * هیچ وابستگی به اندروید ندارد تا در آزمون واحد JVM پوشش شود.
 */
object RoadSegments {

    /** بزرگ‌خانهٔ عرض جغرافیایی هر خانه — ≈۵۵۵ متر (بازهٔ ۵۰۰م تا ۱ک سند) */
    const val CELL_DEG = 0.005

    /** بزرگ‌خانهٔ طول جغرافیایی متناسب با عرض — خانه‌ها در کابل تقریباً مربع‌اند */
    fun lonWidthDeg(lat: Double): Double =
        CELL_DEG / maxOf(0.2, cos(Math.toRadians(lat)))

    fun latIndex(lat: Double): Int = floor(lat / CELL_DEG).toInt()

    fun lonIndex(lat: Double, lon: Double): Int =
        floor(lon / lonWidthDeg(lat)).toInt()

    /** خانهٔ جهت: ۰=شمال، ۱=شمال‌شرق، … ۷=شمال‌غرب (میانهٔ هر خانه روی مضرب ۴۵) */
    fun bearingBin(bearing: Double): Int {
        val b = ((bearing % 360.0) + 360.0) % 360.0
        return (Math.round(b / 45.0).toInt()) % 8
    }

    /** مرکز خانهٔ جهت به درجه */
    fun binCenterDeg(bin: Int): Double = ((bin % 8) * 45.0 + 360.0) % 360.0

    /** شناسهٔ پایدار قطعه برای یک قرائت موقعیت + جهت حرکت */
    fun cellId(lat: Double, lon: Double, bearing: Double): String =
        "g${lonIndex(lat, lon)}_${latIndex(lat)}_${bearingBin(bearing)}"

    /** مرکز قطعه از روی شناسه — بدون هیچ پرس‌وجو */
    fun centerOf(segId: String): Pair<Double, Double>? {
        val p = segId.split("_")
        if (p.size != 3 || !p[0].startsWith("g")) return null
        val ix = p[0].substring(1).toIntOrNull() ?: return null
        val iy = p[1].toIntOrNull() ?: return null
        val latC = (iy + 0.5) * CELL_DEG
        val lonC = (ix + 0.5) * lonWidthDeg(latC)
        return Pair(latC, lonC)
    }

    /** کوچک‌ترین زاویهٔ میان دو محور (۰ تا ۱۸۰ درجه) */
    fun angularDiff(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /**
     * آیا دو خانهٔ جهت «یک جهت» شمرده می‌شوند؟
     * تلورانس ۶۷٫۵ درجه (خانهٔ مجاور هم پذیرفته می‌شود) تا حرکت در مرز
     * خانه‌های ۴۵ درجه‌ای داده را دو پاره نکند.
     */
    fun sameDirection(binA: Int, binB: Int): Boolean =
        angularDiff(binCenterDeg(binA), binCenterDeg(binB)) <= 67.5

    /** فاصلهٔ جلوِ رو به متر برای نمایش */
    fun roundM(m: Double): Int = m.roundToInt()
}
