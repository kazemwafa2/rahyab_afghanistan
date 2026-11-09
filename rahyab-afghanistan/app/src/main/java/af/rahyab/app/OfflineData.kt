package af.rahyab.app

import android.content.Context
import android.os.StatFs
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * مدیریت بسته‌های نقشه/داده آفلاین نصب‌شده روی گوشی.
 *
 * ساختار پوشه‌ها:
 *   filesDir/offline/registry.json   — فهرست بسته‌های نصب‌شده
 *   filesDir/offline/packages/<id>-<dataDate>/ — محتوای هر بسته
 *   filesDir/offline/downloads/…part — فایل‌های در حال دانلود
 *   filesDir/offline/staging/<id>/    — محل استخراج موقت (نصب اتمیک)
 *
 * نصب اتمیک: ابتدا در staging استخراج و اعتبارسنجی می‌شود؛ فقط پس از موفقیت
 * پوشه نهایی نام‌گذاری و در registry ثبت می‌شود. در صورت خرابی، بسته قبلی دست‌نخورده می‌ماند.
 */
object OfflineData {

    private const val TAG = "OfflineData"

    class Installed(
        val id: String,
        val name: String,
        val region: String,
        val dataDate: String,
        val builderVersion: String,
        val dirName: String,
        val sizeBytes: Long,
        val installedAt: Long,
        val hasTiles: Boolean,
        val hasGraph: Boolean,
        val hasIndex: Boolean,
        val west: Double = 0.0,
        val south: Double = 0.0,
        val east: Double = 0.0,
        val north: Double = 0.0
    ) {
        /** آیا این نقطه داخل محدوده بسته است؟ (اگر محدوده ثبت نشده باشد false) */
        fun contains(lat: Double, lon: Double): Boolean =
            west != 0.0 && east != 0.0 && lon in west..east && lat in south..north
    }

    private lateinit var baseDir: File
    private val registry = ArrayList<Installed>()
    private var loaded = false

    fun init(ctx: Context) {
        baseDir = File(ctx.filesDir, "offline")
        if (!baseDir.exists()) baseDir.mkdirs()
        File(baseDir, "downloads").mkdirs()
        File(baseDir, "staging").mkdirs()
        File(baseDir, "packages").mkdirs()
        loadRegistry()
    }

    // ---------- فهرست نصب‌شده‌ها ----------
    val installed: List<Installed> get() { ensureLoaded(); return registry.toList() }

    fun installedById(id: String): Installed? { ensureLoaded(); return registry.firstOrNull { it.id == id } }

    /** بسته‌ای که هم گراف مسیریابی و هم اندیس جست‌وجو دارد */
    fun activeDataPackage(): Installed? =
        installed.filter { it.hasGraph && it.hasIndex }.maxByOrNull { it.dataDate }

    /** بسته پایه کشوری — اولویت با نسخه کامل (اگر نصب باشد)، وگرنه نسخه همراه APK */
    fun basePackage(): Installed? =
        installedById(Config.BASE_FULL_PACKAGE_ID)?.takeIf { it.hasGraph }
            ?: installedById(Config.BASE_PACKAGE_ID)?.takeIf { it.hasGraph }

    /** آیا این بسته از خانواده پایه کشوری است؟ */
    fun isBaseId(id: String): Boolean =
        id == Config.BASE_PACKAGE_ID || id == Config.BASE_FULL_PACKAGE_ID

    /**
     * بسته‌ای که کاشی نقشه و سبک آفلاین دارد.
     * اولویت با بسته ولایتی است (کاشی ریزتر زوم بالا)؛ اگر فقط بسته پایه باشد، همان برگردانده می‌شود.
     */
    fun activeMapPackage(): Installed? {
        return installed.filter { it.hasTiles && !isBaseId(it.id) }
            .maxByOrNull { it.dataDate } ?: basePackage()?.takeIf { it.hasTiles }
    }

    fun dirOf(pkg: Installed): File = File(File(baseDir, "packages"), pkg.dirName)
    fun graphFile(pkg: Installed): File = File(dirOf(pkg), "${pkg.id}.rafgraph")
    fun indexFile(pkg: Installed): File = File(dirOf(pkg), "${pkg.id}.rafidx")
    fun tilesFile(pkg: Installed): File = File(dirOf(pkg), "${pkg.id}.pmtiles")
    fun styleFile(pkg: Installed): File = File(dirOf(pkg), "style.json")
    fun fontsDir(pkg: Installed): File = File(dirOf(pkg), "fonts")

    /**
     * پوشه فونت گلیف برای سبک آفلاین.
     * اولویت: فونت داخل بسته (بسته‌های قدیمی)؛ سپس فونت همراه خود برنامه
     * (assets/fonts — Noto Sans، SIL OFL) که یک بار در حافظه برنامه استخراج می‌شود.
     * بسته‌های جدید فونت ندارند تا دانلود سبک‌تر شود؛ فونت از برنامه می‌آید.
     */
    fun effectiveFontsDir(ctx: Context, pkg: Installed): File {
        val inPkg = fontsDir(pkg)
        if (File(inPkg, "Noto Sans Regular").isDirectory) return inPkg
        val appFonts = File(baseDir, "fonts")
        if (!File(appFonts, "Noto Sans Regular").isDirectory) {
            try {
                val stacks = ctx.assets.list("fonts") ?: emptyArray()
                for (stack in stacks) {
                    val outStack = File(appFonts, stack)
                    outStack.mkdirs()
                    for (fn in ctx.assets.list("fonts/$stack") ?: emptyArray()) {
                        val ins = ctx.assets.open("fonts/$stack/$fn")
                        val outf = File(outStack, fn)
                        if (!outf.isFile || outf.length() == 0L) {
                            ins.use { input -> outf.outputStream().use { input.copyTo(it) } }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "استخراج فونت‌های برنامه ناموفق", e)
            }
        }
        return appFonts
    }

    // ---------- گراف و اندیس (بارگذاری تنبل) ----------
    @Volatile
    var graph: OfflineRouter.Graph? = null
        private set

    /** ولایتی که گراف فعلی برای آن بارگذاری شده است */
    @Volatile
    var graphPackageId: String? = null
        private set

    @Volatile
    var index: OfflineGeocoder.Index? = null
        private set

    private val indexCache = HashMap<String, OfflineGeocoder.Index>()

    @Volatile
    var dataSourceName: String? = null
        private set

    private val dataLock = Any()

    /** گراف مسیریابی آفلاین؛ در صورت نصب بسته و بارگذاری موفق غیرتهی است */
    fun graphForRouting(): OfflineRouter.Graph? {
        ensureLoaded()
        if (graph != null) return graph
        synchronized(dataLock) {
            if (graph != null) return graph
            val pkg = activeDataPackage() ?: return null
            return loadGraphLocked(pkg)
        }
    }

    /**
     * گراف مناسب برای مسیریابی از مبدأ به مقصد — کلید مسیر بین‌ولایتی بدون قطع‌شدن در مرز بسته‌ها:
     *  - اگر یک بسته ولایتی هم مبدأ و هم مقصد را پوشش دهد → گراف همان ولایت (خیابان‌های کامل)
     *  - وگرنه (مسیر بین دو ولایت یا مقصد بیرون محدوده) → گراف بسته پایه کشوری
     *    (راه‌های اصلی همه ولایت‌ها در یک گراف یکپارچه)
     *  - اگر بسته پایه نصب نباشد → جدیدترین بسته داده موجود (رفتار قدیمی)
     * تنها یک گراف در حافظه نگه داشته می‌شود (محدودیت گوشی‌های کم‌رم).
     */
    fun graphForRouting(lat: Double, lon: Double, destLat: Double, destLon: Double): OfflineRouter.Graph? {
        ensureLoaded()
        synchronized(dataLock) {
            val dataPkgs = installed.filter { it.hasGraph && it.hasIndex && !isBaseId(it.id) }
            val coversBoth = dataPkgs.filter { it.contains(lat, lon) && it.contains(destLat, destLon) }
            val base = basePackage()
            val target: Installed? = when {
                coversBoth.isNotEmpty() -> coversBoth.maxByOrNull { it.dataDate }
                base != null -> base
                else -> {
                    val any = installed.filter { it.hasGraph && it.hasIndex }
                    any.firstOrNull { it.contains(lat, lon) } ?: any.maxByOrNull { it.dataDate }
                }
            }
            if (target == null) return null
            if (graph != null && graphPackageId == target.id) return graph
            return loadGraphLocked(target)
        }
    }

    /** گراف مناسب برای مسیریابی از این نقطه (بدون مقصد مشخص — برای نمایش وضعیت) */
    fun graphForRouting(lat: Double, lon: Double): OfflineRouter.Graph? {
        ensureLoaded()
        if (graph != null && graphPackageId != null) {
            val cur = installedById(graphPackageId!!)
            if (cur != null && (cur.contains(lat, lon) || !anyContains(lat, lon))) return graph
        }
        synchronized(dataLock) {
            val dataPkgs = installed.filter { it.hasGraph && it.hasIndex }
            if (dataPkgs.isEmpty()) return null
            val target = dataPkgs.firstOrNull { it.contains(lat, lon) } ?: dataPkgs.maxByOrNull { it.dataDate }!!
            if (graph != null && graphPackageId == target.id) return graph
            return loadGraphLocked(target)
        }
    }

    private fun anyContains(lat: Double, lon: Double): Boolean =
        installed.any { it.contains(lat, lon) }

    private fun loadGraphLocked(pkg: Installed): OfflineRouter.Graph? {
        return try {
            val g = OfflineRouter.Graph.load(graphFile(pkg))
            g.buildGrid()
            g.nationwide = (pkg.id == Config.BASE_PACKAGE_ID)
            graph = g
            graphPackageId = pkg.id
            index = null
            dataSourceName = pkg.name
            g
        } catch (e: Exception) {
            Log.e(TAG, "بارگذاری گراف آفلاین ناموفق: ${pkg.id}", e)
            null
        }
    }

    fun indexForSearch(): OfflineGeocoder.Index? {
        ensureLoaded()
        if (index != null) return index
        synchronized(dataLock) {
            if (index != null) return index
            val pkg = activeDataPackage() ?: return null
            return try {
                val ix = OfflineGeocoder.Index.load(indexFile(pkg))
                index = ix
                dataSourceName = pkg.name
                ix
            } catch (e: Exception) {
                Log.e(TAG, "بارگذاری اندیس جست‌وجو ناموفق", e)
                null
            }
        }
    }

    /**
     * اندیس همه بسته‌های نصب‌شده (برای جست‌وجوی ترکیبی بین ولایت‌ها).
     * اندیس‌ها کوچک‌اند (چند صد کیلوبایت) و بارگذاری همه در حافظه مشکل ندارد.
     */
    fun indexesForSearch(): List<OfflineGeocoder.Index> {
        ensureLoaded()
        synchronized(dataLock) {
            val out = ArrayList<OfflineGeocoder.Index>()
            var any = false
            for (pkg in installed) {
                if (!pkg.hasIndex) continue
                any = true
                val cached = indexCache[pkg.dirName]
                if (cached != null) { out.add(cached); continue }
                try {
                    val ix = OfflineGeocoder.Index.load(indexFile(pkg))
                    indexCache[pkg.dirName] = ix
                    out.add(ix)
                } catch (e: Exception) {
                    Log.e(TAG, "اندیس ${pkg.id} بارگذاری نشد", e)
                }
            }
            if (any && dataSourceName == null) dataSourceName = installed.firstOrNull()?.name
            return out
        }
    }

    /** پس از نصب/حذف بسته، گراف و اندیس حافظه دوباره از نو بارگذاری می‌شوند */
    fun invalidate() {
        synchronized(dataLock) {
            graph = null; index = null; dataSourceName = null; graphPackageId = null
            indexCache.clear()
        }
        registry.clear(); loaded = false
        try { onDataChanged?.invoke() } catch (_: Exception) { }
    }

    /** اعلان تغییر داده‌ها (نصب/حذف بسته) — رابط کاربری سبک نقشه را تازه می‌کند */
    @Volatile
    var onDataChanged: (() -> Unit)? = null

    // ---------- نصب و حذف ----------
    /** خواندن pkgmanifest.json از یک پوشه استخراج‌شده */
    fun readPkgManifest(dir: File): Installed? {
        val f = File(dir, "pkgmanifest.json")
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            val id = o.getString("id")
            val filesArr = o.optJSONArray("files") ?: JSONArray()
            val names = HashSet<String>()
            var total = 0L
            for (i in 0 until filesArr.length()) {
                val fo = filesArr.getJSONObject(i)
                names.add(fo.getString("name")); total += fo.optLong("sizeBytes", 0)
            }
            val b = o.optJSONObject("bounds")
            Installed(
                id = id,
                name = o.optString("name", id),
                region = o.optString("region", ""),
                dataDate = o.optString("dataDate", ""),
                builderVersion = o.optString("builderVersion", ""),
                dirName = "",
                sizeBytes = total,
                installedAt = 0L,
                hasTiles = names.contains("$id.pmtiles") && File(dir, "style.json").isFile,
                hasGraph = names.contains("$id.rafgraph"),
                hasIndex = names.contains("$id.rafidx"),
                west = b?.optDouble("west", 0.0) ?: 0.0,
                south = b?.optDouble("south", 0.0) ?: 0.0,
                east = b?.optDouble("east", 0.0) ?: 0.0,
                north = b?.optDouble("north", 0.0) ?: 0.0
            )
        } catch (e: Exception) {
            Log.e(TAG, "pkgmanifest.json نا معتبر", e)
            null
        }
    }

    /** اعتبارسنجی و ثبت نهایی بسته از پوشه staging (نصب اتمیک) */
    fun installFromStaging(staging: File): Installed? {
        val m = readPkgManifest(staging) ?: return null
        // اعتبارسنجی سرآیند فایل‌های اصلی
        try {
            if (m.hasGraph) OfflineRouter.Graph.probe(graphFile(m))
            if (m.hasIndex) OfflineGeocoder.Index.probe(indexFile(m))
            if (m.hasTiles) {
                val tf = tilesFile(m)
                if (tf.length() < 1000) throw IllegalArgumentException("فایل کاشی‌ها ناقص است")
            }
        } catch (e: Exception) {
            Log.e(TAG, "اعتبارسنجی بسته ناموفق", e)
            return null
        }
        val dirName = if (m.dataDate.isEmpty()) m.id else "${m.id}-${m.dataDate}"
        val finalDir = File(File(baseDir, "packages"), dirName)
        if (finalDir.exists()) finalDir.deleteRecursively()
        if (!staging.renameTo(finalDir)) {
            // در صورت شکست rename (cross-device نباید رخ دهد) کپی می‌کنیم
            if (!staging.copyRecursively(finalDir, overwrite = true)) return null
            staging.deleteRecursively()
        }
        val inst = Installed(
            m.id, m.name, m.region, m.dataDate, m.builderVersion, dirName,
            finalDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum(),
            System.currentTimeMillis(), m.hasTiles, m.hasGraph, m.hasIndex,
            m.west, m.south, m.east, m.north
        )
        registry.removeAll { it.id == inst.id }
        registry.add(inst)
        saveRegistry()
        invalidate()
        return inst
    }

    fun delete(id: String): Boolean {
        ensureLoaded()
        // بسته پایه کشوری همراه APK حذف نمی‌شود؛ نسخه کامل دانلودی قابل حذف است
        if (id == Config.BASE_PACKAGE_ID) return false
        val pkg = registry.firstOrNull { it.id == id } ?: return false
        val ok = dirOf(pkg).deleteRecursively()
        registry.removeAll { it.id == id }
        saveRegistry()
        invalidate()
        return ok
    }

    /**
     * نصب بسته پایه از دارایی‌های خود APK (بدون انترنت، در نخستین اجرا).
     * همان مسیر نصب اتمیک دانلود: استخراج در staging، اعتبارسنجی، ثبت.
     */
    fun installFromAsset(ctx: Context, assetPath: String): Installed? {
        val staging = File(File(baseDir, "staging"), "asset-${Config.BASE_PACKAGE_ID}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ctx.assets.open(assetPath).use { ins ->
                java.util.zip.ZipInputStream(ins.buffered()).use { z ->
                    val outCanonical = staging.canonicalFile
                    var e: java.util.zip.ZipEntry? = z.nextEntry
                    while (e != null) {
                        val f = File(staging, e.name)
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
            return installFromStaging(staging)
        } catch (e: Exception) {
            Log.e(TAG, "نصب بسته پایه از دارایی‌های برنامه ناموفق", e)
            staging.deleteRecursively()
            return null
        }
    }

    /** آیا بسته پایه (نسخه‌ای از آن) هنوز نصب نشده؟ (برای نصب خودکار در نخستین اجرا) */
    fun needsBaseInstall(): Boolean {
        ensureLoaded()
        return installedById(Config.BASE_PACKAGE_ID) == null
    }

    /** فضای خالی قابل استفاده در حافظه داخلی به بایت */
    fun freeBytes(ctx: Context): Long = try {
        StatFs(ctx.filesDir.path).availableBytes
    } catch (e: Exception) { 0L }

    // ---------- خواندن/نوشتن registry ----------
    private fun ensureLoaded() { if (!loaded) loadRegistry() }

    @Synchronized
    private fun loadRegistry() {
        registry.clear()
        loaded = true
        val f = File(baseDir, "registry.json")
        if (!f.isFile) return
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dirName = o.getString("dir")
                val dir = File(File(baseDir, "packages"), dirName)
                // بسته‌ای که پوشه‌اش حذف شده باشد نادیده گرفته می‌شود
                if (!dir.isDirectory) continue
                registry.add(
                    Installed(
                        o.getString("id"), o.optString("name", ""), o.optString("region", ""),
                        o.optString("dataDate", ""), o.optString("builderVersion", ""), dirName,
                        o.optLong("sizeBytes", 0), o.optLong("installedAt", 0),
                        o.optBoolean("hasTiles", false), o.optBoolean("hasGraph", false),
                        o.optBoolean("hasIndex", false),
                        o.optDouble("west", 0.0), o.optDouble("south", 0.0),
                        o.optDouble("east", 0.0), o.optDouble("north", 0.0)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "registry.json خراب بود", e)
        }
    }

    @Synchronized
    private fun saveRegistry() {
        val arr = JSONArray()
        registry.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("region", p.region)
                    .put("dataDate", p.dataDate).put("builderVersion", p.builderVersion)
                    .put("dir", p.dirName).put("sizeBytes", p.sizeBytes)
                    .put("installedAt", p.installedAt)
                    .put("hasTiles", p.hasTiles).put("hasGraph", p.hasGraph).put("hasIndex", p.hasIndex)
                    .put("west", p.west).put("south", p.south).put("east", p.east).put("north", p.north)
            )
        }
        try {
            val f = File(baseDir, "registry.json")
            val tmp = File(baseDir, "registry.json.tmp")
            tmp.writeText(arr.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.e(TAG, "ذخیره registry ناموفق", e)
        }
    }
}
