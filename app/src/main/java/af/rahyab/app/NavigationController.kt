package af.rahyab.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * کنترلر ناوبری در سطح برنامه (نه اکتیویتی).
 *
 * به این ترتیب ناوبری با صفحه خاموش هم ادامه پیدا می‌کند:
 *  - سرویس Foreground (NavigationService) پروسه را زنده نگه می‌دارد و اعلان دائمی می‌دهد.
 *  - به‌روزرسانی موقعیت GPS در همین کنترلر انجام می‌شود و رابط کاربری فقط گوش می‌دهد.
 *  - تشخیص خروج از مسیر، محاسبه مجدد (اول آفلاین، بعد آنلاین) و اعلام صوتی این‌جاست.
 *  - جلوگیری از محاسبه مجدد مکرر با حداقل فاصله زمانی ۸ ثانیه و شمارنده پایداری.
 */
object NavigationController {

    private const val TAG = "NavController"
    private const val OFF_ROUTE_M = 45.0
    private const val OFF_ROUTE_TICKS = 3     // سه قرائت پیاپی بیرون مسیر
    private const val REROUTE_COOLDOWN_MS = 8000L
    private const val ARRIVE_M = 18.0

    data class UiState(
        val navigating: Boolean,
        val remainingM: Double,
        val remainingS: Double,
        val stepIndex: Int,
        val toManeuverM: Double,
        val instructionKey: String,
        val instructionName: String,
        val offRoute: Boolean,
        val rerouting: Boolean,
        val arrived: Boolean
    )

    private val ui = Handler(Looper.getMainLooper())
    private val io = SafeExecutors.fixed(1)

    private val uiListeners = CopyOnWriteArrayList<(UiState) -> Unit>()
    private val fixListeners = CopyOnWriteArrayList<(Location) -> Unit>()
    private val alertListeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile var navigating = false; private set

    /** حالت رانندگی آزاد: بدون مقصد؛ سرعت‌سنج + هشدارها فعال (مثل «حالت رانندگی آزاد» نشان) */
    @Volatile var freeMode = false; private set
    var voiceEnabled: Boolean
        get() = voice?.enabled ?: true
        set(v) { voice?.enabled = v; prefsPut("voice", v) }

    /** مسیر فعال ناوبری (برای بازکشیدن خط مسیر در رابط کاربری) */
    val currentRoute: RouteResult? get() = route

    // آخرین وضعیت برای همگام‌سازی رابط کاربری پس از بازگشت به برنامه
    val lastRemainingM: Double get() = _lastRemainingM
    val lastRemainingS: Double get() = _lastRemainingS
    val lastStepIndex: Int get() = _lastStepIndex
    val lastToManeuver: Double get() = _lastToManeuver
    val lastKey: String get() = _lastKey
    val lastName: String get() = _lastName

    fun stopVoice() { voice?.stop() }

    /** دسترسی راهنمای صوتی برای صفحه تنظیمات (فهرست صداها، پیش‌نمایش، بسته انتخابی…) */
    val voiceGuide: VoiceGuide? get() = voice

    /** آیا صدای دری موجود است؟ (بسته داخل برنامه یا موتور سیستم) */
    fun voiceHasPersian(): Boolean = voice?.hasPersianVoice() ?: false

    /** پخش جمله آزمایشی صوتی */
    fun testVoice(): Boolean = voice?.testVoice() ?: false

    /** پخش تأیید صوتی ثبت گزارش */
    fun voiceReportSaved() { voice?.speakAlert("report_saved", "") }

    /** بازکردن صفحه نصب داده صدای فارسی یا تنظیمات متن‌به‌گفتار گوشی */
    fun openVoiceInstall(ctx: Context) {
        val v = voice ?: return
        try { ctx.startActivity(v.installVoiceDataIntent()) } catch (e: Exception) {
            try { ctx.startActivity(v.ttsSettingsIntent()) } catch (e2: Exception) { }
        }
    }

    private var appCtx: Context? = null
    private var prefs: android.content.SharedPreferences? = null
    private var voice: VoiceGuide? = null
    private var serviceCallback: ((String, String) -> Unit)? = null

    private var route: RouteResult? = null
    private var destination: Place? = null
    private var profile: Profile = Profile.CAR
    private var engine: NavEngine? = null
    private val announced = HashSet<String>()
    private var offCount = 0
    private var rerouting = false
    private var lastReroute = 0L
    private var arrivedAnnounced = false
    private var lastFix: Location? = null

    private var wakeLock: PowerManager.WakeLock? = null

    // ---------- اتصال رابط کاربری ----------
    fun addUiListener(l: (UiState) -> Unit) { uiListeners.add(l); emit() }
    fun removeUiListener(l: (UiState) -> Unit) { uiListeners.remove(l) }
    fun addFixListener(l: (Location) -> Unit) { fixListeners.add(l) }
    fun removeFixListener(l: (Location) -> Unit) { fixListeners.remove(l) }
    /** گوش‌دهی هشدارها: کلید عبارت صوتی هشدار (برای نمایش متن در رابط) */
    fun addAlertListener(l: (String) -> Unit) { alertListeners.add(l) }
    fun removeAlertListener(l: (String) -> Unit) { alertListeners.remove(l) }

    /** اعلان سرویس برای به‌روزرسانی متن ناوبری */
    fun setServiceCallback(cb: (String, String) -> Unit) { serviceCallback = cb }

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = appCtx!!.getSharedPreferences("settings", Context.MODE_PRIVATE)
        voice = VoiceGuide(appCtx!!) { /* نبود TTS فقط یک بار از طریق رابط اعلام می‌شود */ }
        voice?.enabled = prefs!!.getBoolean("voice", true)
    }

    /**
     * کانتکست با زبان برنامه (نه زبان گوشی) — برای جمله‌های صوتی و هشدارها.
     * زبان از AppCompatDelegate گرفته می‌شود تا روی همه نسخه‌های اندروید درست باشد.
     */
    private fun locCtx(): Context {
        val base = appCtx ?: return appCtx ?: throw IllegalStateException("init نشده")
        val tags = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val tag = if (!tags.isEmpty) tags.toLanguageTags() else
            base.resources.configuration.locales[0].toLanguageTag()
        val conf = Configuration(base.resources.configuration)
        conf.setLocale(androidx.core.os.LocaleListCompat.forLanguageTags(tag)[0])
        return base.createConfigurationContext(conf)
    }

    private fun prefsPut(k: String, v: Boolean) { prefs?.edit()?.putBoolean(k, v)?.apply() }

    private fun emit() {
        val r = route
        val eng = engine
        val st = UiState(
            navigating = navigating,
            remainingM = lastRemainingM,
            remainingS = lastRemainingS,
            stepIndex = lastStepIndex,
            toManeuverM = lastToManeuver,
            instructionKey = lastKey,
            instructionName = lastName,
            offRoute = offCount > 0,
            rerouting = rerouting,
            arrived = arrivedAnnounced
        )
        uiListeners.forEach { l -> ui.post { try { l(st) } catch (e: Exception) { } } }
        if (navigating && r != null && eng != null) {
            serviceCallback?.invoke(instrTitle(lastKey, lastName), formatRemain(st))
        }
    }

    private var _lastRemainingM = 0.0
    private var _lastRemainingS = 0.0
    private var _lastStepIndex = 0
    private var _lastToManeuver = 0.0
    private var _lastKey = "depart"
    private var _lastName = ""

    /** نقطه چسبیده‌به‌مسیر (برای تیرک جهت — همیشه دقیقاً روی مسیر) */
    val hasSnap: Boolean get() = _hasSnap
    val lastSnapLat: Double get() = _snapLat
    val lastSnapLon: Double get() = _snapLon
    val lastSnapBearing: Double get() = _snapBearing
    private var _hasSnap = false
    private var _snapLat = 0.0
    private var _snapLon = 0.0
    private var _snapBearing = 0.0

    fun instrTitle(key: String, name: String, ctx: Context? = null): String {
        val app = try { ctx ?: locCtx() } catch (e: Exception) { appCtx } ?: return key
        val base = app.getString(
            when (key) {
                "depart" -> R.string.nav_depart
                "arrive" -> R.string.nav_arrive
                "roundabout" -> R.string.nav_roundabout
                "exit_roundabout" -> R.string.nav_exit_roundabout
                "right" -> R.string.nav_right
                "left" -> R.string.nav_left
                "slight_right" -> R.string.nav_slight_right
                "slight_left" -> R.string.nav_slight_left
                "sharp_right" -> R.string.nav_sharp_right
                "sharp_left" -> R.string.nav_sharp_left
                "uturn" -> R.string.nav_uturn
                "offroute" -> R.string.nav_offroute
                "rerouted" -> R.string.nav_rerouted
                "continue" -> R.string.nav_continue
                else -> R.string.nav_straight
            }
        )
        return if (name.isNotEmpty()) "$base\n$name" else base
    }

    /** دستور بعد از پیچ فعلی (برای پیش‌نمایش «سپس…» در بنر ناوبری) */
    fun nextInstruction(stepIndex: Int): String? {
        val r = route ?: return null
        val s = r.steps.getOrNull(stepIndex + 1) ?: return null
        val k = keyOf(s)
        return instrTitle(k, s.name)
    }

    /** کلید پیچ بعد از پیچ فعلی (برای نمایش پیکان «و بعد» در بنر) */
    fun nextKey(stepIndex: Int): String? {
        val r = route ?: return null
        val s = r.steps.getOrNull(stepIndex + 1) ?: return null
        if (s.type == "arrive") return null
        return keyOf(s)
    }

    private fun formatRemain(st: UiState): String {
        val app = appCtx ?: return ""
        val d = formatDistance(app, st.remainingM)
        val t = formatDuration(app, st.remainingS)
        return app.getString(R.string.nav_remaining, d, t)
    }

    // ---------- شروع و توقف ----------
    fun start(r: RouteResult, dest: Place, prof: Profile): Boolean {
        val ctx = appCtx ?: return false
        if (!hasLocationPermission(ctx)) return false
        freeMode = false
        route = r
        destination = dest
        profile = prof
        engine = NavEngine(r)
        navigating = true
        announced.clear()
        offCount = 0
        rerouting = false
        arrivedAnnounced = false
        _lastKey = "depart"; _lastName = r.steps.firstOrNull()?.name ?: ""
        _lastRemainingM = r.distanceM
        _lastRemainingS = r.durationS

        acquireWakeLock(ctx)
        startLocationUpdates(ctx)
        NavigationService.start(ctx)
        emit()
        speakKey("depart", r.steps.firstOrNull()?.name ?: "")
        // فوری پس از شروع، وضعیت را با آخرین موقعیت معلوم به‌روز کن
        lastFix?.let { onFix(it) }
        return true
    }

    fun stop() {
        navigating = false
        engine = null
        route = null
        destination = null
        _hasSnap = false
        stopLocationUpdates()
        releaseWakeLock()
        voice?.stop()
        NavigationService.stop(appCtx ?: return)
        emit()
    }

    // ---------- رانندگی آزاد (مثل نشان) ----------
    /** شروع رانندگی آزاد: سرعت‌سنج، هشدارهای گزارش‌ها و حد سرعت بدون مسیر مقصد */
    fun startFree(): Boolean {
        val ctx = appCtx ?: return false
        if (!hasLocationPermission(ctx)) return false
        freeMode = true
        announced.clear()
        acquireWakeLock(ctx)
        startLocationUpdates(ctx)
        voice?.speak(locCtx().getString(R.string.free_started), listOf("free_start"))
        emit()
        return true
    }

    fun stopFree() {
        freeMode = false
        stopLocationUpdates()
        releaseWakeLock()
        voice?.stop()
        emit()
    }

    // ---------- موقعیت ----------
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onFix(location) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(ctx: Context) {
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.5f, locListener, Looper.getMainLooper())
            }
        } catch (e: Exception) { Log.e(TAG, "شروع به‌روزرسانی موقعیت ناموفق", e) }
    }

    private fun stopLocationUpdates() {
        val ctx = appCtx ?: return
        try { (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locListener) } catch (e: Exception) { }
    }

    fun hasLocationPermission(ctx: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock(ctx: Context) {
        if (wakeLock != null) return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rahyab:nav").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // سقف ۶ ساعت
            }
        } catch (e: Exception) { }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (e: Exception) { }
        wakeLock = null
    }

    // ---------- پردازش هر قرائت GPS ----------
    fun onFix(loc: Location) {
        lastFix = loc
        fixListeners.forEach { l -> ui.post { try { l(loc) } catch (e: Exception) { } } }
        checkAlerts(loc) // هشدارها در ناوبری و رانندگی آزاد هر دو فعال‌اند
        if (!navigating) return
        val r = route ?: return
        val eng = engine ?: return
        if (loc.hasAccuracy() && loc.accuracy > 60f) return // قرائت ضعیف نادیده گرفته می‌شود

        io.execute {
            val st = try { eng.update(loc.latitude, loc.longitude) } catch (e: Exception) { return@execute }

            // نقطه چسبیده‌به‌مسیر برای تیرک جهت — از شروع مبدا تا مقصد روی مسیر می‌ماند
            _hasSnap = true
            _snapLat = st.snapLat
            _snapLon = st.snapLon
            _snapBearing = st.snapBearing

            // خروج از مسیر: با شمارنده پایداری تا از پرش GPS محاسبه مجدد مکرر نشود
            if (st.offRouteM > OFF_ROUTE_M) {
                offCount++
                if (offCount >= OFF_ROUTE_TICKS && !rerouting) reroute(loc)
                postState(st, "offroute", "")
                return@execute
            }
            offCount = 0

            if (st.remaining < ARRIVE_M) { arrive(); return@execute }

            val step = r.steps.getOrNull(st.stepIndex)
            var key = lastKey
            var name = lastName
            if (step != null) {
                key = keyOf(step)
                name = step.name
                postState(st, key, name)
                announce(st, key, name)
            } else {
                postState(st, key, name)
            }

            _lastRemainingM = st.remaining
            _lastRemainingS = if (eng.total > 0) r.durationS * st.remaining / eng.total else 0.0
            _lastStepIndex = st.stepIndex
            _lastToManeuver = st.toManeuver
            _lastKey = key
            _lastName = name
            serviceCallback?.invoke(instrTitle(key, name), formatRemain(makeState(st, key, name)))
        }
    }

    private fun makeState(st: NavEngine.State, key: String, name: String) = UiState(
        navigating, lastRemainingM, lastRemainingS, st.stepIndex, st.toManeuver, key, name,
        offCount > 0, rerouting, arrivedAnnounced
    )

    private fun postState(st: NavEngine.State, key: String, name: String) {
        val s = makeState(st, key, name)
        uiListeners.forEach { l -> ui.post { try { l(s) } catch (e: Exception) { } } }
    }

    // ---------- اعلام صوتی ----------
    private fun speakKey(key: String, name: String) {
        val app = try { locCtx() } catch (e: Exception) { appCtx } ?: return
        val text = instrTitle(key, name, app)
        voice?.speak(text, listOf(key))
    }

    private fun announce(st: NavEngine.State, key: String, name: String) {
        val app = try { locCtx() } catch (e: Exception) { appCtx } ?: return
        val d = st.toManeuver
        val th = when {
            d <= 40 -> 40
            d <= 200 -> 200
            d <= 500 -> 500
            else -> return
        }
        val id = "${st.stepIndex}:$th"
        if (announced.contains(id)) return
        for (t in listOf(40, 200, 500)) if (t >= th) announced.add("${st.stepIndex}:$t")
        val instr = instrTitle(key, name, app)
        if (th == 40) {
            voice?.speak(instr, listOf(key))
        } else {
            val dist = formatDistance(app, th.toDouble())
            voice?.speak(app.getString(R.string.nav_in, dist, instr), listOf("d$th", key))
        }
    }

    // ---------- هشدارهای جاده‌ای (مثل تنظیمات هشدارهای نشان) ----------
    private var alertState = AlertEngine.State()
    private var lastAlertScan = 0L
    private var cachedReports: List<AlertEngine.ReportPt> = emptyList()

    private fun alertConfig(): AlertEngine.Config {
        val p = prefs ?: return AlertEngine.Config()
        return AlertEngine.Config(
            bump = p.getBoolean("a_bump", true),
            accident = p.getBoolean("a_accident", true),
            traffic = p.getBoolean("a_traffic", true),
            closed = p.getBoolean("a_closed", true),
            hazard = p.getBoolean("a_hazard", true),
            cam = p.getBoolean("a_cam", true),
            overspeed = p.getBoolean("a_speed", true),
            speedLimitKmh = p.getInt("a_limit", 80)
        )
    }

    /** متن نمایشی هشدار بر اساس کلید عبارت صوتی */
    fun alertText(clip: String): String {
        val app = try { locCtx() } catch (e: Exception) { appCtx } ?: return ""
        return app.getString(
            when (clip) {
                "alert_bump" -> R.string.alert_bump_text
                "alert_accident" -> R.string.alert_accident_text
                "alert_traffic" -> R.string.alert_traffic_text
                "alert_closed" -> R.string.alert_closed_text
                "alert_cam" -> R.string.alert_cam_text
                "alert_speed" -> R.string.alert_speed_text
                else -> R.string.alert_hazard_text
            }
        )
    }

    private fun checkAlerts(loc: Location) {
        if (!(navigating || freeMode)) return
        val ctx = appCtx ?: return
        val cfg = alertConfig()
        if (!cfg.anyReportEnabled && !cfg.overspeed) return
        val now = System.currentTimeMillis()
        val scan = now - lastAlertScan > 5000
        if (scan) {
            lastAlertScan = now
            io.execute {
                // اسکن گزارش‌های فعال هر ۵ ثانیه (کم‌هزینه برای دیتابیس)
                val list = try {
                    ReportsModule.active(ctx).map { AlertEngine.ReportPt(it.id, it.type, it.lat, it.lon) }
                } catch (e: Exception) { emptyList() }
                cachedReports = list
            }
        }
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        io.execute {
            val (ns, events) = AlertEngine.evaluate(
                loc.latitude, loc.longitude, speedKmh,
                cachedReports, cfg, alertState, System.currentTimeMillis()
            )
            alertState = ns
            for (ev in events) {
                voice?.speakAlert(ev.clip, alertText(ev.clip))
                ui.post {
                    alertListeners.forEach { l -> try { l(ev.clip) } catch (e: Exception) { } }
                }
            }
        }
    }

    // ---------- محاسبه مجدد مسیر ----------
    private fun reroute(loc: Location) {
        val dest = destination ?: return
        val now = System.currentTimeMillis()
        if (rerouting || now - lastReroute < REROUTE_COOLDOWN_MS) return
        rerouting = true
        offCount = 0
        speakKey("offroute", "")
        emit()
        io.execute {
            var newRoute: RouteResult? = null
            try {
                val pair = Services.route(profile, loc.latitude, loc.longitude, dest.lat, dest.lon)
                newRoute = pair.first
            } catch (e: Exception) {
                Log.w(TAG, "محاسبه مجدد ناموفق: ${e.message}")
            }
            ui.post {
                rerouting = false
                lastReroute = System.currentTimeMillis()
                val nr = newRoute
                if (nr != null && navigating) {
                    route = nr
                    engine = NavEngine(nr)
                    announced.clear()
                    offCount = 0
                    speakKey("rerouted", "")
                    emit()
                } else {
                    emit()
                }
            }
        }
    }

    /** مسیر تازه (مثلاً پس از تغییر مقصد) بدون شروع دوباره ناوبری */
    fun replaceRoute(r: RouteResult) {
        route = r
        engine = NavEngine(r)
        announced.clear()
        offCount = 0
        _lastRemainingM = r.distanceM
        _lastRemainingS = r.durationS
    }

    private fun arrive() {
        if (!arrivedAnnounced) {
            arrivedAnnounced = true
            speakKey("arrive", "")
            emit()
            // پس از اعلام رسیدن، چند ثانیه صبر و سپس توقف
            ui.postDelayed({ if (navigating && arrivedAnnounced) stop() }, 4000)
        }
    }

    // ---------- قالب‌بندی ----------
    /** ساعت روز به شکل ۰۹:۲۱ برای نمایش آخرین به‌روزرسانی */
    fun formatTimeOfDay(ms: Long): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        return String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    fun formatDistance(app: Context, m: Double): String =
        if (m < 1000) app.getString(R.string.unit_m, m.roundToInt().coerceAtLeast(1))
        else app.getString(R.string.unit_km, String.format(java.util.Locale.US, "%.1f", m / 1000))

    fun formatDuration(app: Context, s: Double): String {
        val min = (s / 60).roundToInt().coerceAtLeast(1)
        return if (min >= 60) app.getString(R.string.unit_hr_min, min / 60, min % 60)
        else app.getString(R.string.unit_min, min)
    }

    fun keyOf(s: Step): String = when (s.type) {
        "depart" -> "depart"
        "arrive" -> "arrive"
        "continue" -> "continue"
        "roundabout", "rotary", "roundabout turn" -> "roundabout"
        "exit roundabout", "exit rotary", "exit_roundabout" -> "exit_roundabout"
        else -> when (s.modifier) {
            "right" -> "right"
            "left" -> "left"
            "slight right" -> "slight_right"
            "slight left" -> "slight_left"
            "sharp right" -> "sharp_right"
            "sharp left" -> "sharp_left"
            "uturn" -> "uturn"
            else -> if (s.type in listOf("right", "left", "slight_right", "slight_left", "sharp_right", "sharp_left", "uturn")) s.type
            else "straight"
        }
    }
}
