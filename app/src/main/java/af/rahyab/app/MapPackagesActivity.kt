package af.rahyab.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * مدیریت بسته‌های نقشه آفلاین:
 *  - فهرست بسته‌های موجود در manifest (نسخه، تاریخ داده، حجم)
 *  - دانلود با پیشرفت، توقف و ادامه از سرگیری پس از قطع اتصال
 *  - حذف بسته با تأیید، نمایش نسخه و تاریخ نقشه نصب‌شده
 */
class MapPackagesActivity : AppCompatActivity() {

    private val io = SafeExecutors.fixed(1)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var listBox: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvManifest: TextView

    private var remote: List<PackageDownloader.RemotePkg> = emptyList()

    private data class Downloading(@Volatile var cancelled: Boolean)
    private var current: Downloading? = null

    private class RowViews(
        val card: MaterialCardView,
        val tvMeta: TextView,
        val tvState: TextView,
        val bar: ProgressBar,
        val action: Button,
        var pkgId: String = ""
    )

    private val rows = ArrayList<RowViews>()
    private val lastError = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.offline_maps)

        val pad = dp(16)
        val root = ScrollView(this).apply { id = View.generateViewId() }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(col)

        // توضیح
        col.addView(TextView(this).apply {
            text = getString(R.string.pkg_note)
            textSize = 14f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })

        // فضای خالی
        tvStatus = TextView(this).apply {
            setPadding(0, dp(8), 0, 0)
            textSize = 13f
            setTextColor(0xFF0D47A1.toInt())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        col.addView(tvStatus)

        // منبع manifest
        tvManifest = TextView(this).apply {
            setPadding(0, dp(4), 0, dp(8))
            textSize = 11f
            setTextColor(0xFF666666.toInt())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        col.addView(tvManifest)

        val btnRefresh = Button(this).apply { text = getString(R.string.refresh) }
        col.addView(btnRefresh, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        btnRefresh.setOnClickListener { loadManifest() }

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        col.addView(listBox)

        // کاتالوگ ۳۴ ولایت — مثل فهرست ولایات نشان؛ بسته‌های ولایتی با انتشارهای بعدی همین‌جا ظاهر می‌شوند
        col.addView(hintText(getString(R.string.pkg_provinces_title)))
        col.addView(TextView(this).apply {
            text = provinceCatalogText()
            textSize = 13f
            setTextColor(0xFF3A4A5E.toInt())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(0, dp(4), 0, dp(12))
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.pkg_provinces_note)
            textSize = 11f
            setTextColor(0xFF666666.toInt())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })

        loadManifest()
    }

    /** فهرست نام دری ۳۴ ولایت از پایگاه‌داده داخلی مکان‌ها */
    private fun provinceCatalogText(): String {
        return try {
            val s = assets.open("places/af_places.geojson").bufferedReader().use { it.readText() }
            val o = org.json.JSONObject(s)
            val names = ArrayList<String>()
            val arr = o.getJSONArray("features")
            for (i in 0 until arr.length()) {
                val pr = arr.getJSONObject(i).getJSONObject("properties")
                if (pr.optString("c") == "province") names.add(pr.getString("n"))
            }
            names.joinToString(" • ")
        } catch (e: Exception) {
            getString(R.string.pkg_provinces_error)
        }
    }

    private fun manifestUrl(): String =
        getSharedPreferences("settings", MODE_PRIVATE).getString("manifest_url", null) ?: Config.MANIFEST_URL

    private fun loadManifest() {
        tvStatus.text = getString(R.string.pkg_loading)
        io.execute {
            // فهرست محلی: بسته‌هایی که فایل‌شان واقعاً داخل همین APK است
            val local: List<PackageDownloader.RemotePkg> = try {
                val json = assets.open("packages/manifest.json").bufferedReader().use { it.readText() }
                PackageDownloader.fetchManifestFromJson(json, applicationContext)
            } catch (e: Exception) { emptyList() }

            val online: List<PackageDownloader.RemotePkg>? = try {
                PackageDownloader.fetchManifest(manifestUrl())
            } catch (e: Exception) {
                if (local.isEmpty()) {
                    ui.post { onManifestError(e) }
                    return@execute
                }
                ui.post { onLocalManifest(local, e is BadResponseException) }
                return@execute
            }
            // ⚠️ رفع اشکال اصلی «دانلود نمی‌شود»: قبلاً وقتی فهرست آنلاین (گیت‌هاب) می‌رسید،
            // فقط آدرس‌های گیت‌هاب نمایش داده می‌شد و بسته‌های داخل APK نادیده گرفته می‌شدند؛
            // گیت‌هاب در افغانستان/با فیلترشکن کند یا قطع است و دانلود همیشه نیمه‌کاره می‌ماند.
            // حالا اگر بسته در APK باشد، همان (asset://) ترجیح داده می‌شود: فوری و بی‌انترنت.
            val merged = mergePreferLocal(local, online ?: emptyList())
            ui.post { showPackages(merged) }
        }
    }

    /** ادغام فهرست‌ها بر اساس شناسه؛ نسخهٔ محلی (داخل APK) بر نسخهٔ آنلاین ارجحیت دارد */
    private fun mergePreferLocal(
        local: List<PackageDownloader.RemotePkg>,
        online: List<PackageDownloader.RemotePkg>
    ): List<PackageDownloader.RemotePkg> {
        val localById = local.associateBy { it.id }
        val out = ArrayList<PackageDownloader.RemotePkg>()
        val seen = HashSet<String>()
        for (o in online) {
            if (!seen.add(o.id)) continue
            out.add(localById[o.id] ?: o)
        }
        for (l in local) if (seen.add(l.id)) out.add(l)
        return out
    }

    /** فهرست محلی (بسته‌های همراه برنامه) — هیچ خطایی نشان نمی‌دهد */
    private fun onLocalManifest(pkgs: List<PackageDownloader.RemotePkg>, onlineMissing: Boolean = false) {
        remote = pkgs
        cleanupStaleParts()
        tvManifest.text = getString(R.string.manifest_source, getString(R.string.manifest_local))
        tvStatus.text = getString(R.string.pkg_free_space, humanBytes(OfflineData.freeBytes(this)))
        rebuildRows()
        Toast.makeText(
            this,
            if (onlineMissing) R.string.pkg_manifest_not_published else R.string.pkg_local_note,
            Toast.LENGTH_LONG
        ).show()
    }

    /** فایل‌های نیمه‌کارهٔ بسته‌های asset:// معنی ندارند (کپی همیشه از نو) — پاک می‌شوند */
    private fun cleanupStaleParts() {
        io.execute {
            for (p in remote) if (p.url.startsWith("asset://")) {
                PackageDownloader.clearPartial(applicationContext, p.id)
            }
        }
    }

    private fun onManifestError(e: Exception) {
        remote = emptyList()
        tvStatus.text = getString(R.string.pkg_free_space, humanBytes(OfflineData.freeBytes(this)))
        rebuildRows()
        Toast.makeText(this, R.string.pkg_manifest_error, Toast.LENGTH_LONG).show()
    }

    private fun showPackages(pkgs: List<PackageDownloader.RemotePkg>) {
        remote = pkgs
        cleanupStaleParts()
        tvStatus.text = getString(R.string.pkg_free_space, humanBytes(OfflineData.freeBytes(this)))
        rebuildRows()
    }

    // ---------- ساخت رابط ----------
    private fun rebuildRows() {
        listBox.removeAllViews()
        rows.clear()

        val installed = OfflineData.installed
        val installedIds = installed.map { it.id }.toSet()

        if (remote.isEmpty()) {
            if (installed.isEmpty()) {
                listBox.addView(hintText(getString(R.string.pkg_empty)))
            }
        } else {
            listBox.addView(hintText(getString(R.string.pkg_available)))
            // حذف تکراری‌ها (مثلاً وقتی manifest سرور و منبع محلی هردو یک بسته را دارند)
            val seen = HashSet<String>()
            for (p in remote) {
                if (!seen.add(p.id)) continue
                listBox.addView(buildRow(p, installed.firstOrNull { it.id == p.id }))
            }
        }

        // بسته‌های نصب‌شده‌ای که در manifest نیستند (مثلاً نصب دستی)
        for (inst in installed) {
            if (installedIds.contains(inst.id) && remote.any { it.id == inst.id }) continue
            if (remote.any { it.id == inst.id }) continue
            listBox.addView(hintText(getString(R.string.pkg_installed_list)))
            listBox.addView(buildInstalledOnlyRow(inst))
            break // یک سرصفحه کافی است
        }
    }

    private fun hintText(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setPadding(0, dp(12), 0, dp(4))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun buildRow(p: PackageDownloader.RemotePkg, inst: OfflineData.Installed?): View {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(3).toFloat()
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(col)

        val tvName = TextView(this).apply {
            text = p.name
            textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        col.addView(tvName)

        val tvMeta = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF555555.toInt())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        col.addView(tvMeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val tvState = TextView(this).apply {
            textSize = 13f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            visibility = View.GONE
        }
        col.addView(tvState, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val action = Button(this)
        col.addView(action, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        val rv = RowViews(card, tvMeta, tvState, bar, action)
        rv.pkgId = p.id
        rows.add(rv)
        bindRow(rv, p, inst)
        return card
    }

    @SuppressLint("SetTextI18n")
    private fun bindRow(rv: RowViews, p: PackageDownloader.RemotePkg, inst: OfflineData.Installed?) {
        rv.pkgId = p.id
        val size = humanBytes(if (p.sizeBytes > 0) p.sizeBytes else 0)
        rv.tvMeta.text = listOf(p.region, p.dataDate, size).filter { it.isNotEmpty() }.joinToString(" • ")

        when {
            isDownloading(p.id) -> {
                rv.tvState.visibility = View.VISIBLE
                rv.bar.visibility = View.VISIBLE
                rv.action.visibility = View.VISIBLE
                rv.action.text = getString(R.string.pkg_stop)
                rv.action.setOnClickListener { cancelDownload() }
            }
            inst != null -> {
                rv.tvState.visibility = View.VISIBLE
                rv.tvState.setTextColor(0xFF1B5E20.toInt())
                rv.tvState.text = getString(
                    R.string.pkg_installed_meta,
                    inst.dataDate.ifEmpty { "?" }, humanBytes(inst.sizeBytes)
                )
                rv.bar.visibility = View.GONE
                if (p.id == Config.BASE_PACKAGE_ID) {
                    // بسته پایه کشوری همراه برنامه است؛ نه حذف دارد نه دوباره‌کاری
                    rv.action.visibility = View.GONE
                } else {
                    rv.action.visibility = View.VISIBLE
                    rv.action.text = getString(R.string.pkg_delete)
                    rv.action.setOnClickListener { confirmDelete(inst) }
                }
            }
            else -> {
                val err = lastError[p.id]
                if (err != null) {
                    rv.tvState.visibility = View.VISIBLE
                    rv.tvState.setTextColor(0xFFB00020.toInt())
                    rv.tvState.text = err
                } else rv.tvState.visibility = View.GONE
                rv.bar.visibility = View.GONE
                rv.action.visibility = View.VISIBLE
                // بسته‌های asset:// همیشه از نو کپی می‌شوند؛ «ادامه دانلود» فقط برای منابع انترنت
                val resume = !p.url.startsWith("asset://") && PackageDownloader.hasPartial(applicationContext, p.id)
                rv.action.text = getString(if (resume) R.string.pkg_resume else R.string.pkg_download)
                rv.action.setOnClickListener { startDownload(p) }
            }
        }
    }

    private fun buildInstalledOnlyRow(inst: OfflineData.Installed): View {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(3).toFloat()
            setContentPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(col)
        col.addView(TextView(this).apply {
            text = inst.name
            textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        col.addView(TextView(this).apply {
            text = listOf(inst.region, inst.dataDate, humanBytes(inst.sizeBytes)).filter { it.isNotEmpty() }
                .joinToString(" • ") + "  •  " + getString(R.string.pkg_installed_ok)
            textSize = 12f; setTextColor(0xFF555555.toInt())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val del = Button(this).apply { text = getString(R.string.pkg_delete) }
        if (inst.id == Config.BASE_PACKAGE_ID) {
            // بسته پایه کشوری: همراه برنامه — قابل حذف نیست
            col.addView(TextView(this).apply {
                text = getString(R.string.pkg_system)
                textSize = 12f; setTextColor(0xFF1B5E20.toInt())
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        } else {
            col.addView(del, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            del.setOnClickListener { confirmDelete(inst) }
        }
        return card
    }

    // ---------- عملیات دانلود ----------
    private fun isDownloading(id: String) = current?.let { !it.cancelled && currentId == id } == true
    private var currentId: String? = null

    private fun startDownload(p: PackageDownloader.RemotePkg) {
        if (current != null && !current!!.cancelled) {
            Toast.makeText(this, R.string.pkg_one_at_time, Toast.LENGTH_SHORT).show()
            return
        }
        // بررسی فضای خالی پیش از شروع
        val need = (p.sizeBytes * 1.6).toLong()
        if (p.sizeBytes > 0 && OfflineData.freeBytes(this) < need) {
            AlertDialog.Builder(this)
                .setTitle(R.string.offline_maps)
                .setMessage(getString(R.string.err_no_space_detail, humanBytes(p.sizeBytes), humanBytes(OfflineData.freeBytes(this))))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        lastError.remove(p.id)
        val d = Downloading(false)
        current = d
        currentId = p.id

        io.execute {
            val result = try { PackageDownloader.downloadAndInstall(
                applicationContext, p,
                object : PackageDownloader.Listener {
                    override fun onProgress(pr: PackageDownloader.Progress) {
                        ui.post { onProgress(pr) }
                    }
                },
                { d.cancelled }
            ) } catch (t: Throwable) {
                // هر خطای پیش‌بینی‌نشده (حتی OutOfMemory) به‌جای بسته‌شدن برنامه نمایش داده می‌شود
                ui.post {
                    onProgress(PackageDownloader.Progress(p.id, PackageDownloader.State.FAILED, 0, 0,
                        "network:" + t.javaClass.simpleName + " " + (t.message ?: "")))
                }
                null
            }
            ui.post {
                if (result != null) {
                    Toast.makeText(this, R.string.pkg_done, Toast.LENGTH_LONG).show()
                }
                current = null; currentId = null
                rebuildRows()
            }
        }
        rebuildRows()
    }

    private fun onProgress(pr: PackageDownloader.Progress) {
        val rv = rows.firstOrNull { it.pkgId == pr.pkgId } ?: return
        when (pr.state) {
            PackageDownloader.State.DOWNLOADING -> {
                rv.bar.visibility = View.VISIBLE
                val pct = if (pr.totalBytes > 0) (pr.downloadedBytes * 100 / pr.totalBytes).toInt().coerceIn(0, 100) else 0
                rv.bar.progress = pct
                rv.tvState.visibility = View.VISIBLE
                rv.tvState.setTextColor(0xFF0D47A1.toInt())
                rv.tvState.text = getString(
                    R.string.pkg_downloading, humanBytes(pr.downloadedBytes),
                    humanBytes(if (pr.totalBytes > 0) pr.totalBytes else 0)
                ) + "  ($pct٪)"
            }
            PackageDownloader.State.VERIFYING -> {
                rv.bar.isIndeterminate = true
                rv.tvState.visibility = View.VISIBLE
                rv.tvState.setTextColor(0xFF0D47A1.toInt())
                rv.tvState.text = getString(R.string.pkg_verifying)
            }
            PackageDownloader.State.EXTRACTING -> {
                rv.bar.isIndeterminate = true
                rv.tvState.visibility = View.VISIBLE
                rv.tvState.setTextColor(0xFF0D47A1.toInt())
                rv.tvState.text = getString(R.string.pkg_extracting)
            }
            PackageDownloader.State.FAILED -> {
                rv.bar.visibility = View.GONE
                rv.tvState.visibility = View.VISIBLE
                rv.tvState.setTextColor(0xFFB00020.toInt())
                val errText = when (pr.message) {
                    "no_space" -> getString(R.string.err_no_space)
                    "checksum" -> getString(R.string.err_checksum)
                    "invalid" -> getString(R.string.err_invalid)
                    "asset_missing" -> getString(R.string.err_asset_missing)
                    "network" -> getString(R.string.err_network_dl)
                    else -> {
                        val m = pr.message ?: ""
                        if (m.startsWith("http_") || m.startsWith("network:"))
                            getString(R.string.err_network_dl) + "\n[" + m + "]"
                        else getString(R.string.pkg_failed_generic)
                    }
                }
                rv.tvState.text = errText
                // ⚠️ رفع اشکال: پس از شکست، فهرست از نو ساخته می‌شد و پیام خطا ناپدید می‌شد؛
                // کاربر فقط «هیچ اتفاقی نیفتاد» می‌دید. حالا پیام تا تلاش بعدی می‌ماند.
                lastError[pr.pkgId] = errText
                Toast.makeText(this, R.string.pkg_failed_generic, Toast.LENGTH_LONG).show()
            }
            else -> { }
        }
    }

    private fun cancelDownload() {
        current?.cancelled = true
        Toast.makeText(this, R.string.pkg_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(inst: OfflineData.Installed) {
        if (inst.id == Config.BASE_PACKAGE_ID) {
            Toast.makeText(this, R.string.pkg_system, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pkg_delete)
            .setMessage(getString(R.string.pkg_delete_confirm, inst.name))
            .setPositiveButton(R.string.pkg_delete) { _, _ ->
                io.execute {
                    OfflineData.delete(inst.id)
                    ui.post { rebuildRows() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- ابزارها ----------
    private fun humanBytes(b: Long): String {
        if (b <= 0) return "0MB"
        val mb = b / (1024.0 * 1024.0)
        return if (mb >= 1000) String.format(Locale.US, "%.1fGB", mb / 1024)
        else String.format(Locale.US, "%.0fMB", mb)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        current?.cancelled = true
        io.shutdownNow()
        super.onDestroy()
    }
}
