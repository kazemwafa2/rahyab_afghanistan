package af.rahyab.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** بررسی روزانهٔ نسخهٔ جدید در پس‌زمینه + اعلان؛ نصب همچنان با تأیید خود کاربر انجام می‌شود. */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, true)) return Result.success()

        return try {
            val release = withContext(Dispatchers.IO) { Updater.checkLatest() }
            prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            if (release != null && Updater.isNewer(release, ctx)) {
                val lastNotified = prefs.getInt(PREF_LAST_NOTIFIED_CODE, 0)
                if (lastNotified != release.versionCode && postNotification(ctx, release.versionName)) {
                    prefs.edit().putInt(PREF_LAST_NOTIFIED_CODE, release.versionCode).apply()
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun postNotification(ctx: Context, version: String): Boolean {
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val system = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, ctx.getString(R.string.auto_update_channel), NotificationManager.IMPORTANCE_DEFAULT)
            system.createNotificationChannel(channel)
        }

        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_UPDATES, true)
        }
        val pending = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(ctx.getString(R.string.auto_update_notification_title))
            .setContentText(ctx.getString(R.string.auto_update_notification_text, version))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.auto_update_notification_text, version)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) { false }
    }

    companion object {
        const val PREF_ENABLED = "auto_update_enabled"
        const val PREF_LAST_CHECK = "auto_update_last_check"
        const val PREF_LAST_NOTIFIED_CODE = "auto_update_last_notified_code"
        const val EXTRA_OPEN_UPDATES = "af.rahyab.app.OPEN_UPDATES"
        const val UNIQUE_PERIODIC = "rahyab_auto_update_daily"
        const val UNIQUE_ONCE = "rahyab_auto_update_startup"
        private const val CHANNEL_ID = "rahyab_updates"
        private const val NOTIFICATION_ID = 2303
        /** درخواست کارفرما: بررسی خودکار روزانه (روشن/خاموش از تنظیمات) */
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        fun schedule(ctx: Context) {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val wm = WorkManager.getInstance(ctx)
            if (!prefs.getBoolean(PREF_ENABLED, true)) {
                wm.cancelUniqueWork(UNIQUE_ONCE)
                wm.cancelUniqueWork(UNIQUE_PERIODIC)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)

            val last = prefs.getLong(PREF_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - last >= CHECK_INTERVAL_MS) {
                val once = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                    .setConstraints(constraints)
                    .build()
                wm.enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, once)
            }
        }

        fun cancel(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(UNIQUE_ONCE)
            wm.cancelUniqueWork(UNIQUE_PERIODIC)
        }
    }
}
