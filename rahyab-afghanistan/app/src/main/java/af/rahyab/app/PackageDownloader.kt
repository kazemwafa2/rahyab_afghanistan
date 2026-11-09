package af.rahyab.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * دانلودکننده بسته‌های نقشه از مخزن Releases گیت‌هاب.
 *
 * ویژگی‌ها طبق چک‌لیست:
 *  - خواندن manifest نسخه‌دار (فهرست بسته‌ها با حجم، تاریخ داده و checksum)
 *  - دانلود با امکان ادامه پس از قطع اتصال (HTTP Range)
 *  - کنترل SHA-256 پس از دانلود
 *  - بررسی فضای خالی گوشی پیش از دانلود
 *  - نصب اتمیک: استخراج در staging، اعتبارسنجی، سپس جابه‌جایی
 *  - بسته ناقص یا خراب هرگز فعال نمی‌شود
 */
object PackageDownloader {

    private const val TAG = "PkgDownloader"
    private const val CHUNK = 64 * 1024

    enum class State { DOWNLOADING, VERIFYING, EXTRACTING, DONE, CANCELLED, FAILED }

    class Progress(
        val pkgId: String,
        val state: State,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val message: String? = null
    )

    interface Listener {
        fun onProgress(p: Progress)
    }

    /** یک بسته از manifest سراسری */
    data class RemotePkg(
        val id: String, val name: String, val region: String, val dataDate: String,
        val builderVersion: String, val url: String, val sizeBytes: Long, val sha256: String
    )

    // ---------- manifest ----------
    /** آیا دانلود نیمه‌کاره‌ای برای این بسته وجود دارد (ادامه دانلود ممکن است) */
    fun hasPartial(ctx: Context, id: String): Boolean {
        val f = File(File(File(ctx.filesDir, "offline"), "downloads"), "$id.part")
        return f.isFile && f.length() > 0
    }

    fun fetchManifest(manifestUrl: String): List<RemotePkg> {
        val body = Http.get(manifestUrl)
        val o = JSONObject(body)
        val out = ArrayList<RemotePkg>()
        val arr = o.optJSONArray("packages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            var url = p.optString("url", "")
            if (url.isEmpty()) {
                val file = p.optString("file", "")
                if (file.isNotEmpty() && manifestUrl.contains("/")) {
                    url = manifestUrl.substringBeforeLast("/") + "/" + file
                }
            }
            if (url.isEmpty()) continue
            out.add(
                RemotePkg(
                    p.getString("id"), p.optString("name", p.getString("id")),
                    p.optString("region", ""), p.optString("dataDate", ""),
                    p.optString("builderVersion", ""), url,
                    p.optLong("sizeBytes", 0), p.optString("sha256", "")
                )
            )
        }
        return out
    }

    // ---------- دانلود ----------
    /**
     * دانلود، راستی‌آزمایی و نصب بسته. کار همگام است — در نخ پس‌زمینه صدا زده شود.
     * @return بسته نصب‌شده یا null در صورت لغو/شکست
     */
    fun downloadAndInstall(ctx: Context, pkg: RemotePkg, listener: Listener, isCancelled: () -> Boolean): InstalledData? {
        val base = File(ctx.filesDir, "offline")
        val dlDir = File(base, "downloads").apply { mkdirs() }
        val partFile = File(dlDir, "${pkg.id}.part")

        // بررسی فضای خالی: فایل زیپ + محتوای استخراج‌شده (تقریب ۱٫۶ برابر)
        val need = (pkg.sizeBytes * 1.6).toLong().coerceAtLeast(1L)
        val free = OfflineData.freeBytes(ctx)
        if (free in 1 until need) {
            listener.onProgress(Progress(pkg.id, State.FAILED, 0, pkg.sizeBytes, "no_space"))
            return null
        }

        try {
            val ok = downloadWithResume(pkg.url, partFile, pkg.sizeBytes) { done, total ->
                listener.onProgress(Progress(pkg.id, State.DOWNLOADING, done, total))
            } ?: return null // لغو شد
            if (isCancelled()) { listener.onProgress(Progress(pkg.id, State.CANCELLED, 0, 0)); return null }

            // کنترل checksum
            listener.onProgress(Progress(pkg.id, State.VERIFYING, ok, ok))
            if (pkg.sha256.isNotEmpty()) {
                val actual = sha256(partFile)
                if (!actual.equals(pkg.sha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA-256 بسته ${pkg.id} مطابقت ندارد")
                    partFile.delete()
                    listener.onProgress(Progress(pkg.id, State.FAILED, 0, 0, "checksum"))
                    return null
                }
            }

            // استخراج در staging
            listener.onProgress(Progress(pkg.id, State.EXTRACTING, ok, ok))
            val staging = File(base, "staging/${pkg.id}")
            staging.deleteRecursively()
            staging.mkdirs()
            unzipSafe(partFile, staging)

            val inst = OfflineData.installFromStaging(staging)
            if (inst == null) {
                staging.deleteRecursively()
                listener.onProgress(Progress(pkg.id, State.FAILED, 0, 0, "invalid"))
                return null
            }
            partFile.delete()
            listener.onProgress(Progress(pkg.id, State.DONE, ok, ok))
            return inst
        } catch (e: Exception) {
            Log.e(TAG, "دانلود بسته ${pkg.id} ناموفق", e)
            listener.onProgress(Progress(pkg.id, State.FAILED, 0, 0, "network"))
            return null
        }
    }

    /** دانلود با ادامه از سر گرفته‌شده؛ خروجی: حجم نهایی یا null در صورت لغو */
    private fun downloadWithResume(url: String, target: File, knownSize: Long, onProgress: (Long, Long) -> Unit): Long? {
        var attempt = 0
        var downloaded = if (target.isFile) target.length() else 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) return null
            attempt++
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", Config.USER_AGENT)
                if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
                instanceFollowRedirects = true
            }
            try {
                val code = c.responseCode
                if (code == 416) { // بازه درخواست‌شده بیرون از حد فایل — فایل قبلاً کامل شده
                    if (knownSize <= 0 || downloaded >= knownSize) return downloaded
                    target.delete(); downloaded = 0
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val resumeAccepted = code == 206
                if (!resumeAccepted && downloaded > 0) { target.delete(); downloaded = 0 }
                val total = if (c.contentLengthLong > 0) c.contentLengthLong + downloaded else knownSize
                val ins = c.inputStream
                val out = FileOutputStream(target, resumeAccepted)
                val buf = ByteArray(CHUNK)
                var lastReport = 0L
                try {
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport > 262_144) { lastReport = downloaded; onProgress(downloaded, total) }
                    }
                } finally {
                    out.close(); ins.close()
                }
                onProgress(downloaded, total)
                if (total > 0 && downloaded < total) {
                    // جریان ناقص — دوباره تلاش با ادامه
                    if (attempt < 20) continue
                    throw IOException("دانلود ناقص: $downloaded از $total")
                }
                return downloaded
            } catch (e: IOException) {
                if (attempt >= 8) throw e
                Log.w(TAG, "قطع اتصال در دانلود؛ پس از مکث ادامه می‌دهیم (تلاش $attempt)", e)
                Thread.sleep(2000L * attempt)
                downloaded = if (target.isFile) target.length() else 0L
            } finally {
                c.disconnect()
            }
        }
    }

    /** استخراج ایمن زیپ (محافظت در برابر Zip-Slip) */
    private fun unzipSafe(zip: File, outDir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            val outCanonical = outDir.canonicalFile
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                val f = File(outDir, e.name)
                if (!f.canonicalPath.startsWith(outCanonical.canonicalPath + File.separator) &&
                    f.canonicalPath != outCanonical.canonicalPath
                ) throw SecurityException("مسیر مشکوک در زیپ: ${e.name}")
                if (e.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { z.copyTo(it) }
                }
                z.closeEntry()
                e = z.nextEntry
            }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** نام مستعار برای خوانایی بهتر در MapPackagesActivity */
typealias InstalledData = OfflineData.Installed
