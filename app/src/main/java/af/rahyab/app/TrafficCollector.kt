package af.rahyab.app

import android.location.Location
import android.content.Context

/**
 * جمع‌آوری نمونه‌های سرعت — بخش ۱۱ سند جامع.
 *
 * فقط هنگام ناوبری/رانندگی آزاد و فقط نمونه‌های سالم:
 *  - دقت GPS ضعیف (بیش از ۲۵ متر) پذیرفته نمی‌شود (بند ۱۳: GPS ضعیف نباید داده بسازد)
 *  - سرعت زیر ۳ km/h = ایست/چراغ سرخ/پارکینگ خصوصی — هرگز نمونه نمی‌سازد
 *    (بند ۱۳: چراغ و پارکینگ خصوصی نباید ترافیک کاذب بسازد)
 *  - سرعت بیش از ۱۵۰ km/h = پرت GPS — دور انداخته می‌شود
 *  - یک نمونه در هر ۲۰ ثانیه (کمینه‌سازی داده — بخش ۲۴)
 *  - هیچ مختصاتی ذخیره/ارسال نمی‌شود؛ فقط شناسهٔ قطعهٔ شبکه‌ای + سرعت + جهت + دقت
 *  - مشارکت اختیاری است: کلید «اشتراک دادهٔ ترافیک» در تنظیمات (پیش‌فرض روشن با توضیح)
 */
object TrafficCollector {

    private const val MAX_ACCURACY_M = 25f
    private const val MIN_KMH = 3.0
    private const val MAX_KMH = 150.0
    private const val INTERVAL_MS = 20_000L

    private var lastSampleMs = 0L

    /**
     * هر قرائت GPS ناوبری/رانندگی آزاد به اینجا می‌رسد.
     * @param roadName نام سرک فعلی از مسیریاب (برای نمایش «در سرک …») یا null
     */
    fun onFix(ctx: Context, loc: Location, roadName: String?) {
        try {
            if (!TrafficStore.contributionEnabled(ctx)) return
            if (!(NavigationController.navigating || NavigationController.freeMode)) return
            if (!loc.hasAccuracy() || loc.accuracy > MAX_ACCURACY_M) return
            if (!loc.hasSpeed()) return
            val kmh = loc.speed * 3.6
            if (kmh < MIN_KMH || kmh > MAX_KMH) return
            val now = System.currentTimeMillis()
            if (now - lastSampleMs < INTERVAL_MS) return
            lastSampleMs = now
            // جهت: از خود قرائت GPS؛ در ناوبری از قطعهٔ مسیر دقیق‌تر است
            val bearing = if (loc.hasBearing()) loc.bearing.toDouble()
                          else NavigationController.lastSnapBearing
            if (bearing <= 0.0 && !loc.hasBearing()) return
            val seg = RoadSegments.cellId(loc.latitude, loc.longitude, bearing)
            TrafficStore.queueSample(ctx, seg, kmh, bearing.toInt(), loc.accuracy.toInt(), roadName)
            // هر ۸ نمونه یک‌بار تلاش ارسال — در نبود اینترنت نمونه در صف می‌ماند
            if (TrafficStore.pendingCount(ctx) >= 8) TrafficSync.flushSamplesAsync(ctx)
        } catch (e: Exception) { }
    }

    /** هنگام پایان ناوبری: تلاش نهایی ارسال + پاک‌سازی نمونه‌های کهنه */
    fun onSessionEnd(ctx: Context) {
        try { TrafficSync.flushSamplesAsync(ctx) } catch (e: Exception) { }
        try { TrafficStore.purgeOld(ctx) } catch (e: Exception) { }
    }
}
