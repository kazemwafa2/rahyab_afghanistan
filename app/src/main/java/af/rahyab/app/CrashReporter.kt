package af.rahyab.app

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * گزارش‌گیر خرابی: اگر برنامه بسته شد (کرش)، در اجرای بعدی علت دقیق نمایش داده می‌شود
 * تا بتوان متن آن را کپی و برای رفع اشکال فرستاد.
 *  - خطای جاوا/کاتلین: ردپای کامل در filesDir/crash.txt ذخیره می‌شود.
 *  - خرابی بومی (مثلاً موتور نقشه) یا کشته‌شدن توسط سیستم: از ApplicationExitInfo (اندروید ۱۱+).
 */
object CrashReporter {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file(app).writeText(
                    "نسخه ${BuildConfig.VERSION_NAME} • $ts • thread=${t.name}\n" +
                        "Android ${Build.VERSION.SDK_INT} • ${Build.MANUFACTURER} ${Build.MODEL}\n\n$sw"
                )
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    private fun nativeExitInfo(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
                .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_SIGNALED ||
                    it.reason == ApplicationExitInfo.REASON_ANR ||
                    it.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE } ?: return null
            val prefs = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
            if (last.timestamp <= prefs.getLong("seen_ts", 0L)) return null
            prefs.edit().putLong("seen_ts", last.timestamp).apply()
            "خروج برنامه: reason=${last.reason} status=${last.status}\n" +
                "${last.description ?: ""}\n" +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(last.timestamp))
        } catch (_: Throwable) { null }
    }

    fun showIfAny(act: Activity) {
        var text: String? = null
        val f = file(act)
        if (f.isFile) {
            text = try { f.readText() } catch (_: Throwable) { null }
            try { f.delete() } catch (_: Throwable) { }
        }
        if (text == null) text = nativeExitInfo(act)
        if (text == null) return
        val shown = text.take(3500)
        try {
            AlertDialog.Builder(act)
                .setTitle("گزارش خرابی قبلی")
                .setMessage(shown)
                .setPositiveButton("کپی") { _, _ ->
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (_: Throwable) { }
    }
}
