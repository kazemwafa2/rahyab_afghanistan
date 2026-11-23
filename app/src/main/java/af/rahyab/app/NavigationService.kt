package af.rahyab.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * سرویس پیش‌زمینه ناوبری.
 *
 * کارها: نگه داشتن پروسه در اولویت بالا برای ادامه ناوبری با صفحه خاموش،
 * نمایش اعلان دائمی با دستور بعدی و دکمه «توقف».
 *
 * نوع سرویس «location» مطابق سیاست اندروید ۱۴+ در manifest اعلان شده است؛
 * شروع آن فقط در حالی انجام می‌شود که اجازه موقعیت گرفته شده باشد.
 */
class NavigationService : Service() {

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIF_ID = 41
        const val ACTION_STOP = "af.rahyab.app.nav.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, NavigationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NavigationService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.description = getString(R.string.notif_channel_desc)
            nm.createNotificationChannel(ch)
        }
        NavigationController.setServiceCallback { title, text -> updateNotification(title, text) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NavigationController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification(
            getString(R.string.notif_nav_title),
            getString(R.string.notif_nav_text)
        )
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_my_location)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$text"))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notif_nav_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(title, text))
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        NavigationController.setServiceCallback { _, _ -> }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
