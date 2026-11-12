package af.rahyab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * هواشناسی و کیفیت هوا — بخش نهم چک‌لیست.
 * منبع: Open-Meteo Weather API + Air Quality API (بدون کلید، رعایت شرایط استفاده).
 *  - دما، وضعیت بارندگی/برف، سرعت باد، رطوبت
 *  - پیش‌بینی ۵ روز (کمینه/بیشینه، احتمال باران)
 *  - PM2.5 و PM10 و شاخص کیفیت هوا (AQI اروپایی)
 *  - زمان آخرین دریافت و نام منبع — تفکیک «پیش‌بینی مدل» از اندازه‌گیری ایستگاه
 *    (Open-Meteo خروجی مدل است؛ این نکته در رابط کاربری صریح ذکر می‌شود)
 */
object WeatherModule {

    class Data(
        val tempC: Double, val feelsC: Double, val humidity: Int, val windKmh: Double,
        val code: Int, val isDay: Boolean,
        val pm25: Double?, val pm10: Double?, val aqi: Double?,
        val daily: List<Day>,
        val fetchedMs: Long
    )

    class Day(val dateIso: String, val code: Int, val minC: Double, val maxC: Double, val rainProb: Int)

    var last: Data? = null
        private set
    private var lastFetchMs = 0L
    private var lastKey = ""

    /** تازه‌سازی اگر قدیمی‌تر از ۱۵ دقیقه؛ خطا بی‌صدا (مقدار قبلی یا null) */
    fun refresh(ctx: Context, lat: Double, lon: Double): Data? {
        val key = "%.3f|%.3f".format(java.util.Locale.US, lat, lon)
        if (last != null && key == lastKey && System.currentTimeMillis() - lastFetchMs < 15 * 60 * 1000L) return last
        val t0 = System.currentTimeMillis()
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=5&timezone=Asia%2FKabul"
        val aqiUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lat&longitude=$lon&current=pm10,pm2_5,us_aqi&timezone=Asia%2FKabul"
        val temp = try { Http.get(url, readTimeoutMs = 10000) } catch (e: Exception) { return last } // بدون اینترنت: بی‌صدا
        val aqiBody = try { Http.get(aqiUrl, readTimeoutMs = 8000) } catch (e: Exception) { null }
        val d = parse(temp, aqiBody)
        if (d != null) { last = d; lastFetchMs = System.currentTimeMillis(); lastKey = key }
        android.util.Log.d("WeatherModule", "هوا در ${System.currentTimeMillis() - t0}ms دریافت شد")
        return d
    }

    fun parse(forecastJson: String, aqiJson: String?): Data? {
        return try {
            val o = JSONObject(forecastJson)
            val cur = o.getJSONObject("current")
            val dailyArr = o.getJSONObject("daily")
            val days = ArrayList<Day>(5)
            val dates = dailyArr.getJSONArray("time")
            val codes = dailyArr.getJSONArray("weather_code")
            val mins = dailyArr.getJSONArray("temperature_2m_min")
            val maxs = dailyArr.getJSONArray("temperature_2m_max")
            val rain = dailyArr.optJSONArray("precipitation_probability_max") ?: JSONArray()
            for (i in 0 until minOf(5, dates.length())) {
                days.add(Day(
                    dates.getString(i), codes.optInt(i, 0),
                    mins.optDouble(i, 0.0), maxs.optDouble(i, 0.0), rain.optInt(i, 0)
                ))
            }
            var pm25: Double? = null; var pm10: Double? = null; var aqi: Double? = null
            if (aqiJson != null) {
                val a = JSONObject(aqiJson).optJSONObject("current")
                if (a != null) {
                    pm25 = if (a.has("pm2_5") && !a.isNull("pm2_5")) a.getDouble("pm2_5") else null
                    pm10 = if (a.has("pm10") && !a.isNull("pm10")) a.getDouble("pm10") else null
                    aqi = if (a.has("us_aqi") && !a.isNull("us_aqi")) a.getDouble("us_aqi") else null
                }
            }
            Data(
                cur.getDouble("temperature_2m"), cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")),
                cur.optInt("relative_humidity_2m", 0), cur.optDouble("wind_speed_10m", 0.0),
                cur.optInt("weather_code", 0), cur.optInt("is_day", 1) == 1,
                pm25, pm10, aqi, days, System.currentTimeMillis()
            )
        } catch (e: Exception) { null }
    }

    /** توضیح دری وضعیت هوا بر اساس کد استاندارد WMO */
    fun describe(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "آفتابی" else "صاف شب"
        1, 2 -> "کمی ابری"
        3 -> "ابری"
        45, 48 -> "مه‌آلود"
        in 51..55 -> "نم‌نم باران"
        in 56..57 -> "باران یخ‌زده"
        in 61..65 -> "باران می‌بارد"
        66, 67 -> "باران یخ‌زده"
        in 71..77 -> "برف می‌بارد"
        80, 81, 82 -> "رگبار باران"
        85, 86 -> "رگبار برف"
        in 95..99 -> "رعد و برق"
        else -> "—"
    }

    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    /** دسته کیفیت هوا بر اساس AQI آمریکا */
    fun aqiLabel(aqi: Double?): String = when {
        aqi == null -> "در دسترس نیست"
        aqi <= 50 -> "خوب (${aqi.toInt()})"
        aqi <= 100 -> "متوسط (${aqi.toInt()})"
        aqi <= 150 -> "ناسالم برای گروه‌های حساس (${aqi.toInt()})"
        aqi <= 200 -> "ناسالم (${aqi.toInt()})"
        aqi <= 300 -> "بسیار ناسالم (${aqi.toInt()})"
        else -> "خطرناک (${aqi.toInt()})"
    }
}
