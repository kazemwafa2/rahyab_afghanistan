package af.rahyab.app

import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.SymbolLayer

/**
 * تم و رنگ‌های نقشه — پالت رسمی راهیاب (درخواست کاربر ۲۰۲۶-۱۱-۱۶):
 * «روز: زمین سبز-خاکستری روشن، کوچه خاکستری، سرک‌ها فیروزه‌ای، پارک سبز، آب آبی»
 * «شب: سرمه‌ای تیره (#111C28) با جاده‌های براق فیروزه‌ای»
 *
 * مقادیر دقیق از طرح داده‌شدهٔ کاربر (CSS با متغیرهای روز/شب):
 *   day:   background #F3F5F2، building #E5E9E4، border #D0D8D2، alley #C8D2D0،
 *          secondary #2F9D93، primary #13B0A2، highway #4FD1C5، park #A8D08D،
 *          trees #55A65C، water #4EA8E8، route #3B82F6، labels #263A40،
 *          halo #FFFFFF، restaurant #F39C12، shop #8C6FE6، hospital #EF5350
 *   night: background #111C28، building #263541، border #354652، alley #53636D،
 *          secondary #287F78، primary #12A995، highway #38D6C0، park #183C35،
 *          trees #3E9861، water #173F59، route #398BFF، labels #E5EEF2،
 *          halo #111C28، restaurant #FFB547، shop #A98BEE، hospital #F16C78
 *
 * ذخیره‌سازی: برای هر عنصر دو کلید جدا برای روز و شب:
 *   «th_<key>»  (روز)   و   «thd_<key>»  (شب)
 * نبودِ کلید = رنگ پیش‌فرض این پالت (ELEMENTS).
 * مهاجرت: ensurePaletteVersion هر پالت ذخیره‌شدهٔ قدیمی را یک بار پاک می‌کند تا
 * پالت جدید کاربر «پیش‌فرض» شود (بند درخواست: «این رنگ و طرح پیش‌فرض برنامه باشد»).
 */
object MapTheme {

    /** یک عنصر نقشه: کلید ذخیره‌سازی، نام نمایشی، رنگ پیش‌فرض روز و شب */
    data class Elem(val key: String, val titleRes: Int, val light: String, val dark: String)

    /** عناصر قابل سفارشی‌سازی — پالت دقیق کاربر، روز و شب */
    val ELEMENTS = listOf(
        Elem("bg",              R.string.th_background,      "#F3F5F2", "#111C28"),
        Elem("building",        R.string.th_building,        "#E5E9E4", "#263541"),
        Elem("building_border", R.string.th_building_border, "#D0D8D2", "#354652"),
        Elem("alley",           R.string.th_alley,           "#C8D2D0", "#53636D"),
        Elem("street",          R.string.th_street,          "#2F9D93", "#287F78"),
        Elem("primary",         R.string.th_primary,         "#13B0A2", "#12A995"),
        Elem("highway",         R.string.th_highway,         "#4FD1C5", "#38D6C0"),
        Elem("park",            R.string.th_park,            "#A8D08D", "#183C35"),
        Elem("trees",           R.string.th_trees,           "#55A65C", "#3E9861"),
        Elem("water",           R.string.th_water,           "#4EA8E8", "#173F59"),
        Elem("label",           R.string.th_label,           "#263A40", "#E5EEF2"),
        Elem("label_halo",      R.string.th_label_halo,      "#FFFFFF", "#111C28"),
        Elem("route",           R.string.th_route,           "#3B82F6", "#398BFF"),
        Elem("restaurant",      R.string.th_restaurant,      "#F39C12", "#FFB547"),
        Elem("shop",            R.string.th_shop,            "#8C6FE6", "#A98BEE"),
        Elem("hospital",        R.string.th_hospital,        "#EF5350", "#F16C78")
    )

    private val KEYS = ELEMENTS.map { it.key }

    /** نسخهٔ پالت — با تغییر پالت رسمی، عدد بالا می‌رود تا رنگ‌های قدیمی پاک شوند */
    private const val PALETTE_VERSION = 2
    private const val PREF_PALETTE_VER = "palette_ver"

    /**
     * مهاجرت پالت: اگر نسخهٔ ذخیره‌شده قدیمی باشد، همهٔ رنگ‌های سفارشی پاک می‌شوند
     * تا پالت جدید پیش‌فرض شود. هرگز ترجیح کاربر در نسخهٔ فعلی بازنویسی نمی‌شود.
     */
    fun ensurePaletteVersion(prefs: SharedPreferences) {
        if (prefs.getInt(PREF_PALETTE_VER, 0) >= PALETTE_VERSION) return
        resetAll(prefs)
        prefs.edit().putInt(PREF_PALETTE_VER, PALETTE_VERSION).apply()
        Log.i("MapTheme", "پالت جدید راهیاب به‌عنوان پیش‌فرض اعمال شد (v$PALETTE_VERSION)")
    }

    // ---------- طرح‌های آماده ----------

    /**
     * طرح آماده: فقط کلیدهای اصلی را تعریف می‌کند؛ کلیدهای تعریف‌نشده از پالت
     * پیش‌فرض (ELEMENTS) می‌آیند — پس هیچ طرحی پالت را ناقص نمی‌کند.
     */
    data class Preset(
        val titleRes: Int,
        val light: Map<String, String> = emptyMap(),
        val dark: Map<String, String> = emptyMap()
    )

    val PRESETS = listOf(
        // کلاسیک راهیاب — همان پالت رسمی کاربر
        Preset(R.string.theme_classic, emptyMap(), emptyMap()),
        // سرسبز — باغ‌ها برجسته
        Preset(R.string.theme_green,
            mapOf("bg" to "#E9EFE2", "building" to "#E5E2D6", "building_border" to "#CFD4C5",
                "alley" to "#D3DFC6", "street" to "#5B9E7E", "primary" to "#3E8E6E",
                "highway" to "#2F9D93", "park" to "#A9D89B", "trees" to "#7CB86A",
                "water" to "#7FC4E8", "label" to "#2F4433", "label_halo" to "#FFFFFF"),
            mapOf("bg" to "#0A1610", "building" to "#1C2E24", "building_border" to "#2A4238",
                "alley" to "#274433", "street" to "#3E7A5C", "primary" to "#2F9D6E",
                "highway" to "#4FC08A", "park" to "#1A3D22", "trees" to "#1E4A28",
                "water" to "#10405C", "label" to "#C4DCC9", "label_halo" to "#0A1610")),
        // کویری — تن‌های گرم صحرا
        Preset(R.string.theme_desert,
            mapOf("bg" to "#F2E8D3", "building" to "#E7DBC0", "building_border" to "#D6C7A6",
                "alley" to "#E3D6B8", "street" to "#D89E45", "primary" to "#C98A35",
                "highway" to "#E0A44E", "park" to "#BFC98A", "trees" to "#9FB35E",
                "water" to "#6FB9D8", "label" to "#54432C", "label_halo" to "#FFFFFF"),
            mapOf("bg" to "#171006", "building" to "#2E2718", "building_border" to "#3E341E",
                "alley" to "#3E341E", "street" to "#8A7448", "primary" to "#A9853E",
                "highway" to "#D89E45", "park" to "#3A4422", "trees" to "#2E3A1C",
                "water" to "#10405C", "label" to "#DCCFB2", "label_halo" to "#171006")),
        // اقیانوس — آبی خنک
        Preset(R.string.theme_ocean,
            mapOf("bg" to "#E2EBEF", "building" to "#DFE7EA", "building_border" to "#CBD8DD",
                "alley" to "#CFDDE3", "street" to "#4E9FC9", "primary" to "#2E86B8",
                "highway" to "#1E6FA8", "park" to "#B7D8C4", "trees" to "#8FBF9E",
                "water" to "#5FA8D8", "label" to "#2C4450", "label_halo" to "#FFFFFF"),
            mapOf("bg" to "#060E14", "building" to "#152630", "building_border" to "#1E3340",
                "alley" to "#1E3038", "street" to "#2E6E8E", "primary" to "#2E86B8",
                "highway" to "#48A8D0", "park" to "#143026", "trees" to "#18382A",
                "water" to "#0C4468", "label" to "#C2D8E4", "label_halo" to "#060E14"))
    )

    // ---------- خواندن و ذخیره ----------

    private fun prefKey(key: String, dark: Boolean) = if (dark) "thd_$key" else "th_$key"

    /** رنگ سفارشی ذخیره‌شده؛ null یعنی رنگ پیش‌فرض پالت */
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

    /** رنگ سفارشی اگر باشد، وگرنه رنگ پیش‌فرض پالت برای روز/شب */
    fun elemColor(prefs: SharedPreferences, key: String, dark: Boolean): Int {
        customOf(prefs, key, dark)?.let { return parse(it) ?: 0 }
        val el = ELEMENTS.firstOrNull { it.key == key } ?: return 0
        return parse(if (dark) el.dark else el.light) ?: 0
    }

    /** اعمال طرح آماده روی هر دو حالت روز و شب؛ کلیدهای تعریف‌نشده = پیش‌فرض پالت */
    fun applyPreset(prefs: SharedPreferences, preset: Preset) {
        val e = prefs.edit()
        for (k in KEYS) {
            val l = preset.light[k]
            val d = preset.dark[k]
            if (l != null) e.putString("th_$k", l) else e.remove("th_$k")
            if (d != null) e.putString("thd_$k", d) else e.remove("thd_$k")
        }
        e.apply()
    }

    /** بازگشت همه عناصر به پالت پیش‌فرض رسمی */
    fun resetAll(prefs: SharedPreferences) {
        val e = prefs.edit()
        KEYS.forEach { e.remove("th_$it"); e.remove("thd_$it") }
        e.apply()
    }

    // ---------- رنگ‌های مسیر ناوبری (برای لایه‌های مسیر در MainActivity) ----------

    /** رنگ خط مسیر اصلی — از پالت کاربر */
    fun routeColor(prefs: SharedPreferences, dark: Boolean): Int = elemColor(prefs, "route", dark)

    /** پوشش تیرهٔ زیر خط مسیر */
    fun routeCasingColor(prefs: SharedPreferences, dark: Boolean): Int =
        shade(routeColor(prefs, dark), if (dark) -0.55 else -0.45)

    /** مسیرهای جایگزین — همان رنگ مسیر ولی محو به رنگ زمین */
    fun routeAltColor(prefs: SharedPreferences, dark: Boolean): Int =
        mix(routeColor(prefs, dark), elemColor(prefs, "bg", dark), 0.55)

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

    private const val BG = "bg"
    private const val STREET = "street"
    private const val PRIMARY = "primary"
    private const val HIGHWAY = "highway"
    private const val ALLEY = "alley"
    private const val BUILDING = "building"
    private const val PARK = "park"
    private const val TREES = "trees"
    private const val WATER = "water"
    private const val RES = "res"
    private const val LABEL = "label"
    private const val POIDOT = "poidot"
    private const val RAIL = "rail"
    private const val CASING = "casing_"

    /** عنصر نقشه مربوط به یک لایه بر اساس شناسه — برای هر دو سبک آفلاین و آنلاین (Liberty) */
    private fun classify(id: String, dark: Boolean): String? = when {
        id == "background" -> BG
        id == "water" || id.startsWith("waterway") || id.startsWith("water_") -> WATER
        // درخت‌ها از پارک جدا شدند (پالت کاربر: trees جدا از park)
        id == "landcover-wood" || id == "landcover_wood" ||
            id == "landuse-forest" || id == "landuse_forest" -> TREES
        id == "park" || id == "landcover-grass" || id == "landcover_grass" ||
            id == "landuse-pitch" || id == "landuse_pitch" ||
            id == "landuse-cemetery" || id == "landuse_cemetery" -> PARK
        id == "building" || id.startsWith("building") -> BUILDING
        id == "landuse-residential" || id == "landuse_residential" -> RES
        // لایهٔ نقطه‌های رنگی مکان‌ها — رستوران/دوکان/شفاخانه از پالت کاربر
        id == "poi-dot" -> POIDOT
        id == "rail" || id.startsWith("railway") || id.startsWith("rail-") -> RAIL
        id == "road-path" || id == "road_path_pedestrian" ||
            id == "bridge_path_pedestrian" || id == "tunnel_path_pedestrian" -> ALLEY
        // پوشش‌های سرک (casing) — رنگ تیره‌تر از رنگ سرک والد
        id.endsWith("-casing") -> CASING + classify(id.dropLast(7), dark)
        id.endsWith("_casing") -> CASING + classify(id.dropLast(7), dark)
        // نام‌ها و نوشته‌ها روی نقشه
        id.startsWith("place-") || id.startsWith("place_") || id.startsWith("poi") ||
            id.startsWith("highway-name") || id.startsWith("road-street-label") ||
            id.startsWith("airport-label") ||
            id == "water_name" || id == "waterway-label" -> LABEL
        // سرک‌های آفلاین (سبک خود راهیاب) — طبقه‌بندی دقیق پالت:
        // minor = کوچه | tertiary/secondary = سرک دوم | primary = سرک اصلی | trunk/motorway = سریع‌الصل
        id == "road-minor" -> ALLEY
        id == "road-tertiary" || id == "road-secondary" -> STREET
        id == "road-primary" -> PRIMARY
        id == "road-trunk" || id == "road-motorway" -> HIGHWAY
        // سرک‌های آنلاین (Liberty/Dark): پیشوند road_/bridge_/tunnel_
        id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_") -> {
            val core = id.substringAfter('_')
            when (core) {
                "motorway", "motorway_link" -> HIGHWAY
                "trunk_primary" -> PRIMARY
                "secondary_tertiary", "link" -> STREET
                "minor", "service_track" -> ALLEY
                else -> null
            }
        }
        else -> null
    }

    // ---------- لایه‌های مسیر ناوبری ----------

    /** رنگ‌گذاری لایه‌های مسیر (ساخته‌شده در MainActivity.addRouteLayers) با پالت کاربر */
    private fun applyRouteLayers(layer: Layer, prefs: SharedPreferences, dark: Boolean) {
        when (layer.id) {
            "route-layer" -> (layer as? LineLayer)?.setProperties(lineColor(routeColor(prefs, dark)))
            "route-casing" -> (layer as? LineLayer)?.setProperties(lineColor(routeCasingColor(prefs, dark)))
            "route-alt-line" -> (layer as? LineLayer)?.setProperties(lineColor(routeAltColor(prefs, dark)))
            // پوشش سفید مسیر جایگزین و مسیر اصلی طبق سبک نشان ثابت می‌ماند
            "origin-layer" -> (layer as? CircleLayer)?.setProperties(circleColor(routeColor(prefs, dark)))
        }
    }

    // ---------- نقطه‌های رنگی مکان‌ها (poi-dot) ----------

    /** کلاس‌های poi-dot با رنگ پایه سبک — فقط سه دستهٔ پالت کاربر رنگ عوض می‌کنند */
    private val POI_BASE_CLASSES = listOf(
        "hospital", "clinic", "doctors", "pharmacy", "school", "college", "university",
        "kindergarten", "place_of_worship", "fuel", "bank", "atm", "post", "police",
        "fire_station", "supermarket", "marketplace", "restaurant", "cafe", "fast_food",
        "hotel", "museum", "attraction", "aerodrome", "bus_station", "station", "parking", "park"
    )
    private val POI_BASE_COLORS = listOf(
        "#E53935", "#EF5350", "#EF5350", "#D81B60", "#1E88E5", "#1E88E5", "#1565C0",
        "#1E88E5", "#6A1B9A", "#FB8C00", "#00897B", "#00897B", "#00897B", "#3949AB",
        "#E53935", "#2E7D32", "#2E7D32", "#43A047", "#43A047", "#43A047",
        "#F4511E", "#8D6E63", "#8D6E63", "#546E7A", "#546E7A", "#546E7A", "#5C6BC0", "#2E7D32"
    )

    /** رنگ یک کلاس poi-dot با پالت کاربر (رستوران/دوکان/شفاخانه) و رنگ پایه برای بقیه */
    private fun poiDotColor(cls: String, prefs: SharedPreferences, dark: Boolean, fallback: String): Int {
        val key = when (cls) {
            "hospital", "clinic", "doctors", "fire_station" -> "hospital"
            "restaurant", "cafe", "fast_food" -> "restaurant"
            "supermarket", "marketplace" -> "shop"
            else -> null
        }
        return if (key != null) elemColor(prefs, key, dark) else parse(fallback) ?: 0
    }

    /** عبارت match کامل رنگ poi-dot برای اعمال زنده روی سبک */
    private fun poiDotExpression(prefs: SharedPreferences, dark: Boolean): Expression {
        // ساختار GL: ["match", input, label1, output1, …, default]
        val parts = ArrayList<Expression>(POI_BASE_CLASSES.size * 2 + 1)
        for (i in POI_BASE_CLASSES.indices) {
            parts.add(Expression.literal(POI_BASE_CLASSES[i]))
            parts.add(Expression.color(poiDotColor(POI_BASE_CLASSES[i], prefs, dark, POI_BASE_COLORS[i])))
        }
        return Expression.match(
            Expression.get("class"),
            *parts.toTypedArray(),
            Expression.color(parse("#90A4AE") ?: 0)
        )
    }

    private fun patchPoiDotJson(paint: org.json.JSONObject, prefs: SharedPreferences, dark: Boolean): Boolean {
        // بازسازی عبارت match رنگ نقطه‌ها با سه دستهٔ پالت کاربر
        val arr = org.json.JSONArray()
        arr.put("match"); arr.put(org.json.JSONArray().put("get").put("class"))
        for (i in POI_BASE_CLASSES.indices) {
            arr.put(POI_BASE_CLASSES[i])
            arr.put(toHex(poiDotColor(POI_BASE_CLASSES[i], prefs, dark, POI_BASE_COLORS[i])))
        }
        arr.put("#90A4AE")
        paint.put("circle-color", arr)
        return true
    }

    // ---------- اعمال روی سبک آفلاین (JSON خام) ----------

    /**
     * وصلهٔ رنگ‌ها روی JSON سبک (rahyab_light/dark.json).
     * فقط عناصری که رنگ سفارشی دارند تغییر می‌کنند؛ بقیه دست نمی‌خورند.
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
                    BG -> if (paint.has("background-color")) {
                        paint.put("background-color", toHex(elemColor(prefs, "bg", dark))); changed = true
                    }
                    RES -> {
                        val c = toHex(mix(elemColor(prefs, "bg", dark), elemColor(prefs, "building", dark), 0.45))
                        if (paint.has("fill-color")) { paint.put("fill-color", c); changed = true }
                    }
                    BUILDING -> {
                        if (paint.has("fill-color")) {
                            paint.put("fill-color", toHex(elemColor(prefs, "building", dark))); changed = true
                        }
                        // خط دور عمارت — پالت کاربر (building-border)
                        if (paint.has("fill-outline-color")) {
                            paint.put("fill-outline-color", toHex(elemColor(prefs, "building_border", dark))); changed = true
                        }
                        if (type == "fill-extrusion" && paint.has("fill-extrusion-color")) {
                            paint.put("fill-extrusion-color", extrusionJson(prefs, dark)); changed = true
                        }
                    }
                    PARK -> if (paint.has("fill-color")) {
                        paint.put("fill-color", toHex(elemColor(prefs, "park", dark))); changed = true
                    }
                    TREES -> if (paint.has("fill-color")) {
                        paint.put("fill-color", toHex(elemColor(prefs, "trees", dark))); changed = true
                    }
                    WATER -> if (paint.has("fill-color")) {
                        paint.put("fill-color", toHex(elemColor(prefs, "water", dark))); changed = true
                    } else if (type == "line" && paint.has("line-color")) {
                        paint.put("line-color", toHex(elemColor(prefs, "water", dark))); changed = true
                    }
                    RAIL -> if (paint.has("line-color")) {
                        paint.put("line-color", toHex(mix(elemColor(prefs, "alley", dark), elemColor(prefs, "label", dark), 0.5))); changed = true
                    }
                    POIDOT -> if (paint.has("circle-color")) {
                        changed = patchPoiDotJson(paint, prefs, dark)
                    }
                    STREET, PRIMARY, HIGHWAY, ALLEY -> if (paint.has("line-color")) {
                        paint.put("line-color", toHex(elemColor(prefs, el, dark))); changed = true
                    }
                    LABEL -> {
                        if (paint.has("text-color")) {
                            paint.put("text-color", toHex(elemColor(prefs, "label", dark))); changed = true
                        }
                        // هالهٔ پشت نام‌ها (label-halo پالت کاربر)
                        if (paint.has("text-halo-color")) {
                            paint.put("text-halo-color", toHex(elemColor(prefs, "label_halo", dark))); changed = true
                        }
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

    /** رنگ ساختمان سه‌بعدی: شیب گرادیان سبک حفظ می‌شود، فقط رنگ‌ها از پالت می‌آیند */
    private fun extrusionJson(prefs: SharedPreferences, dark: Boolean): org.json.JSONArray {
        val b = elemColor(prefs, "building", dark)
        val arr = org.json.JSONArray()
        arr.put("interpolate"); arr.put(org.json.JSONArray().put("linear"))
        arr.put(org.json.JSONArray().put("get").put("render_height"))
        arr.put(0); arr.put(toHex(b))
        arr.put(24); arr.put(toHex(shade(b, 0.06)))
        arr.put(60); arr.put(toHex(shade(b, 0.12)))
        return arr
    }

    // ---------- اعمال زنده روی سبک بارگذاری‌شده (آنلاین + آفلاین) ----------

    /**
     * وصلهٔ رنگ روی سبک فعال بدون بارگذاری دوباره — بلافاصله پس از ذخیره رنگ می‌گیرد.
     */
    fun applyToStyle(style: Style, prefs: SharedPreferences, dark: Boolean) {
        if (!anyCustom(prefs, dark)) return
        try {
            for (layer in style.layers) {
                val el = classify(layer.id, dark)
                if (el == null) { applyRouteLayers(layer, prefs, dark); continue }
                when {
                    el == BG && layer is BackgroundLayer ->
                        layer.setProperties(backgroundColor(elemColor(prefs, "bg", dark)))
                    el == BG && layer is FillLayer ->
                        layer.setProperties(fillColor(elemColor(prefs, "bg", dark)))
                    el == RES && layer is FillLayer ->
                        layer.setProperties(fillColor(mix(elemColor(prefs, "bg", dark), elemColor(prefs, "building", dark), 0.45)))
                    el == BUILDING && layer is FillLayer -> {
                        layer.setProperties(fillColor(elemColor(prefs, "building", dark)))
                        layer.setProperties(fillOutlineColor(elemColor(prefs, "building_border", dark)))
                    }
                    el == BUILDING && layer is FillExtrusionLayer -> {
                        layer.setProperties(fillExtrusionColor(extrusionExpression(prefs, dark)))
                    }
                    el == PARK && layer is FillLayer ->
                        layer.setProperties(fillColor(elemColor(prefs, "park", dark)))
                    el == TREES && layer is FillLayer ->
                        layer.setProperties(fillColor(elemColor(prefs, "trees", dark)))
                    el == WATER && layer is FillLayer ->
                        layer.setProperties(fillColor(elemColor(prefs, "water", dark)))
                    el == WATER && layer is LineLayer ->
                        layer.setProperties(lineColor(elemColor(prefs, "water", dark)))
                    el == RAIL && layer is LineLayer ->
                        layer.setProperties(lineColor(mix(elemColor(prefs, "alley", dark), elemColor(prefs, "label", dark), 0.5)))
                    el == POIDOT && layer is CircleLayer ->
                        layer.setProperties(circleColor(poiDotExpression(prefs, dark)))
                    (el == STREET || el == PRIMARY || el == HIGHWAY || el == ALLEY) && layer is LineLayer ->
                        layer.setProperties(lineColor(elemColor(prefs, el, dark)))
                    el.startsWith(CASING) && layer is LineLayer -> {
                        val base = elemColor(prefs, el.removePrefix(CASING), dark)
                        layer.setProperties(lineColor(shade(base, if (dark) -0.52 else -0.24)))
                    }
                    el == LABEL && layer is SymbolLayer -> {
                        layer.setProperties(textColor(elemColor(prefs, "label", dark)))
                        layer.setProperties(textHaloColor(elemColor(prefs, "label_halo", dark)))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MapTheme", "اعمال رنگ روی سبک ناموفق: ${e.message}")
        }
    }

    /** عبارت رنگ ساختمان سه‌بعدی برای اعمال زنده */
    private fun extrusionExpression(prefs: SharedPreferences, dark: Boolean): Expression {
        val b = elemColor(prefs, "building", dark)
        return Expression.interpolate(
            Expression.linear(), Expression.get("render_height"),
            Expression.stop(0.0, Expression.color(b)),
            Expression.stop(24.0, Expression.color(shade(b, 0.06))),
            Expression.stop(60.0, Expression.color(shade(b, 0.12)))
        )
    }
}
