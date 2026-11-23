package af.rahyab.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * آزمون اسکن پشتیبان دارایی‌های ریلیز و قرارداد manifest —
 * اشکال گزارش‌شدهٔ کاربر: فایل‌های بسته تازه در ریلیز بارگذاری شده بود ولی
 * manifest.json قدیمی مانده بود؛ برنامه باید از اسکن دارایی‌ها نسخهٔ جدید را ببیند.
 */
class PackageScanTest {

    private val html = """
        <div>
          <a href="/kazemwafa2/rahyab_afghanistan/releases/download/map-data/kabul-2026-11-15.rahpkg.zip">kabul</a>
          <a href="/kazemwafa2/rahyab_afghanistan/releases/download/map-data/kabul-2026-11-11.rahpkg.zip">old</a>
          <a href="/kazemwafa2/rahyab_afghanistan/releases/download/map-data/herat-2026-11-15.rahpkg.zip">herat</a>
          <a href="/kazemwafa2/rahyab_afghanistan/releases/download/map-data/SHA256SUMS.txt">sums</a>
          <a href="/kazemwafa2/rahyab_afghanistan/releases/download/map-data/manifest.json">manifest</a>
          <img src="/kazemwafa2/rahyab_afghanistan/releases/download/map-data/note.png">
        </div>
    """.trimIndent()

    @Test
    fun `asset scan finds packages with newest date and correct url`() {
        val pkgs = PackageDownloader.parseReleaseAssets(html)
        assertEquals(2, pkgs.size)
        val kabul = pkgs.first { it.id == "kabul" }
        assertEquals("2026-11-15", kabul.dataDate)
        assertTrue(kabul.url.endsWith("/kabul-2026-11-15.rahpkg.zip"))
        assertTrue(kabul.url.startsWith("https://github.com/kazemwafa2/rahyab_afghanistan/releases/download/map-data/"))
        assertEquals("herat", pkgs.first { it.id == "herat" }.id)
    }

    @Test
    fun `asset scan ignores non-package files`() {
        val pkgs = PackageDownloader.parseReleaseAssets(html)
        assertTrue(pkgs.none { it.id.contains("SHA256") || it.id.contains("manifest") })
    }

    @Test
    fun `manifest parser accepts rebuilt contract`() {
        val pkg = JSONObject()
            .put("id", "kabul").put("name", "کابل").put("region", "کابل")
            .put("dataDate", "2026-11-15").put("builderVersion", "1.2.0")
            .put("file", "kabul-2026-11-15.rahpkg.zip")
            .put("url", "https://github.com/kazemwafa2/rahyab_afghanistan/releases/download/map-data/kabul-2026-11-15.rahpkg.zip")
            .put("sizeBytes", 32037470L)
            .put("sha256", "a".repeat(64))
        val body = JSONObject().put("schema", 1).put("updated", "2026-11-16T00:00:00Z")
            .put("packages", JSONArray().put(pkg)).toString()
        val list = PackageDownloader.fetchManifestFromJson(body)
        assertEquals(1, list.size)
        assertEquals("2026-11-15", list[0].dataDate)
        assertEquals(32037470L, list[0].sizeBytes)
        assertEquals(64, list[0].sha256.length)
    }

    @Test
    fun `manifest parser rejects entries without http url`() {
        val pkg = JSONObject()
            .put("id", "x").put("file", "x.rahpkg.zip").put("dataDate", "2026-11-15")
        val body = JSONObject().put("packages", JSONArray().put(pkg)).toString()
        assertEquals(0, PackageDownloader.fetchManifestFromJson(body).size)
    }

    /**
     * نقشهٔ اختصاصی کامل (afghanistan_full) — بستهٔ تازهٔ نسخهٔ 1.1.4:
     * باید از manifest و اسکن دارایی‌ها هر دو پیدا شود و تاریخ تازه‌ترش،
     * دکمهٔ «به‌روزرسانی» را برای کاربر فعال کند.
     */
    @Test
    fun `full nationwide package is discovered from manifest and asset scan`() {
        val full = JSONObject()
            .put("id", "afghanistan_full").put("name", "افغانستان (نقشهٔ اختصاصی کامل)")
            .put("region", "افغانستان (نقشهٔ اختصاصی کامل)")
            .put("dataDate", "2026-11-17").put("builderVersion", "1.2.0")
            .put("file", "afghanistan_full-2026-11-17.rahpkg.zip")
            .put("url", "https://github.com/kazemwafa2/rahyab_afghanistan/releases/download/map-data/afghanistan_full-2026-11-17.rahpkg.zip")
            .put("sizeBytes", 159_300_000L)
            .put("sha256", "b".repeat(64))
            .put("bounds", JSONObject().put("west", 60.48).put("south", 29.36).put("east", 74.90).put("north", 38.51))
        val body = JSONObject().put("schema", 1).put("updated", "2026-11-17T00:00:00Z")
            .put("packages", JSONArray().put(full)).toString()
        val list = PackageDownloader.fetchManifestFromJson(body)
        assertEquals(1, list.size)
        assertEquals("afghanistan_full", list[0].id)
        assertEquals("2026-11-17", list[0].dataDate)
        assertTrue(list[0].sizeBytes > 100_000_000)

        // اسکن دارایی‌ها هم باید همین بسته را ببیند
        val scanned = PackageDownloader.parseReleaseAssets(
            """<a href="/kazemwafa2/rahyab_afghanistan/releases/download/map-data/afghanistan_full-2026-11-17.rahpkg.zip">full</a>"""
        )
        assertEquals(1, scanned.size)
        assertEquals("afghanistan_full", scanned[0].id)
    }
}
