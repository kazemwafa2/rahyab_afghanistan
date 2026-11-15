package af.rahyab.app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlin.concurrent.thread

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
        installBasePackageIfNeeded()
        // همگام‌سازی گزارش‌ها (بند ۱۹ سند جامع): نخستین ارسال صف در شروع فرایند +
        // زمان‌بندی دوره‌ای WorkManager — هر ۳ ساعت با اتصال اینترنت
        ReportsModule.syncNow(this)
        ReportsSyncWorker.schedule(this)
        // موتور ترافیک (بخش ۱۱ و ۱۹): ارسال صف نمونه‌ها + تازه‌سازی وضعیت‌ها —
        // فقط با اینترنت؛ هیچ موقعیت‌گیری پس‌زمینه‌ای انجام نمی‌شود (بخش ۲۲ و ۲۴)
        TrafficSync.flushSamplesAsync(this)
        TrafficSyncWorker.schedule(this)
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

    /**
     * نصب خودکار «بسته پایه کشوری» در نخستین اجرا — از دارایی‌های خود APK،
     * بدون انترنت و بدون هیچ اقدامی از کاربر. برنامه از همان ابتدا نقشه کل
     * کشور، مسیریابی بین‌ولایتی راه‌های اصلی و جست‌وجوی سراسری دارد.
     */
    private fun installBasePackageIfNeeded() {
        if (!OfflineData.needsBaseInstall()) return
        thread(name = "base-install") {
            val t0 = System.currentTimeMillis()
            val inst = OfflineData.installFromAsset(this, Config.BASE_PACKAGE_ASSET)
            if (inst != null) {
                Log.i("RahyabApp", "بسته پایه کشوری نصب شد (${inst.id}, ${inst.sizeBytes / 1048576}MB) در ${System.currentTimeMillis() - t0}ms")
            } else {
                Log.e("RahyabApp", "نصب بسته پایه ناموفق بود؛ کاربر می‌تواند بسته‌ها را دستی نصب کند")
            }
        }
    }
}
