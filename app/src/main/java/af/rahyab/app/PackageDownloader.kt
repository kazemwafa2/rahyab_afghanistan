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

    /** پاک‌کردن فایل نیمه‌کاره — برای بسته‌های asset:// ادامه‌دادن معنی ندارد، کپی همیشه از نو است */
    fun clearPartial(ctx: Context, id: String) {
        try {
            File(File(File(ctx.filesDir, "offline"), "downloads"), "$id.part").delete()
        } catch (_: Exception) { }
    }

    /** آیا این فایل داخل دارایی‌های خود APK موجود است؟ (فهرست فقط بسته‌های واقعاً قابل دانلود را نشان می‌دهد) */
    fun assetExists(ctx: Context, assetPath: String): Boolean = try {
        ctx.assets.open(assetPath).use { true }
    } catch (_: Exception) { false }

    fun fetchManifest(manifestUrl: String): List<RemotePkg> {
        val body = Http.get(manifestUrl)
        return fetchManifestFromJson(body)
    }

    /** تجزیه فهرست بسته‌ها از متن JSON — از منبع انترنت یا منبع محلی داخل برنامه.
     *  اگر ctx داده شود، بسته‌های asset:// که فایل‌شان در این APK نیست حذف می‌شوند
     *  تا دکمه‌ای که کار نمی‌کند هرگز نمایش داده نشود. */
    fun fetchManifestFromJson(body: String, ctx: Context? = null): List<RemotePkg> {
        val o = JSONObject(body)
        val out = ArrayList<RemotePkg>()
        val arr = o.optJSONArray("packages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            var url = p.optString("url", "")
            if (url.isEmpty()) {
                val file = p.optString("file", "")
                if (file.isNotEmpty()) url = file // مسیر نسبی یا asset://
            }
            if (url.isEmpty()) continue
            if (ctx != null && url.startsWith("asset://") && !assetExists(ctx, url.removePrefix("asset://"))) {
                Log.w(TAG, "بسته ${p.optString("id")} در دارایی‌های این APK نیست — از فهرست حذف شد")
                continue
            }
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
            val ok: Long = if (pkg.url.startsWith("asset://")) {
                // بسته داخل خود برنامه (asset://) — بدون انترنت، مستقیم از دارایی‌های APK کپی می‌شود
                val assetPath = pkg.url.removePrefix("asset://")
                val ins = try {
                    ctx.assets.open(assetPath)
                } catch (e: Exception) {
                    // فایل بسته در این نسخه APK نیست — پیام دقیق به کاربر
                    listener.onProgress(Progress(pkg.id, State.FAILED, 0, pkg.sizeBytes, "asset_missing"))
                    return null
                }
                ins.use { s ->
                    partFile.outputStream().use { out ->
                        val buf = ByteArray(CHUNK)
                        var done = 0L
                        val total = s.available().toLong().let { if (it > 0) it else pkg.sizeBytes }
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done % (CHUNK * 40) == 0L) listener.onProgress(Progress(pkg.id, State.DOWNLOADING, done, total))
                        }
                        listener.onProgress(Progress(pkg.id, State.DOWNLOADING, done, done))
                        done
                    }
                }
            } else {
                listener.onProgress(Progress(pkg.id, State.DOWNLOADING, 0, pkg.sizeBytes, "connecting"))
                downloadWithResume(pkg.url, partFile, pkg.sizeBytes) { done, total ->
                    listener.onProgress(Progress(pkg.id, State.DOWNLOADING, done, total))
                } ?: return null // لغو شد
            }
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
            val detail = if (e is HttpFatal) "http_${e.code}"
                else "network:" + (e.javaClass.simpleName + " " + (e.message ?: "")).trim()
            listener.onProgress(Progress(pkg.id, State.FAILED, 0, 0, detail))
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
                if (code in 400..499) throw HttpFatal(code)
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
                if (e is HttpFatal || attempt >= 8) throw e
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

/** خطای دائمی سرور (مثلاً 404 = فایل در ریلیز نیست) — تکرار بی‌فایده است */
class HttpFatal(val code: Int) : IOException("HTTP $code")

/** نام مستعار برای خوانایی بهتر در MapPackagesActivity */
typealias InstalledData = OfflineData.Installed
