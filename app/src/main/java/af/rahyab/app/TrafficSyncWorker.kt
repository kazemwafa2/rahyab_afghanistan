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
 * همگام‌سازی خودکار موتور ترافیک — بخش ۱۹ سند جامع (WorkManager).
 *
 * وظایف (فقط با اتصال اینترنت، هر ۳۰ دقیقه):
 *  ۱) ارسال صف نمونه‌های سرعتِ در انتظار (حتی وقتی برنامه بسته است)
 *  ۲) تازه‌سازی وضعیت‌های زندهٔ پیرامون آخرین موقعیت معلوم — تا هنگام
 *     باز شدن برنامه، نقشه وضعیت نسبتاً تازه داشته باشد
 *
 * حریم خصوصی: نمونه‌ها ناشناس‌اند (seg+سرعت+جهت) و این کار فقط نمونه‌های
 * از پیش جمع‌شده را می‌فرستد — هیچ موقعیت‌گیری پس‌زمینه‌ای انجام نمی‌دهد
 * (بند ۲۲: هیچ ردیابی مخفیانه‌ای وجود ندارد).
 */
class TrafficSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            TrafficSync.flushSamplesBlocking(ctx)
            TrafficStore.purgeOld(ctx)
            // آخرین ناحیهٔ نقشهٔ معلوم از ترجیحات برنامه (بدون موقعیت‌گیری جدید)
            val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val lat = sp.getFloat("map_lat", 0f).toDouble()
            val lon = sp.getFloat("map_lon", 0f).toDouble()
            if (lat != 0.0 && lon != 0.0) TrafficSync.refreshNearBlocking(ctx, lat, lon)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "rahyab_traffic_sync"

        fun schedule(ctx: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<TrafficSyncWorker>(30, TimeUnit.MINUTES)
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
