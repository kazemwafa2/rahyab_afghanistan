package af.rahyab.app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlin.concurrent.thread

class RahyabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // سرویس‌ها، کش شبکه و مدیریت بسته‌های آفلاین
        Services.init(this)
        NavigationController.init(this)
        // زبان پیش‌فرض: دری افغانستان (تا وقتی کاربر زبان دیگری انتخاب نکرده)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa-AF"))
        }
        installBasePackageIfNeeded()
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
