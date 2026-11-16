package af.rahyab.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
class RahyabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // سرویس‌ها، کش شبکه و مدیریت بسته‌های آفلاین
        Services.init(this)
        NavigationController.init(this)
        // زبان پیش‌فرض: دری افغانستان (تا وقتی کاربر زبان دیگری انتخاب نکرده)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa-AF"))
        }
        resetLanguageOnceAfterUpdate()
        // همگام‌سازی گزارش‌ها (بند ۱۹ سند جامع): نخستین ارسال صف در شروع فرایند +
        // زمان‌بندی دوره‌ای WorkManager — هر ۳ ساعت با اتصال اینترنت
        ReportsModule.syncNow(this)
        ReportsSyncWorker.schedule(this)
        // موتور ترافیک (بخش ۱۱ و ۱۹): ارسال صف نمونه‌ها + تازه‌سازی وضعیت‌ها —
        // فقط با اینترنت؛ هیچ موقعیت‌گیری پس‌زمینه‌ای انجام نمی‌شود (بخش ۲۲ و ۲۴)
        TrafficSync.flushSamplesAsync(this)
        TrafficSyncWorker.schedule(this)
        // بررسی خودکار هفتگی نسخهٔ جدید؛ اعلان فقط با اینترنت و نصب با تأیید کاربر
        UpdateCheckWorker.schedule(this)
    }

    /**
     * یک‌بار پس از به‌روزرسانی به نسخهٔ ۲۲: بازگشت به زبان پیش‌فرض دری.
     * روی برخی گوشی‌ها (اندروید ۱۳+) زبان انگلیسی در تنظیمات سیستم برای برنامه
     * ثبت شده بود و با هر به‌روزرسانی انگلیسی می‌ماند؛ اینجا یک‌بار به دری
     * برمی‌گردیم و پس از آن انتخاب خود کاربر همیشه محترم شمرده می‌شود
     * (پرچم ذخیره می‌شود و این کار هرگز دوباره تکرار نمی‌شود).
     */
    private fun resetLanguageOnceAfterUpdate() {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        if (sp.getInt("lang_reset_v", 0) >= 22) return
        sp.edit().putInt("lang_reset_v", 22).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa-AF"))
    }

}
