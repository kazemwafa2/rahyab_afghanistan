package af.rahyab.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * همگام‌سازی خودکار گزارش‌ها با WorkManager — بند ۱۹ سند جامع.
 *
 * وظیفه: ارسال گزارش‌های در صف (pending_reports) به سرور و دریافت گزارش‌های
 * زندهٔ دیگر رانندگان — «پس از اتصال اینترنت» به‌صورت خودکار، حتی وقتی کاربر
 * برنامه را باز نکرده است.
 *
 * طراحی:
 *  - هر ۳ ساعت یک‌بار، فقط با اتصال اینترنت (NetworkType.CONNECTED) اجرا می‌شود.
 *  - KEEP: اگر کار از قبل زمان‌بندی شده، دوباره ساخته نمی‌شود.
 *  - گزارش‌های حساس امنیتی و انواع مکان ثابت هرگز در صف نیستند (ReportsModule.add).
 *  - خود عملیات ارسال idempotent است (client_uuid) — اجرای دوباره گزارش تکراری نمی‌سازد.
 *  - در خطای شبکه Result.retry() — WorkManager با backoff دوباره تلاش می‌کند.
 */
class ReportsSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            ReportsModule.syncBlocking(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        /** نام کار یکتا — برای KEEP در زمان‌بندی */
        const val UNIQUE_NAME = "rahyab_reports_sync"

        /**
         * زمان‌بندی دوره‌ای — یک‌بار در RahyabApp.onCreate صدا زده می‌شود.
         * در نبود وابستگی/سرویس سیستم، خطا بی‌صدا گرفته می‌شود تا اجرای برنامه هرگز نشکند.
         */
        fun schedule(ctx: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<ReportsSyncWorker>(3, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
                )
            } catch (e: Exception) { }
        }
    }
}
