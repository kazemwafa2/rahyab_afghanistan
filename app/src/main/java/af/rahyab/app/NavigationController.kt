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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    /** زیر این سرعت (تقریباً ۵٫۴ km/h) خودرو ایستاده است و لرزش GPS نباید «خروج از مسیر» بسازد */
    private const val MOVING_SPEED_MS = 1.5f

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
    /** پیشرفت جاری روی مسیر (متر از مبدأ) — برای هشدارهای مسیرمحور (بند ۱۵) */
    private var _lastProgressM = 0.0

    /** نقطه چسبیده‌به‌مسیر (برای تیرک جهت — همیشه دقیقاً روی مسیر) */
    val hasSnap: Boolean get() = _hasSnap
    val lastSnapLat: Double get() = _snapLat
    val lastSnapLon: Double get() = _snapLon
    val lastSnapBearing: Double get() = _snapBearing
    private var _hasSnap = false
    private var _snapLat = 0.0
    private var _snapLon = 0.0
    private var _snapBearing = 0.0

    /**
     * آخرین جهت حرکت معلوم (درجه ۰ تا ۳۶۰) — برای قطعه‌سازی گزارش‌ها و
     * نمونه‌های ترافیک (بخش ۸ و ۱۱). خروجی null = جهت معلوم نیست.
     */
    val lastHeadingDeg: Double?
        get() {
            lastFix?.let { if (it.hasBearing()) return it.bearing.toDouble() }
            return if (_hasSnap && navigating) _snapBearing else null
        }

    fun instrTitle(key: String, name: String, ctx: Context? = null): String {
        val app = try { ctx ?: locCtx() } catch (e: Exception) { appCtx } ?: return key
        val base = app.getString(
            when (key) {
                "depart" -> R.string.nav_depart
                "arrive" -> R.string.nav_arrive
                "arrive_farewell" -> R.string.nav_arrive_farewell
                "trip_end" -> R.string.nav_trip_end
                "roundabout" -> R.string.nav_roundabout
                "exit_roundabout" -> R.string.nav_exit_roundabout
                "exit_1" -> R.string.nav_exit_1
                "exit_2" -> R.string.nav_exit_2
                "exit_3" -> R.string.nav_exit_3
                "right" -> R.string.nav_right
                "left" -> R.string.nav_left
                "slight_right" -> R.string.nav_slight_right
                "slight_left" -> R.string.nav_slight_left
                "sharp_right" -> R.string.nav_sharp_right
                "sharp_left" -> R.string.nav_sharp_left
                "uturn" -> R.string.nav_uturn
                "go_right_side" -> R.string.nav_go_right_side
                "go_left_side" -> R.string.nav_go_left_side
                "stay_right" -> R.string.nav_stay_right
                "stay_left" -> R.string.nav_stay_left
                "fork_right" -> R.string.nav_fork_right
                "fork_left" -> R.string.nav_fork_left
                "enter_road" -> R.string.nav_enter_road
                "exit_road" -> R.string.nav_exit_road
                "enter_main" -> R.string.nav_enter_main
                "exit_main" -> R.string.nav_exit_main
                "enter_alt" -> R.string.nav_enter_alt
                "lane_change" -> R.string.nav_lane_change
                "keep_right" -> R.string.nav_keep_right
                "keep_left" -> R.string.nav_keep_left
                "keep_road" -> R.string.nav_keep_road
                "route_started" -> R.string.nav_route_started
                "route_changed" -> R.string.nav_route_changed
                "faster_found" -> R.string.nav_faster_found
                "gps_lost" -> R.string.nav_gps_lost
                "gps_restored" -> R.string.nav_gps_restored
                "approaching" -> R.string.nav_approaching
                "dest_right" -> R.string.nav_dest_right
                "dest_left" -> R.string.nav_dest_left
                "dest_500" -> R.string.nav_dest_500
                "dest_200" -> R.string.nav_dest_200
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
        approach500 = false
        approach200 = false
        offCount = 0
        rerouting = false
        arrivedAnnounced = false
        _lastKey = "depart"; _lastName = r.steps.firstOrNull()?.name ?: ""
        _lastRemainingM = r.distanceM
        _lastRemainingS = r.durationS
        _lastProgressM = 0.0
        routeAlertState = RouteAlerts.St()
        lastTrafficStatesRefreshMs = 0L

        // وضعیت اولیهٔ تیرک/دوربین: چسبیده به مبدأ مسیر با جهت نخستین قطعه — تا دوربین
        // بی‌درنگ روی مبدأ زوم کند و نشانگر وسیله رو به مسیر باشد، حتی پیش از رسیدن
        // نخستین قرائت GPS (درخواست کاربر)
        if (r.points.size >= 2) {
            _hasSnap = true
            _snapLat = r.points[0].second
            _snapLon = r.points[0].first
            _snapBearing = bearingBetween(r.points[0].second, r.points[0].first, r.points[1].second, r.points[1].first)
        }

        acquireWakeLock(ctx)
        startLocationUpdates(ctx)
        NavigationService.start(ctx)
        emit()
        speakKey("depart", r.steps.firstOrNull()?.name ?: "")
        // فوری پس از شروع، وضعیت را با آخرین موقعیت معلوم به‌روز کن
        lastFix?.let { onFix(it) }
        return true
    }

    /** توقف ناوبری؛ userInitiated = توقف دستی (پیام «سفر شما به پایان رسید…») */
    fun stop(userInitiated: Boolean = false) {
        val wasNavigating = navigating && !arrivedAnnounced
        navigating = false
        engine = null
        route = null
        destination = null
        _hasSnap = false
        stopLocationUpdates()
        releaseWakeLock()
        // موتور ترافیک: پایان نشست — تلاش نهایی ارسال نمونه‌ها + پاک‌سازی (بند ۱۱ و ۲۴)
        try { appCtx?.let { TrafficCollector.onSessionEnd(it) } } catch (e: Exception) { }
        routeAlertState = RouteAlerts.St()
        if (userInitiated && wasNavigating) {
            // «سفر شما به پایان رسید. از همراهی شما سپاسگزاریم.»
            val app = try { locCtx() } catch (e: Exception) { appCtx }
            voice?.speak(app?.getString(R.string.nav_trip_end) ?: "", listOf("trip_end"))
        }
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
        try { appCtx?.let { TrafficCollector.onSessionEnd(it) } } catch (e: Exception) { }
        routeAlertState = RouteAlerts.St()
        voice?.stop()
        emit()
    }

    // ---------- موقعیت ----------
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onFix(location) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            // «ارتباط GPS دوباره برقرار شد.» (واژه‌نامه بخش ۷)
            if (provider == LocationManager.GPS_PROVIDER && (navigating || freeMode)) {
                voice?.speak(locCtx().getString(R.string.nav_gps_restored), listOf("gps_restored"))
            }
        }
        override fun onProviderDisabled(provider: String) {
            // «ارتباط GPS قطع شده است.»
            if (provider == LocationManager.GPS_PROVIDER && (navigating || freeMode)) {
                voice?.speak(locCtx().getString(R.string.nav_gps_lost), listOf("gps_lost"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(ctx: Context) {
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.5f, locListener, Looper.getMainLooper())
            }
            // فراهم‌کنندهٔ شبکه نیز گوش‌دهی می‌شود — روی برخی گوشی‌ها تنها GPS_PROVIDER
            // دیر پاسخ می‌دهد یا در ساختمان‌ها هیچ قرائتی نمی‌آید؛ با شبکه نخستین
            // قرائت سریع‌تر می‌رسد و دوربین بی‌درنگ روی مبدأ می‌نشیند
            try {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 3f, locListener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) { }
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
        // موتور ترافیک: نمونه‌برداری سرعت (بند ۱۱) — فقط در حرکت واقعی و با GPS سالم
        try {
            appCtx?.let {
                TrafficCollector.onFix(it, loc, if (navigating) _lastName.ifEmpty { null } else null)
            }
        } catch (e: Exception) { }
        // هشدارهای مسیرمحور (بند ۱۵ و ۱۶) — فقط هنگام ناوبری
        maybeRouteAlerts(loc)
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
            _lastProgressM = st.progress

            // خروج از مسیر — دو محافظ ضد خطای کاذب:
            //  ۱) آستانهٔ پویا: با دقت GPS مقیاس می‌شود (دقت ۴۰m → آستانهٔ ۶۰m) تا لرزش
            //     موقعیت که در پارک‌شدن/مکان‌های سرپوشیده رایج است، هشدار کاذب نسازد.
            //  ۲) گیت حرکت: خودروی ایستاده هرگز «خارج از مسیر» شمرده نمی‌شود —
            //     برای بیرون‌رفتن از مسیر باید حرکت کرد؛ ایستاده فقط پرش GPS داریم.
            val threshold = offRouteThreshold(loc)
            if (st.offRouteM > threshold) {
                if (isMoving(loc)) {
                    offCount++
                    if (offCount >= OFF_ROUTE_TICKS && !rerouting) reroute(loc)
                }
                // بنر «از مسیر خارج شدید» فقط وقتی پایدار است (سه قرائت پیاپی یا در حال
                // محاسبهٔ مجدد) — نه با یک پرش لحظه‌ای GPS؛ وگرنه بنر چشمک می‌زد.
                if (offCount >= OFF_ROUTE_TICKS || rerouting) postState(st, "offroute", "")
                return@execute
            }
            offCount = 0

            // اعلام نزدیک شدن به مقصد — «تا مقصد ۵۰۰/۲۰۰ متر باقی مانده است.» + طرف مقصد
            announceApproach(st.remaining)

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

    /** آستانهٔ پویا برای تشخیص خروج از مسیر — حداقل ۴۵ متر و با دقت ضعیف GPS بزرگ‌تر */
    private fun offRouteThreshold(loc: Location): Double {
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
        return maxOf(OFF_ROUTE_M, acc * 1.5)
    }

    /** آیا خودرو واقعاً در حرکت است؟ بدون سنسور سرعت، محتاطانه «در حرکت» فرض می‌شود */
    private fun isMoving(loc: Location): Boolean =
        !loc.hasSpeed() || loc.speed >= MOVING_SPEED_MS

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
            // «اکنون به راست بچرخید.» — کلیپ «اکنون» + دستور (واژه‌نامه بخش ۲)
            voice?.speak(instr, listOf("d40", key))
        } else {
            // «پس از ۲۰۰ متر به راست بچرخید.» — کلیپ فاصله + دستور
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
            police = p.getBoolean("a_police", true),
            redlight = p.getBoolean("a_redlight", true),
            slippery = p.getBoolean("a_slippery", true),
            narrow = p.getBoolean("a_narrow", true),
            school = p.getBoolean("a_school", true),
            crosswalk = p.getBoolean("a_crosswalk", true),
            rail = p.getBoolean("a_rail", true),
            animal = p.getBoolean("a_animal", true),
            trafficlight = p.getBoolean("a_trafficlight", true),
            limit = p.getBoolean("a_limit_change", true),
            steepDown = p.getBoolean("a_steep_down", true),
            steepUp = p.getBoolean("a_steep_up", true),
            toll = p.getBoolean("a_toll", true),
            border = p.getBoolean("a_border", true),
            bridge = p.getBoolean("a_bridge", true),
            tunnel = p.getBoolean("a_tunnel", true),
            trafficLight = p.getBoolean("a_traffic_light", true),
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
                "alert_police" -> R.string.alert_police_text
                "alert_redlight" -> R.string.alert_redlight_text
                "alert_slippery" -> R.string.alert_slippery_text
                "alert_narrow" -> R.string.alert_narrow_text
                "alert_school" -> R.string.alert_school_text
                "alert_crosswalk" -> R.string.alert_crosswalk_text
                "alert_rail" -> R.string.alert_rail_text
                "alert_animal" -> R.string.alert_animal_text
                "alert_trafficlight" -> R.string.alert_trafficlight_text
                "alert_limit" -> R.string.alert_limit_text
                "alert_steep_down" -> R.string.alert_steep_down_text
                "alert_steep_up" -> R.string.alert_steep_up_text
                "alert_toll" -> R.string.alert_toll_text
                "alert_border" -> R.string.alert_border_text
                "alert_bridge" -> R.string.alert_bridge_text
                "alert_tunnel" -> R.string.alert_tunnel_text
                "slow_down" -> R.string.alert_slow_down_text
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
                val reports = try {
                    ReportsModule.active(ctx).map { AlertEngine.ReportPt(it.id, it.type, it.lat, it.lon) }
                } catch (e: Exception) { emptyList() }
                // مکان‌های ثابت ضمیمهٔ ۱ (کمره، دست‌انداز، عوارض، مرز، پل، تونل…) از کش محلی
                val poiPts = try {
                    RoadPois.alertPoints(ctx, loc.latitude, loc.longitude, 1200.0)
                } catch (e: Exception) { emptyList() }
                cachedReports = reports + poiPts
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

    // ---------- هشدارهای مسیرمحور (بند ۱۵ و ۱۶) ----------
    private var routeAlertState = RouteAlerts.St()
    private var lastRouteAlertMs = 0L
    private var lastTrafficStatesRefreshMs = 0L
    private val taIo = SafeExecutors.fixed(1)

    /**
     * هر ۱۰ ثانیه یک‌بار: وضعیت‌های ترافیک مسیر جلوِ رو + گزارش‌های قطعه‌دار
     * دیگران ارزیابی می‌شوند؛ هشدارهای تازه صوتی (دری) و متنی اعلام می‌شوند.
     * رانندهٔ بی‌ربط (جهت دیگر/مسیر دیگر) هرگز هشدار نمی‌گیرد (بند ۱۵).
     */
    private fun maybeRouteAlerts(loc: Location) {
        if (!navigating) return
        val now = System.currentTimeMillis()
        if (now - lastRouteAlertMs < 10_000L) return
        lastRouteAlertMs = now
        val ctx = appCtx ?: return
        val r = route ?: return
        val progress = _lastProgressM
        val bearing = if (_hasSnap) _snapBearing else
            (if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
        taIo.execute {
            // تازه‌سازی وضعیت‌های مسیر از سرور — هر ۵ دقیقه
            if (now - lastTrafficStatesRefreshMs > 5 * 60_000L) {
                lastTrafficStatesRefreshMs = now
                try { TrafficSync.refreshAlongRouteBlocking(ctx, r) } catch (e: Exception) { }
            }
            val congestions = try {
                TrafficStore.cachedStates(ctx).map {
                    RouteAlerts.Congestion(it.seg, it.state, it.roadType, it.roadName)
                }
            } catch (e: Exception) { emptyList<RouteAlerts.Congestion>() }
            // گزارش‌های قطعه‌دار دیگران (با شدت) — برای هشدار زودهنگام (بند ۱۵)
            val repAhead = try {
                ReportsModule.active(ctx).filter { it.fromServer && it.seg != null }.map {
                    RouteAlerts.ReportAhead(
                        "rp:${it.sid}:${it.type}", it.type, it.severity,
                        it.lat, it.lon, it.dirBin
                    )
                }
            } catch (e: Exception) { emptyList<RouteAlerts.ReportAhead>() }
            val (ns, events) = RouteAlerts.evaluate(r.points, progress, bearing, congestions, repAhead, routeAlertState)
            routeAlertState = ns
            if (events.isEmpty()) return@execute
            val app = try { locCtx() } catch (e: Exception) { appCtx } ?: return@execute
            for (ev in events) {
                val text = if (ev.state != null) {
                    val label = app.getString(trafficStateRes(ev.state))
                    val road = ev.roadName?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                    app.getString(R.string.traffic_alert_ahead, formatDistance(app, ev.aheadM)) + "، " + label + road
                } else {
                    alertText(ev.clip)
                }
                voice?.speak(text, listOf(ev.clip))
                ui.post { alertListeners.forEach { l -> try { l(ev.clip) } catch (e: Exception) { } } }
            }
        }
    }

    /** منبع رشتهٔ برچسب وضعیت ترافیک */
    fun trafficStateRes(state: String): Int = when (state) {
        TrafficRules.ST_SUSPECTED -> R.string.traffic_state_suspected
        TrafficRules.ST_MODERATE -> R.string.traffic_state_moderate
        TrafficRules.ST_SEVERE -> R.string.traffic_state_severe
        TrafficRules.ST_EXTREME -> R.string.traffic_state_extreme
        TrafficRules.ST_CLOSED -> R.string.traffic_state_closed
        TrafficRules.ST_INSUFFICIENT -> R.string.traffic_state_insufficient
        TrafficRules.ST_EXPIRED -> R.string.traffic_state_expired
        else -> R.string.traffic_state_normal
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
        approach500 = false
        approach200 = false
        _lastRemainingM = r.distanceM
        _lastRemainingS = r.durationS
    }

    // ---------- نزدیک شدن به مقصد و پایان سفر (واژه‌نامه بخش ۸ و ۹) ----------
    private var approach500 = false
    private var approach200 = false

    /** طرف مقصد نسبت به انتهای مسیر: «dest_right» یا «dest_left» */
    private fun destSideClip(): String {
        val r = route ?: return ""
        val d = destination ?: return ""
        if (r.points.size < 2) return ""
        val a = r.points[r.points.size - 2]
        val b = r.points[r.points.size - 1]
        // بردار مسیر در انتها (v) و بردار از انتهای مسیر به مقصد (w)
        val vx = b.first - a.first; val vy = b.second - a.second
        val wx = d.lon - b.first; val wy = d.lat - b.second
        val cross = vx * wy - vy * wx
        return if (cross > 0) "dest_left" else "dest_right"
    }

    /** اعلام «تا مقصد … باقی مانده است» در ۵۰۰ و ۲۰۰ متر + طرف مقصد در ۲۰۰ متر */
    private fun announceApproach(remaining: Double) {
        val app = try { locCtx() } catch (e: Exception) { appCtx } ?: return
        if (remaining <= 500.0 && !approach500) {
            approach500 = true
            voice?.speak(app.getString(R.string.nav_dest_500), listOf("dest_500"))
        }
        if (remaining <= 200.0 && !approach200) {
            approach200 = true
            val side = destSideClip()
            val text = if (side.isEmpty()) app.getString(R.string.nav_dest_200)
            else app.getString(R.string.nav_dest_200) + " " + instrTitle(side, "", app)
            voice?.speak(text, if (side.isEmpty()) listOf("dest_200") else listOf("dest_200", side))
        }
    }

    private fun arrive() {
        if (!arrivedAnnounced) {
            arrivedAnnounced = true
            // «به مقصد رسیدید. سفر خوشی داشته باشید.» (واژه‌نامه بخش ۹)
            val app = try { locCtx() } catch (e: Exception) { appCtx }
            val text = app?.getString(R.string.nav_arrive_farewell) ?: ""
            voice?.speak(text, listOf("arrive_farewell"))
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

    /**
     * محور (زاویهٔ قطب‌نما) از نقطهٔ اول به نقطهٔ دوم بر حسب درجه (۰ = شمال).
     * برای جهت‌دادن نشانگر وسیله و دوربین به سمت مسیر و مقصد.
     */
    fun bearingBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
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
