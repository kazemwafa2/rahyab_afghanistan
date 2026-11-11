package af.rahyab.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * سرویس پخش رادیو در پس‌زمینه — بخش دهم چک‌لیست.
 * پخش با خاموش شدن صفحه و رفتن به پس‌زمینه ادامه می‌یابد؛ اعلان دائمی با
 * دکمه توقف. مدیریت قطع انترنت: خطای پخش → توقف بی‌صدا + اعلان کوتاه.
 */
class RadioService : Service() {

    companion object {
        private const val CHANNEL_ID = "radio"
        private const val NOTIF_ID = 42
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val ACTION_STOP = "af.rahyab.app.radio.STOP"

        var nowPlaying: String? = null
            private set
        @Volatile
        private var playerRef: MediaPlayer? = null

        /** اطلاع‌رسانی وضعیت پخش به صفحه رادیو (نام، در حال پخش؟) */
        @Volatile
        var stateListener: ((String?, Boolean) -> Unit)? = null

        private fun notifyState(playing: Boolean) {
            try { stateListener?.invoke(nowPlaying, playing) } catch (e: Exception) { }
        }

        /** نام ایستگاه در حال پخش (برای نشان‌دادن ایستگاه فعال در فهرست) */
        val isPlayingName: String? get() = if (isPlaying()) nowPlaying else null

        fun start(ctx: Context, url: String, name: String) {
            val i = Intent(ctx, RadioService::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, name)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** شروع پخش با ترتیب پارامترهای صفحه رادیو (نام، نشانی) */
        fun play(ctx: Context, name: String, url: String) = start(ctx, url, name)

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RadioService::class.java))
            notifyState(false)
        }

        fun isPlaying(): Boolean = nowPlaying != null

        /** کنترل صدا (۰-۱۰) برای رادیو */
        fun setVolume(v: Float) {
            try { playerRef?.setVolume(v.coerceIn(0f, 1f), v.coerceIn(0f, 1f)) } catch (e: Exception) { }
        }
    }

    private var player: MediaPlayer? = null
    private var name = ""

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.radio_channel), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        name = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.radio)
        nowPlaying = name
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        play(url)
        notifyState(false) // اتصال در جریان است
        return START_NOT_STICKY
    }

    private fun play(url: String) {
        player?.release(); player = null
        try {
            val m = MediaPlayer()
            playerRef = m
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            m.setDataSource(url)
            m.setOnPreparedListener {
                it.start()
                notifyState(true) // پخش آغاز شد
            }
            m.setOnErrorListener { _, _, _ ->
                // قطع انترنت یا جریان خراب: توقف بی‌صدا؛ کاربر می‌تواند دوباره بزند
                stopSelf()
                true
            }
            m.prepareAsync()
            player = m
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(getString(R.string.radio_playing, name))
            .setContentText(getString(R.string.radio_notif_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(0, getString(R.string.radio_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        nowPlaying = null
        notifyState(false)
        try { player?.stop(); player?.release() } catch (e: Exception) { }
        playerRef = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
