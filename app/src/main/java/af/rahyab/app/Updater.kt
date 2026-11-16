package af.rahyab.app

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * به‌روزرسانی مستقیم برنامه — بخش دوازدهم چک‌لیست.
 *  - بررسی آخرین نسخه از GitHub Releases (بدون نیاز به ورود کاربر به گیت‌هاب)
 *  - نمایش نسخه، حجم APK و توضیحات تغییرات
 *  - دانلود با درصد پیشرفت و ادامه‌پذیر
 *  - بررسی صحت با SHA-256 (اگر در متن ریلیز «sha256: …» آمده باشد)
 *  - نصب با PackageInstaller رسمی اندروید؛ اطلاعات کاربر حفظ می‌شود
 *  - امضای ثابت: نصب نسخه با امضای متفاوت توسط اندروید رد می‌شود
 */
object Updater {

    class Release(
        val versionName: String, val versionCode: Int, val apkUrl: String,
        val sizeBytes: Long, val notes: String, val sha256: String?
    )

    /** وضعیت برای رابط کاربری */
    var lastCheck: String? = null
        private set

    private fun api(): String {
        val repo = Config.REPO_URL.removePrefix("https://github.com/")
        return "https://api.github.com/repos/$repo/releases/latest"
    }

    /** بررسی ریلیز جدید؛ خطاهای شبکه به‌صورت استثنا منتقل می‌شود */
    fun checkLatest(): Release? {
        val body = Http.get(api(), readTimeoutMs = 15000)
        val r = JSONObject(body)
        if (r.has("message") && !r.has("tag_name")) throw BadResponseException("release not found")
        val tag = r.optString("tag_name", "")
        val assets = r.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            if (name.endsWith(".apk") && (name.contains("universal") || apkUrl == null)) {
                apkUrl = a.optString("browser_download_url", "")
                size = a.optLong("size", 0L)
                if (name.contains("universal")) break
            }
        }
        if (apkUrl.isNullOrBlank()) throw BadResponseException("no apk asset")
        val notes = r.optString("body", "").take(1200)
        // versionCode از متن ریلیز یا تگ: «versionCode: ۹» یا تگ v0.8.1
        var code = 0
        val m = Regex("versionCode\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE).find(notes)
        if (m != null) code = m.groupValues[1].toInt()
        else {
            val mt = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(tag)
            if (mt != null) {
                code = mt.groupValues[1].toInt() * 100 + mt.groupValues[2].toInt() * 10 + mt.groupValues[3].toInt()
            }
        }
        val sha = Regex("sha256\\s*[:=]\\s*([0-9a-fA-F]{64})").find(notes)?.groupValues?.get(1)?.lowercase()
        lastCheck = "checked:${System.currentTimeMillis()}"
        return Release(tag.removePrefix("v"), code, apkUrl, size, notes, sha)
    }

    /** آیا نسخه دریافت‌شده تازه‌تر از نسخه نصب‌شده است؟ */
    fun isNewer(rel: Release, ctx: Context): Boolean {
        if (rel.versionCode <= 0) return false
        return rel.versionCode > currentVersionCode(ctx)
    }

    fun currentVersionCode(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** مسیر فایل موقت دانلود — با قطع انترنت حفظ می‌شود تا دانلود ادامه پیدا کند */
    fun partFile(ctx: Context): File {
        val outDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "update")
        outDir.mkdirs()
        return File(outDir, "rahyab-update.apk.part")
    }

    /**
     * دانلود APK با گزارش پیشرفت و «ادامهٔ دانلود».
     *  - پیشرفت: (درصد، بایت‌های دریافت‌شده، حجم کل)
     *  - اگر دانلود قبلی ناتمام مانده باشد، با هدر Range از همان‌جا ادامه می‌یابد
     *    (سرور GitHub Releases از Range پشتیبانی می‌کند)؛ اگر سرور ۲۰۰ برگرداند
     *    از صفر شروع می‌شود.
     *  - SHA-256 پس از تکمیل روی فایل کامل محاسبه می‌شود (با ادامهٔ دانلود هم درست است).
     */
    fun download(
        ctx: Context, rel: Release,
        onProgress: (Int, Long, Long) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            var c: java.net.HttpURLConnection? = null
            try {
                val part = partFile(ctx)
                var got = if (part.isFile) part.length() else 0L
                c = (java.net.URL(rel.apkUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = Config.HTTP_CONNECT_TIMEOUT_MS
                    readTimeout = 60000
                    setRequestProperty("User-Agent", Config.USER_AGENT)
                    if (got > 0) setRequestProperty("Range", "bytes=$got-")
                }
                c.connect()
                val code = c.responseCode
                // ۲۰۶ = ادامه از همان‌جا؛ ۲۰۰ = سرور Range را نداد — از صفر
                if (code == 416) { // بازهٔ کامل یا ناسازگار — از صفر دوباره
                    c.disconnect()
                    part.delete(); got = 0
                    download(ctx, rel, onProgress, onDone, onError)
                    return@Thread
                }
                if (code != 206) got = 0
                if (got == 0L) part.delete()
                val rangeTotal = (c.contentLengthLong.takeIf { it > 0 } ?: -1L).let {
                    if (code == 206) it + got else it
                }
                val total = if (rangeTotal > 0) rangeTotal
                    else if (rel.sizeBytes > 0) rel.sizeBytes else -1L
                val buf = ByteArray(64 * 1024)
                java.io.FileOutputStream(part, got > 0).use { f ->
                    c.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            f.write(buf, 0, n)
                            got += n
                            if (total > 0) onProgress(((got * 100) / total).toInt().coerceIn(0, 100), got, total)
                        }
                    }
                }
                c.disconnect()
                if (total > 0 && got < total && total > 1_000_000) {
                    // ناتمام — فایل part حفظ می‌شود؛ دفعهٔ بعد از همان‌جا ادامه می‌یابد
                    onError("incomplete")
                    return@Thread
                }
                // بررسی صحت با SHA-256 (روی فایل کامل — سازگار با ادامهٔ دانلود)
                if (rel.sha256 != null) {
                    val md = MessageDigest.getInstance("SHA-256")
                    part.inputStream().use { ins ->
                        val b = ByteArray(64 * 1024)
                        while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) }
                    }
                    val digest = md.digest().joinToString("") { "%02x".format(it) }
                    if (!digest.equals(rel.sha256, ignoreCase = true)) {
                        part.delete()
                        onError("sha256_mismatch")
                        return@Thread
                    }
                }
                val out = File(part.parentFile, "rahyab-update.apk")
                if (out.isFile) out.delete()
                if (!part.renameTo(out)) {
                    onError("rename_failed")
                    return@Thread
                }
                onProgress(100, got, got)
                onDone(out)
            } catch (e: Exception) {
                try { c?.disconnect() } catch (_: Exception) { }
                // ناتمام — part حفظ می‌شود تا دانلود بعدی ادامه یابد
                onError(e.message ?: "download_error")
            }
        }.start()
    }

    /** نصب با PackageInstaller رسمی (حفظ اطلاعات کاربر، تأیید کاربر) */
    fun install(ctx: Context, apk: File, onResult: (Boolean, String) -> Unit) {
        try {
            val pm = ctx.packageManager
            val sessionParams = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            sessionParams.setSize(apk.length())
            val sessionId = pm.packageInstaller.createSession(sessionParams)
            val session = pm.packageInstaller.openSession(sessionId)
            val out = session.openWrite("rahyab", 0, apk.length())
            apk.inputStream().use { it.copyTo(out) }
            session.fsync(out)
            out.close()

            val intentSender = android.app.PendingIntent.getBroadcast(
                ctx, 10,
                Intent("af.rahyab.app.INSTALL_RESULT").setPackage(ctx.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            ).intentSender
            session.commit(intentSender)
            session.close()
            onResult(true, "session_started")
        } catch (e: Exception) {
            onResult(false, e.message ?: "install_error")
        }
    }

    /** آیا برنامه اجازه نصب بسته‌های ناشناس دارد؟ (اندروید ۸+) */
    fun canInstall(ctx: Context): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 26)
            ctx.packageManager.canRequestPackageInstalls()
        else true
    } catch (e: Exception) { false }

    /** درخواست اجازه نصب از کاربر */
    fun requestInstallPermission(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
