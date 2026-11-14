package af.rahyab.app

import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.SymbolLayer

/**
 * تم و رنگ‌های نقشه — سفارشی‌سازی کامل رنگ عناصر نقشه:
 * زمین پس‌زمینه، سرک‌ها، سرک اصلی و سریع‌الصل، کوچه و لین،
 * عمارت و خانه‌ها، پارک و باغ، آب و رودخانه و رنگ نام‌ها.
 *
 * درخواست کاربر: «تم ورنگ‌ها هیچ تغیر نکرده» — از این نسخه رنگ‌های انتخابی
 * کاربر روی هر دو سبک آنلاین و آفلاین اعمال می‌شود و بلافاصله پس از ذخیره،
 * نقشه بدون بارگذاری دوباره رنگ می‌گیرد.
 *
 * ذخیره‌سازی: برای هر عنصر دو کلید جدا برای روز و شب:
 *   «th_<key>»  (روز)   و   «thd_<key>»  (شب)
 * نبودِ کلید = رنگ پیش‌فرض خود سبک (دست نمی‌زنیم).
 */
object MapTheme {

    /** یک عنصر نقشه: کلید ذخیره‌سازی، نام نمایشی، رنگ پیش‌فرض روز و شب */
    data class Elem(val key: String, val titleRes: Int, val light: String, val dark: String)

    /** عناصر قابل سفارشی‌سازی — ترتیب نمایش در صفحه تنظیمات */
    val ELEMENTS = listOf(
        Elem("bg",       R.string.th_background, "#EAE4D8", "#0B1218"),
        Elem("street",   R.string.th_street,     "#FFFFFF", "#5C6F86"),
        Elem("highway",  R.string.th_highway,    "#F2B84F", "#E9B457"),
        Elem("alley",    R.string.th_alley,      "#DFD8CC", "#39485A"),
        Elem("building", R.string.th_building,   "#E4DED4", "#1D2833"),
        Elem("park",     R.string.th_park,       "#CDE6B8", "#14301F"),
        Elem("water",    R.string.th_water,      "#9FCFF3", "#0E2B47"),
        Elem("label",    R.string.th_label,      "#3A3F44", "#C9D6E2")
    )

    /** طرح‌های آماده — ترتیب رنگ‌ها: bg,street,highway,alley,building,park,water,label */
    data class Preset(val titleRes: Int, val light: List<String>, val dark: List<String>)

    val PRESETS = listOf(
        // کلاسیک راهیاب (روز روشن، شب سرمه‌ای)
        Preset(R.string.theme_classic, listOf(
            "#EAE4D8", "#FFFFFF", "#F2B84F", "#DFD8CC", "#E4DED4", "#CDE6B8", "#9FCFF3", "#3A3F44"
        ), listOf(
            "#0B1218", "#5C6F86", "#E9B457", "#39485A", "#1D2833", "#14301F", "#0E2B47", "#C9D6E2"
        )),
        // سرسبز — باغ‌ها برجسته
        Preset(R.string.theme_green, listOf(
            "#E9EFE2", "#FFFFFF", "#7BB661", "#D3DFC6", "#E5E2D6", "#A9D89B", "#7FC4E8", "#2F4433"
        ), listOf(
            "#0A1610", "#5B8A66", "#8FCB8F", "#274433", "#1C2E24", "#1A3D22", "#10405C", "#C4DCC9"
        )),
        // کویری — تن‌های گرم صحرا
        Preset(R.string.theme_desert, listOf(
            "#F2E8D3", "#FFFDF4", "#E0A44E", "#E3D6B8", "#E7DBC0", "#BFC98A", "#6FB9D8", "#54432C"
        ), listOf(
            "#171006", "#8A7448", "#D89E45", "#3E341E", "#2E2718", "#3A4422", "#10405C", "#DCCFB2"
        )),
        // اقیانوس — آبی خنک
        Preset(R.string.theme_ocean, listOf(
            "#E2EBEF", "#FFFFFF", "#4E9FC9", "#CFDDE3", "#DFE7EA", "#B7D8C4", "#5FA8D8", "#2C4450"
        ), listOf(
            "#060E14", "#48708A", "#66B6DC", "#1E3038", "#152630", "#143026", "#0C4468", "#C2D8E4"
        ))
    )

    private val KEYS = ELEMENTS.map { it.key }

    // ---------- خواندن و ذخیره ----------

    private fun prefKey(key: String, dark: Boolean) = if (dark) "thd_$key" else "th_$key"

    /** رنگ سفارشی ذخیره‌شده؛ null یعنی رنگ پیش‌فرض خود سبک */
    fun customOf(prefs: SharedPreferences, key: String, dark: Boolean): String? {
        val v = prefs.getString(prefKey(key, dark), null) ?: return null
        return if (v.isBlank() || parse(v) == null) null else v
    }

    fun setCustom(prefs: SharedPreferences, key: String, value: String?, dark: Boolean) {
        val e = prefs.edit()
        if (value == null) e.remove(prefKey(key, dark)) else e.putString(prefKey(key, dark), value)
        e.apply()
    }

    fun anyCustom(prefs: SharedPreferences, dark: Boolean): Boolean =
        KEYS.any { customOf(prefs, it, dark) != null }

    /** رنگ سفارشی اگر باشد، وگرنه رنگ پیش‌فرض خود عنصر برای روز/شب */
    fun elemColor(prefs: SharedPreferences, key: String, dark: Boolean): Int {
        customOf(prefs, key, dark)?.let { return parse(it) ?: 0 }
        val el = ELEMENTS.firstOrNull { it.key == key } ?: return 0
        return parse(if (dark) el.dark else el.light) ?: 0
    }

    /** اعمال طرح آماده روی هر دو حالت روز و شب */
    fun applyPreset(prefs: SharedPreferences, preset: Preset) {
        val e = prefs.edit()
        KEYS.forEachIndexed { i, k ->
            e.putString("th_$k", preset.light[i])
            e.putString("thd_$k", preset.dark[i])
        }
        e.apply()
    }

    /** بازگشت همه عناصر به رنگ پیش‌فرض سبک */
    fun resetAll(prefs: SharedPreferences) {
        val e = prefs.edit()
        KEYS.forEach { e.remove("th_$it"); e.remove("thd_$it") }
        e.apply()
    }

    // ---------- ابزار رنگ ----------

    fun parse(c: String): Int? = try {
        Color.parseColor(c.trim())
    } catch (e: Exception) { null }

    fun toHex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

    /** تیره/روشن‌سازی: f بین -۱ تا +۱ (منفی = تیره‌تر) */
    fun shade(c: Int, f: Double): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        val delta = if (f < 0) hsv[2] * f else (1.0 - hsv[2]) * f
        hsv[2] = (hsv[2] + delta).toFloat().coerceIn(0.04f, 1f)
        return Color.HSVToColor(hsv)
    }

    fun mix(a: Int, b: Int, t: Double): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    // ---------- طبقه‌بندی لایه‌ها ----------

    private const val BG = "bg"; private const val STREET = "street"
    private const val HIGHWAY = "highway"; private const val ALLEY = "alley"
    private const val BUILDING = "building"; private const val PARK = "park"
    private const val WATER = "water"; private const val RES = "res"
    private const val LABEL = "label"
    private const val CASING = "casing_"

    /** عنصر نقشه مربوط به یک لایه بر اساس شناسه — برای هر دو سبک آفلاین و آنلاین (Liberty) */
    private fun classify(id: String, dark: Boolean): String? = when {
        id == "background" -> BG
        id == "water" || id.startsWith("waterway") || id.startsWith("water_") -> WATER
        id == "park" || id == "landcover_grass" || id == "landuse_pitch" ||
            id == "landuse_cemetery" || id == "landcover_wood" -> PARK
        id == "building" || id.startsWith("building") -> BUILDING
        id == "landuse-residential" || id == "landuse_residential" -> RES
        id == "road-path" || id == "road_path_pedestrian" ||
            id == "bridge_path_pedestrian" || id == "tunnel_path_pedestrian" -> ALLEY
        // پوشش‌های سرک (casing) — رنگ تیره‌تر از رنگ سرک والد
        id.endsWith("-casing") -> CASING + classify(id.dropLast(7), dark)
        id.endsWith("_casing") -> CASING + classify(id.dropLast(7), dark)
        // نام‌ها و نوشته‌ها روی نقشه
        id.startsWith("place-") || id.startsWith("place_") || id.startsWith("poi") ||
            id.startsWith("highway-name") || id.startsWith("road-street-label") ||
            id == "water_name" || id == "waterway-label" -> LABEL
        // سرک‌های آفلاین (سبک خود راهیاب)
        id == "road-minor" -> ALLEY
        id == "road-tertiary" || id == "road-secondary" || id == "road-primary" -> STREET
        id == "road-trunk" || id == "road-motorway" -> HIGHWAY
        // سرک‌های آنلاین (Liberty/Dark): پیشوند road_/bridge_/tunnel_
        id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_") -> {
            val core = id.substringAfter('_')
            when (core) {
                "motorway", "motorway_link", "trunk_primary" -> HIGHWAY
                "secondary_tertiary", "link" -> STREET
                "minor", "service_track" -> ALLEY
                else -> null
            }
        }
        else -> null
    }

    // ---------- اعمال روی سبک آفلاین (JSON خام) ----------

    /**
     * وصلهٔ رنگ‌ها روی JSON سبک آفلاین (rahyab_light/dark.json).
     * فقط عناصری که کاربر رنگ سفارشی داده تغییر می‌کنند؛ بقیه دست نمی‌خورند.
     */
    fun applyJson(json: String, prefs: SharedPreferences, dark: Boolean): String {
        if (!anyCustom(prefs, dark)) return json
        return try {
            val o = org.json.JSONObject(json)
            val layers = o.getJSONArray("layers")
            var changed = false
            for (i in 0 until layers.length()) {
                val l = layers.getJSONObject(i)
                val id = l.optString("id") ?: continue
                val paint = l.optJSONObject("paint") ?: continue
                val type = l.optString("type")
                when (val el = classify(id, dark)) {
                    BG -> if (paint.has("fill-color")) {
                        paint.put("fill-color", toHex(elemColor(prefs, "bg", dark))); changed = true
                    }
                    RES -> {
                        val c = toHex(mix(elemColor(prefs, "bg", dark), elemColor(prefs, "building", dark), 0.45))
                        if (paint.has("fill-color")) { paint.put("fill-color", c); changed = true }
                    }
                    BUILDING -> if (paint.has("fill-color")) {
                        paint.put("fill-color", toHex(elemColor(prefs, "building", dark))); changed = true
                    }
                    PARK -> if (paint.has("fill-color")) {
                        paint.put("fill-color", toHex(elemColor(prefs, "park", dark))); changed = true
                    }
                    WATER -> if (paint.has("fill-color")) {
                        paint.put("fill-color", toHex(elemColor(prefs, "water", dark))); changed = true
                    } else if (type == "line" && paint.has("line-color")) {
                        paint.put("line-color", toHex(elemColor(prefs, "water", dark))); changed = true
                    }
                    STREET, HIGHWAY, ALLEY -> if (paint.has("line-color")) {
                        paint.put("line-color", toHex(elemColor(prefs, el, dark))); changed = true
                    }
                    LABEL -> if (paint.has("text-color")) {
                        paint.put("text-color", toHex(elemColor(prefs, "label", dark))); changed = true
                    }
                    else -> if (el != null && el.startsWith(CASING)) {
                        val base = elemColor(prefs, el.removePrefix(CASING), dark)
                        if (paint.has("line-color")) {
                            paint.put("line-color", toHex(shade(base, if (dark) -0.52 else -0.24))); changed = true
                        }
                    }
                }
            }
            if (changed) o.toString() else json
        } catch (e: Exception) {
            Log.w("MapTheme", "وصلهٔ JSON سبک ناموفق: ${e.message}")
            json
        }
    }

    // ---------- اعمال زنده روی سبک بارگذاری‌شده (آنلاین + آفلاین) ----------

    /**
     * وصلهٔ رنگ روی سبک فعال بدون بارگذاری دوباره — بلافاصله پس از ذخیره رنگ می‌گیرد.
     * برای سبک آنلاین (Liberty/Dark) و آفلاین هر دو کار می‌کند.
     */
    fun applyToStyle(style: Style, prefs: SharedPreferences, dark: Boolean) {
        if (!anyCustom(prefs, dark)) return
        try {
            for (layer in style.layers) {
                val el = classify(layer.id, dark) ?: continue
                when {
                    el == BG && layer is FillLayer ->
                        layer.setProperties(fillColor(elemColor(prefs, "bg", dark)))
                    el == RES && layer is FillLayer ->
                        layer.setProperties(fillColor(mix(elemColor(prefs, "bg", dark), elemColor(prefs, "building", dark), 0.45)))
                    el == BUILDING && layer is FillLayer -> {
                        layer.setProperties(fillColor(elemColor(prefs, "building", dark)))
                    }
                    el == PARK && layer is FillLayer ->
                        layer.setProperties(fillColor(elemColor(prefs, "park", dark)))
                    el == WATER && layer is FillLayer ->
                        layer.setProperties(fillColor(elemColor(prefs, "water", dark)))
                    el == WATER && layer is LineLayer ->
                        layer.setProperties(lineColor(elemColor(prefs, "water", dark)))
                    (el == STREET || el == HIGHWAY || el == ALLEY) && layer is LineLayer ->
                        layer.setProperties(lineColor(elemColor(prefs, el, dark)))
                    el.startsWith(CASING) && layer is LineLayer -> {
                        val base = elemColor(prefs, el.removePrefix(CASING), dark)
                        layer.setProperties(lineColor(shade(base, if (dark) -0.52 else -0.24)))
                    }
                    el == LABEL && layer is SymbolLayer ->
                        layer.setProperties(textColor(elemColor(prefs, "label", dark)))
                }
            }
        } catch (e: Exception) {
            Log.w("MapTheme", "اعمال رنگ روی سبک ناموفق: ${e.message}")
        }
    }
}
