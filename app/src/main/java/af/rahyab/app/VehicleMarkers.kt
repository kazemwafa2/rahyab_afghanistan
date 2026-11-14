package af.rahyab.app

/**
 * نشانگرهای مسیریابی — به سبک صفحه «نشانگر مسیریابی» نشان/بلد.
 *
 * ۱۶ وسیله + فلش، همه با نمای بالا و ظاهر سه‌بعدی (سایه، شیشه، علامت تکسی)
 * مطابق ناوگان تکسی افغانستان (آبی-سفید). نشانگر انتخابی کاربر جای تیرک
 * ناوبری روی نقشه می‌نشیند و با جهت حرکت می‌چرخد.
 *
 * تنظیم ذخیره‌شده: pref «nav_marker» = id وسیله (پیش‌فرض «arrow»).
 */
object VehicleMarkers {

    /** یک نشانگر: شناسه، نام، منبع تصویر، عرض نمایش بر حسب dp، طول نمایش بر حسب dp */
    data class Marker(
        val id: String,
        val nameRes: Int,
        val drawableRes: Int,
        val widthDp: Int,
        val heightDp: Int
    )

    val ALL: List<Marker> = listOf(
        // فلش پیش‌فرض — با کد رسم می‌شود (makeNavArrowBitmap)
        Marker("arrow", R.string.veh_arrow, 0, 34, 34),
        Marker("landcruiser", R.string.veh_landcruiser, R.drawable.veh_landcruiser, 40, 70),
        Marker("toyota_suv", R.string.veh_toyota_suv, R.drawable.veh_toyota_suv, 40, 70),
        Marker("lexus", R.string.veh_lexus, R.drawable.veh_lexus, 40, 70),
        Marker("mercedes", R.string.veh_mercedes, R.drawable.veh_mercedes, 38, 68),
        Marker("bmw", R.string.veh_bmw, R.drawable.veh_bmw, 38, 68),
        Marker("camry", R.string.veh_camry, R.drawable.veh_camry, 38, 68),
        Marker("hyundai", R.string.veh_hyundai, R.drawable.veh_hyundai, 38, 68),
        Marker("kia", R.string.veh_kia, R.drawable.veh_kia, 38, 68),
        Marker("hiaze", R.string.veh_hiaze, R.drawable.veh_hiaze, 42, 76),
        Marker("mercedes_van", R.string.veh_mercedes_van, R.drawable.veh_mercedes_van, 42, 76),
        Marker("bus", R.string.veh_bus, R.drawable.veh_bus, 44, 82),
        Marker("tuktuk", R.string.veh_tuktuk, R.drawable.veh_tuktuk, 40, 66),
        Marker("moto", R.string.veh_moto, R.drawable.veh_moto, 30, 62),
        Marker("cargo_trike", R.string.veh_cargo_trike, R.drawable.veh_cargo_trike, 42, 66),
        Marker("bike", R.string.veh_bike, R.drawable.veh_bike, 30, 62)
    )

    fun byId(id: String): Marker = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** نشانگر انتخابی کاربر */
    fun selected(prefs: android.content.SharedPreferences): Marker =
        byId(prefs.getString("nav_marker", "arrow") ?: "arrow")

    fun save(prefs: android.content.SharedPreferences, id: String) {
        prefs.edit().putString("nav_marker", id).apply()
    }

    /** آیا نشانگر انتخابی فلش است؟ */
    fun isArrow(prefs: android.content.SharedPreferences): Boolean =
        selected(prefs).id == "arrow"
}
