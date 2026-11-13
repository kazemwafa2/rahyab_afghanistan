package af.rahyab.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.speech.RecognizerIntent
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * صفحه اصلی به سبک برنامه‌های حرفه‌ای نقشه:
 *  - نوار جست‌وجو + دسته‌بندی مکان‌ها (مساجد، شفاخانه‌ها، دواخانه‌ها و…)
 *  - نوار پایین: نقشه، ذخیره‌شده‌ها، بسته‌های آفلاین، تنظیمات
 *  - کارت مسیر با انتخاب موتر/پیاده و دکمه شروع
 *  - ناوبری: بنر پیچ بعدی + نوار پایین با سرعت‌سنج، زمان باقی‌مانده، ساعت رسیدن و توقف
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var locationEnabled = false
    private var offlineStyleActive = false
    /** اینترنت ظاهراً وصل است (مثلاً وای‌فای/VPN) ولی سرور نقشه واقعاً جواب نمی‌دهد */
    @Volatile private var netReallyDown = false
    @Volatile private var lastProbeMs = 0L
    @Volatile private var probing = false

    private lateinit var store: PlacesStore
    private lateinit var prefs: SharedPreferences

    private var selected: Place? = null
    private var results: List<Place> = emptyList()
    private var navProfile = Profile.CAR
    private var drawnRoute: RouteResult? = null

    // مسیرهای جایگزین به سبک نشان: [سریع‌ترین، جایگزین ۱، جایگزین ۲]
    private var altRoutes: List<RouteResult> = emptyList()
    private var selectedRouteIdx = 0
    private var routeSourceOffline = true
    private var deepLinkReturn = false

    private val io = SafeExecutors.fixed(2)
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var etSearch: EditText
    private lateinit var resultsCard: View
    private lateinit var lvResults: ListView
    private lateinit var homeSheet: View
    private lateinit var chipsScroll: View
    private lateinit var chipsRow: ViewGroup
    private lateinit var recentsList: ViewGroup
    private lateinit var tvRecentsTitle: TextView
    private lateinit var bubbleLayer: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fabColumn: View
    private lateinit var fabRoute: View
    private lateinit var routeHeader: View
    private lateinit var tvOrigin: TextView
    private lateinit var tvDestName: TextView
    private lateinit var btnRouteSettings: View
    private lateinit var navBanner: View
    private lateinit var ivNavArrow: ImageView
    private lateinit var tvNavDist: TextView
    private lateinit var tvNavText: TextView
    private lateinit var tvNavInstr: TextView
    private lateinit var nextCard: View
    private lateinit var ivNextArrow: ImageView
    private lateinit var leftCol: View
    private lateinit var btnCompass: TextView
    private lateinit var navBar: View
    private lateinit var tvSpeed: TextView
    private lateinit var tvRemainTime: TextView
    private lateinit var tvEta: TextView
    private lateinit var btnMute: ImageView
    private lateinit var panel: View
    private lateinit var tvTitle: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvSafety: TextView
    private lateinit var tvSource: TextView
    private lateinit var routeSheet: View
    private lateinit var tabCar: View
    private lateinit var tabFoot: View
    private lateinit var ivTabCar: ImageView
    private lateinit var ivTabFoot: ImageView
    private lateinit var tvTabCar: TextView
    private lateinit var tvTabFoot: TextView
    private lateinit var tvVia: TextView
    private lateinit var tvRouteTime: TextView
    private lateinit var tvRouteDist: TextView
    private lateinit var ivRouteMode: ImageView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStopNav: View

    // رانندگی آزاد (مثل «حالت رانندگی آزاد» نشان)
    private lateinit var freeCard: View
    private lateinit var tvFreeSpeed: TextView
    private lateinit var tvFreeAlert: TextView
    private lateinit var btnFreeEnd: View

    // حالت‌های صفحه (سبک نشان/بلد): خانه → مکان → پیش‌نمایش مسیر → ناوبری
    private enum class Mode { HOME, PLACE, ROUTE, NAV }
    private var mode = Mode.HOME

    // تغییر مبدأ/مقصد از سرصفحه مسیر
    private enum class Pick { NONE, ORIGIN, DEST }
    private var pick = Pick.NONE
    private var originPlace: Place? = null   // null = موقعیت فعلی
    private var northUp = false

    // زبانه‌های موتورسکل و بایسکل (بخش هفتم چک‌لیست)
    private lateinit var tabMoto: View
    private lateinit var tabBike: View
    private lateinit var ivTabMoto: ImageView
    private lateinit var ivTabBike: ImageView
    private lateinit var tvTabMoto: TextView
    private lateinit var tvTabBike: TextView

    // ترافیک، رادیو، گزارش حوادث، هوا
    private lateinit var fabTraffic: View
    private lateinit var fabRadio: View
    private lateinit var fabReport: View
    private lateinit var weatherCard: View
    private lateinit var tvWeather: TextView
    private var trafficOn = false
    private var trafficScheduled = false
    private var voiceDialogShown = false
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // حباب‌های زمان مسیرها روی نقشه
    private class Bubble(val view: TextView, val anchor: LatLng)
    private val bubbles = ArrayList<Bubble>()

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val t = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) { etSearch.setText(t); doSearch() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableLocation() else toast(R.string.location_denied)
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // گوش‌دهی به کنترلر ناوبری
    private val uiListener = { st: NavigationController.UiState -> onNavState(st) }
    private val fixListener = { loc: Location -> onFixDuringNav(loc) }
    private val alertListener = { clip: String ->
        tvFreeAlert.text = NavigationController.alertText(clip)
    }

    // دسته‌بندی‌های جست‌وجوی سریع (نماد + نام + شناسه دسته در اندیس آفلاین)
    private val categories = listOf(
        Triple("🕌", R.string.cat_religion, OfflineGeocoder.C_RELIGION),
        Triple("🏥", R.string.cat_health, OfflineGeocoder.C_HEALTH),
        Triple("💊", R.string.cat_pharmacy, OfflineGeocoder.C_PHARMACY),
        Triple("🏫", R.string.cat_education, OfflineGeocoder.C_EDUCATION),
        Triple("⛽", R.string.cat_fuel, OfflineGeocoder.C_FUEL),
        Triple("🏦", R.string.cat_bank, OfflineGeocoder.C_BANK),
        Triple("🛒", R.string.cat_market, OfflineGeocoder.C_MARKET),
        Triple("🍽️", R.string.cat_food, OfflineGeocoder.C_FOOD),
        Triple("🏨", R.string.cat_hotel, OfflineGeocoder.C_HOTEL),
        Triple("🌳", R.string.cat_park, OfflineGeocoder.C_PARK),
        Triple("🚓", R.string.cat_police, OfflineGeocoder.C_SERVICES),
        Triple("🚌", R.string.cat_transport, OfflineGeocoder.C_TRANSPORT),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post { CrashReporter.showIfAny(this) }
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        store = PlacesStore(this)

        // خوش‌آمد فقط در نخستین اجرا
        if (!prefs.getBoolean("welcomed", false)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        etSearch = findViewById(R.id.etSearch)
        resultsCard = findViewById(R.id.resultsCard)
        lvResults = findViewById(R.id.lvResults)
        homeSheet = findViewById(R.id.homeSheet)
        chipsScroll = findViewById(R.id.chipsScroll)
        chipsRow = findViewById(R.id.chipsRow)
        recentsList = findViewById(R.id.recentsList)
        tvRecentsTitle = findViewById(R.id.tvRecentsTitle)
        bubbleLayer = findViewById(R.id.bubbleLayer)
        bottomNav = findViewById(R.id.bottomNav)
        fabColumn = findViewById(R.id.fabColumn)
        fabRoute = findViewById(R.id.fabRoute)
        routeHeader = findViewById(R.id.routeHeader)
        tvOrigin = findViewById(R.id.tvOrigin)
        tvDestName = findViewById(R.id.tvDestName)
        btnRouteSettings = findViewById(R.id.btnRouteSettings)
        navBanner = findViewById(R.id.navBanner)
        ivNavArrow = findViewById(R.id.ivNavArrow)
        tvNavDist = findViewById(R.id.tvNavDist)
        tvNavText = findViewById(R.id.tvNavText)
        tvNavInstr = findViewById(R.id.tvNavInstr)
        nextCard = findViewById(R.id.nextCard)
        ivNextArrow = findViewById(R.id.ivNextArrow)
        leftCol = findViewById(R.id.leftCol)
        btnCompass = findViewById(R.id.btnCompass)
        navBar = findViewById(R.id.navBar)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvRemainTime = findViewById(R.id.tvRemainTime)
        tvEta = findViewById(R.id.tvEta)
        btnMute = findViewById(R.id.btnMute)
        tabMoto = findViewById(R.id.tabMoto)
        tabBike = findViewById(R.id.tabBike)
        ivTabMoto = findViewById(R.id.ivTabMoto)
        ivTabBike = findViewById(R.id.ivTabBike)
        tvTabMoto = findViewById(R.id.tvTabMoto)
        tvTabBike = findViewById(R.id.tvTabBike)
        fabTraffic = findViewById(R.id.fabTraffic)
        fabRadio = findViewById(R.id.fabRadio)
        fabReport = findViewById(R.id.fabReport)
        weatherCard = findViewById(R.id.weatherCard)
        tvWeather = findViewById(R.id.tvWeather)
        panel = findViewById(R.id.panel)
        tvTitle = findViewById(R.id.tvTitle)
        tvInfo = findViewById(R.id.tvInfo)
        tvSafety = findViewById(R.id.tvSafety)
        tvSource = findViewById(R.id.tvSource)
        routeSheet = findViewById(R.id.routeSheet)
        tabCar = findViewById(R.id.tabCar)
        tabFoot = findViewById(R.id.tabFoot)
        ivTabCar = findViewById(R.id.ivTabCar)
        ivTabFoot = findViewById(R.id.ivTabFoot)
        tvTabCar = findViewById(R.id.tvTabCar)
        tvTabFoot = findViewById(R.id.tvTabFoot)
        tvVia = findViewById(R.id.tvVia)
        tvRouteTime = findViewById(R.id.tvRouteTime)
        tvRouteDist = findViewById(R.id.tvRouteDist)
        ivRouteMode = findViewById(R.id.ivRouteMode)
        btnStart = findViewById(R.id.btnStart)
        btnStopNav = findViewById(R.id.btnStopNav)
        freeCard = findViewById(R.id.freeCard)
        tvFreeSpeed = findViewById(R.id.tvFreeSpeed)
        tvFreeAlert = findViewById(R.id.tvFreeAlert)
        btnFreeEnd = findViewById(R.id.btnFreeEnd)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(Config.START_LAT, Config.START_LON))
                .zoom(Config.START_ZOOM).build()
            m.addOnCameraMoveListener { positionBubbles() }
            m.addOnCameraIdleListener {
                positionBubbles()
                // آفلاین: اگر دوربین به ولایت دیگری رفت، بسته نقشهٔ همان ولایت (یا بسته پایه) بار شود
                if (offlineStyleActive) {
                    try { pickOfflinePackage()?.let { applyOfflineStyle(it) } } catch (_: Exception) { }
                    // اگر اینترنت دوباره واقعاً برگشت (VPN درست شد)، به سبک آنلاین برگرد
                    if (netReallyDown && System.currentTimeMillis() - lastProbeMs > 30_000L) {
                        lastProbeMs = System.currentTimeMillis()
                        io.execute {
                            val ok = try {
                                val c = java.net.URL(Config.STYLE_LIGHT).openConnection() as java.net.HttpURLConnection
                                c.connectTimeout = 3000; c.readTimeout = 3000
                                try { c.responseCode; true } finally { c.disconnect() }
                            } catch (e: Exception) { false }
                            if (ok) ui.post { netReallyDown = false; loadStyle() }
                        }
                    }
                } else if (System.currentTimeMillis() - lastProbeMs > 25_000L) {
                    probeOnlineReachability()
                }
                // ذخیره مرکز نقشه برای کارت هوا
                m.cameraPosition.target?.let { t ->
                    prefs.edit().putFloat("map_lat", t.latitude.toFloat())
                        .putFloat("map_lon", t.longitude.toFloat()).apply()
                }
            }
            // تغییر مبدأ/مقصد دستی روی نقشه — لمس طولانی = گفت‌وگوی انتخاب
            m.addOnMapLongClickListener { pt ->
                if (!NavigationController.navigating) onMapPointChosen(pt.latitude, pt.longitude, true)
                true
            }
            m.addOnMapClickListener { pt ->
                if (NavigationController.navigating || mode == Mode.NAV) return@addOnMapClickListener false
                onMapPointChosen(pt.latitude, pt.longitude, false)
                true
            }
            hideMapAttribution()
            // اگر بارگیری سبک آنلاین شکست خورد (انترنت ناپایدار) → خودکار سبک آفلاین
            mapView.addOnDidFailLoadingMapListener { errMsg ->
                try {
                    if (offlineStyleActive) handleOfflineStyleFailure(errMsg) else handleOnlineStyleFailure()
                } catch (e: Exception) { }
            }
            loadStyle()
        }
        mapView.post { hideMapAttribution() }

        buildCategoryChips()
        refreshRecents()
        updateCompassLabel()

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        lvResults.setOnItemClickListener { _, _, pos, _ -> select(results[pos]) }
        findViewById<View>(R.id.ivMic).setOnClickListener { startVoiceSearch() }
        findViewById<View>(R.id.btnProfile).setOnClickListener { showSettings() }
        findViewById<View>(R.id.fabLocation).setOnClickListener { onMyLocation() }
        fabRoute.setOnClickListener { onPlanRoute() }
        findViewById<View>(R.id.fabLayers).setOnClickListener { showMapModeDialog() }
        btnRouteSettings.setOnClickListener { showMapModeDialog() }
        findViewById<View>(R.id.btnRoute).setOnClickListener { requestRoute(navProfile) }
        findViewById<View>(R.id.btnClear).setOnClickListener { clearAll() }
        findViewById<View>(R.id.btnSave).setOnClickListener { showSaveDialog() }
        findViewById<View>(R.id.btnShare).setOnClickListener { shareSelected() }
        tabCar.setOnClickListener { setProfile(Profile.CAR) }
        tabFoot.setOnClickListener { setProfile(Profile.FOOT) }
        findViewById<View>(R.id.btnPreview).setOnClickListener { showStepsDialog() }
        findViewById<View>(R.id.btnChangeOrigin).setOnClickListener { startPick(Pick.ORIGIN) }
        findViewById<View>(R.id.btnChangeDest).setOnClickListener { startPick(Pick.DEST) }
        findViewById<View>(R.id.ivSwap).setOnClickListener { swapEndpoints() }
        btnCompass.setOnClickListener { northUp = !northUp; updateCompassLabel() }
        btnStart.setOnClickListener { startNav() }
        btnStopNav.setOnClickListener { confirmStopNav() }
        btnFreeEnd.setOnClickListener { endFreeDriving() }
        btnMute.setOnClickListener { toggleVoice() }
        tabMoto.setOnClickListener { setProfile(Profile.MOTO) }
        tabBike.setOnClickListener { setProfile(Profile.BIKE) }
        fabTraffic.setOnClickListener { toggleTraffic() }
        fabTraffic.setOnLongClickListener { showTrafficSettingsDialog(); true }
        fabRadio.setOnClickListener { startActivity(Intent(this, RadioActivity::class.java)) }
        fabReport.setOnClickListener { showReportDialog() }
        weatherCard.setOnClickListener { showWeatherDialog() }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_map -> { hideKeyboard(); resultsCard.visibility = View.GONE; true }
                R.id.tab_free -> { startFreeDriving(); bottomNav.post { bottomNav.selectedItemId = R.id.tab_map }; true }
                R.id.tab_saved -> { showPlaces(); bottomNav.post { bottomNav.selectedItemId = R.id.tab_map }; true }
                R.id.tab_packs -> { startActivity(Intent(this, MapPackagesActivity::class.java)); bottomNav.post { bottomNav.selectedItemId = R.id.tab_map }; true }
                R.id.tab_settings -> { showSettings(); bottomNav.post { bottomNav.selectedItemId = R.id.tab_map }; true }
                else -> false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { onBack() }
        })

        styleTabs()
        setMode(Mode.HOME)
        handleDeepLink(intent)
    }

    /** دکمه برگشت: ناوبری → تأیید توقف؛ مسیر → مکان؛ مکان → خانه؛ در غیر این صورت خروج */
    private fun onBack() {
        when {
            NavigationController.freeMode -> endFreeDriving()
            NavigationController.navigating -> confirmStopNav()
            pick != Pick.NONE -> cancelPick()
            resultsCard.visibility == View.VISIBLE -> resultsCard.visibility = View.GONE
            mode == Mode.ROUTE -> { clearRoute(); selected?.let { showPlaceSheet(it) } ?: setMode(Mode.HOME) }
            mode == Mode.PLACE -> clearAll()
            else -> finish()
        }
    }

    // ---------- Deep Link برای اپلیکیشن‌های تاکسی/حمل‌ونقل (docs/INTEGRATION.md) ----------
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * پذیرش درخواست از برنامه‌های دیگر — بخش ششم چک‌لیست:
     *  - rahyab://navigate?... (تاکسی و برنامه‌های همکار، مستند در docs/INTEGRATION.md)
     *  - geo:lat,lon (استاندارد اندروید؛ از مرورگر و هر برنامه‌ای)
     *  - لینک‌های Google Maps و OpenStreetMap (مستقیم یا کوتاه goo.gl)
     *  - اشتراک متن از واتساپ و سایر برنامه‌ها: مختصات، لینک نقشه یا نام مکان
     * اعتبارسنجی: مختصات باید معتبر باشد؛ درخواست نامعتبر رد می‌شود.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            handleExternalPlace(Integration.fromText(text), fromShare = true)
            return
        }
        val uri = intent.data ?: return
        if (uri.scheme == "rahyab" && uri.host == "navigate") {
            fun param(k: String): String? = uri.getQueryParameter(k)?.trim()?.takeIf { it.isNotEmpty() }
            fun coord(k: String): Double? = param(k)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val dLat = coord("destination_lat") ?: return rejectDeepLink("destination_lat")
            val dLon = coord("destination_lon") ?: return rejectDeepLink("destination_lon")
            if (dLat !in -90.0..90.0 || dLon !in -180.0..180.0) return rejectDeepLink("bounds")
            val oLat = coord("origin_lat")
            val oLon = coord("origin_lon")
            if ((oLat == null) != (oLon == null)) return rejectDeepLink("origin_pair")
            if (oLat != null && oLon != null && (oLat !in -90.0..90.0 || oLon !in -180.0..180.0)) {
                return rejectDeepLink("bounds")
            }
            val mode = param("travel_mode")
            val profile = if (mode.equals("walking", ignoreCase = true)) Profile.FOOT else Profile.CAR
            val name = param("name") ?: getString(R.string.deep_link_dest_default)
            deepLinkReturn = intent.getBooleanExtra("EXTRA_RETURN_RESULT", false)

            val dest = Place(name, dLat, dLon)
            selected = dest
            originPlace = null
            setDestMarker(dest)
            showPlaceSheet(dest)
            navProfile = profile
            styleTabs()
            if (oLat != null && oLon != null) {
                // مبدأ صریح از اپ فراخوان (مثلاً موقعیت راننده/مسافر)
                requestRouteFrom(profile, oLat, oLon)
            } else {
                val loc = lastKnown()
                if (loc != null) requestRouteFrom(profile, loc.latitude, loc.longitude)
                else {
                    toast(R.string.need_location_for_route)
                    if (!hasLocationPermission()) permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            return
        }
        // geo: و لینک‌های نقشه‌های دیگر (واتساپ، پیام‌رسان‌ها، مرورگر)
        val p = try { Integration.fromUri(uri) } catch (e: Exception) { null } ?: return
        if (p.needResolveUrl != null) {
            toast(R.string.resolving_link)
            io.execute {
                val rp = Integration.resolveShortLink(p.needResolveUrl!!)
                ui.post {
                    if (rp != null) handleExternalPlace(rp, fromShare = false)
                    else toast(R.string.deep_link_invalid)
                }
            }
            return
        }
        handleExternalPlace(p, fromShare = false)
    }

    private fun rejectDeepLink(why: String) {
        android.util.Log.w("MainActivity", "درخواست Deep Link رد شد: $why")
        toast(R.string.deep_link_invalid)
    }

    /** مکان دریافتی از برنامه دیگر یا لینک: مقصد یا جست‌وجو */
    private fun handleExternalPlace(p: Integration.Parsed, fromShare: Boolean) {
        when {
            p.lat != null && p.lon != null -> {
                val name = p.name ?: getString(if (fromShare) R.string.shared_location else R.string.link_location)
                select(Place(name, p.lat, p.lon))
            }
            p.searchQuery != null -> {
                etSearch.setText(p.searchQuery)
                doSearch()
            }
            else -> if (fromShare) toast(R.string.deep_link_invalid)
        }
    }

    /**
     * ترتیب مسیرهای جایگزین با پرهیز از راه‌های پرزحمت (تنظیم مسیریابی).
     * مسیرهایی که به گزارش‌های «زحمت» کاربران نزدیک‌ترند به عقب می‌روند —
     * تنها داده واقعی گزارش‌ها لحاظ می‌شود (قانون ۵).
     */
    private fun reorderLowTraffic(list: List<RouteResult>): List<RouteResult> {
        if (list.size < 2) return list
        val jams = try {
            ReportsModule.active(this).filter { it.type == ReportsModule.TYPE_HEAVY_TRAFFIC }
        } catch (e: Exception) { emptyList() }
        if (jams.isEmpty()) return list
        fun penalty(r: RouteResult): Int {
            var p = 0
            val pts = r.points
            val step = if (pts.size > 120) pts.size / 100 else 1
            var i = 0
            while (i < pts.size) {
                val (lo, la) = pts[i]
                for (j in jams) {
                    val d = haversineKm(la, lo, j.lat, j.lon)
                    if (d < 0.7) p += 1
                }
                i += step
            }
            return p
        }
        return list.sortedBy { penalty(it) }
    }

    /** درخواست مسیر با مبدأ مشخص (برای Deep Link، ناوبری عادی و مبدأ دلخواه) */
    private fun requestRouteFrom(profile: Profile, fromLat: Double, fromLon: Double) {
        val dest = selected ?: return
        navProfile = profile
        styleTabs()
        toast(R.string.calculating_route)
        io.execute {
            try {
                val pair = Services.routeAlternatives(profile, fromLat, fromLon, dest.lat, dest.lon)
                ui.post {
                    val list = pair.first
                    if (list.isEmpty()) {
                        toast(R.string.route_failed)
                        if (deepLinkReturn) setResult(RESULT_FIRST_USER)
                    } else {
                        routeSourceOffline = pair.second == Services.Source.OFFLINE
                        if (routeSourceOffline) showSource(getString(R.string.route_offline_active))
                        else showSource(getString(R.string.source_online))
                        val ordered = if (prefs.getBoolean("r_avoid_traffic", false)) reorderLowTraffic(list) else list
                        altRoutes = ordered
                        selectedRouteIdx = 0
                        setMode(Mode.ROUTE)
                        drawAllRoutes()
                        rebuildBubbles()
                        fitRoutes()
                        updateRouteInfo()
                        if (deepLinkReturn) {
                            val best = list[0]
                            setResult(RESULT_OK, Intent().apply {
                                putExtra("distance_m", best.distanceM)
                                putExtra("duration_s", best.durationS)
                                putExtra("routes_count", list.size)
                            })
                        }
                    }
                }
            } catch (e: ServiceException) {
                ui.post {
                    when (e) {
                        is RateLimitedException -> toast(R.string.service_busy)
                        is ForbiddenException -> toast(R.string.service_forbidden)
                        else -> toast(R.string.error_network)
                    }
                }
            }
        }
    }

    // ---------- دسته‌بندی مکان‌ها ----------
    private fun addChip(text: String, onClick: () -> Unit) {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setTextColor(Color.parseColor("#1B2735"))
        t.setBackgroundResource(R.drawable.bg_chip)
        t.setPadding(dp(16), dp(10), dp(16), dp(10))
        t.gravity = android.view.Gravity.CENTER_VERTICAL
        t.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        t.setOnClickListener { onClick() }
        chipsRow.addView(t)
    }

    /** ردیف چیپ‌ها: خانه، محل کار، سپس دسته‌های مکان‌ها (سبک نشان) */
    private fun buildCategoryChips() {
        chipsRow.removeAllViews()
        val home = store.home
        val work = store.work
        addChip("🏠 " + (if (home == null) "+ " else "") + getString(R.string.home)) {
            val h = store.home
            if (h != null) select(h) else Toast.makeText(this, R.string.set_home_hint, Toast.LENGTH_LONG).show()
        }
        addChip("💼 " + (if (work == null) "+ " else "") + getString(R.string.work)) {
            val w = store.work
            if (w != null) select(w) else Toast.makeText(this, R.string.set_work_hint, Toast.LENGTH_LONG).show()
        }
        for ((emoji, labelRes, cat) in categories) {
            addChip("$emoji ${getString(labelRes)}") { doCategorySearch(cat) }
        }
    }

    /** سه مکان اخیر در برگه خانه */
    private fun refreshRecents() {
        recentsList.removeAllViews()
        val list = store.recents().take(3)
        tvRecentsTitle.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        for (p in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(10), 0, dp(10))
            val icon = ImageView(this)
            icon.setImageResource(R.drawable.ic_history)
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(Color.parseColor("#7D8CA3")))
            icon.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            val name = TextView(this)
            name.text = p.name
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.textSize = 14f
            name.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            name.setTextColor(Color.parseColor("#1B2735"))
            name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
            row.addView(icon)
            row.addView(name)
            row.setOnClickListener { select(p) }
            recentsList.addView(row)
        }
    }

    private fun startVoiceSearch() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, resources.configuration.locales[0].toLanguageTag())
        try { speechLauncher.launch(i) } catch (e: Exception) { toast(R.string.voice_search_unavailable) }
    }

    private fun doCategorySearch(cat: Int) {
        if (NavigationController.navigating) return
        val loc = lastKnown()
        if (OfflineData.installed.none { it.hasIndex }) {
            toast(R.string.cat_need_offline)
            startActivity(Intent(this, MapPackagesActivity::class.java))
            return
        }
        toast(R.string.searching)
        io.execute {
            val res = Services.searchCategory(cat, loc?.latitude, loc?.longitude)
            ui.post {
                showSource(getString(R.string.source_offline))
                if (res.isEmpty()) {
                    toast(R.string.cat_empty)
                    resultsCard.visibility = View.GONE
                } else {
                    showResults(res)
                }
            }
        }
    }

    // ---------- جست‌وجو (آفلاین/آنلاین با کش) ----------
    private fun doSearch() {
        val q = etSearch.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        toast(R.string.searching)
        val lang = resources.configuration.locales[0].language
        val loc = lastKnown()
        io.execute {
            try {
                val res = Services.search(q, lang, loc?.latitude, loc?.longitude)
                ui.post {
                    when (res.source) {
                        Services.Source.OFFLINE -> showSource(getString(R.string.source_offline))
                        Services.Source.ONLINE -> showSource(getString(R.string.source_online))
                    }
                    showResults(res.places)
                }
            } catch (e: ServiceException) {
                // خطای سرویس آنلاین: جست‌وجوی بین مکان‌های ذخیره‌شده روی گوشی
                val local = store.searchLocal(q)
                ui.post {
                    showSource("")
                    when (e) {
                        is RateLimitedException -> toast(R.string.service_busy)
                        is ForbiddenException -> toast(R.string.service_forbidden)
                        else -> toast(R.string.error_network)
                    }
                    if (local.isNotEmpty()) { toast(R.string.offline_search_notice); showResults(local) }
                    else showResults(emptyList())
                }
            } catch (e: Exception) {
                // هیچ استثنایی نباید نخ اجرا را بیندازد (کرش هنگام قطع انترنت)
                val local = try { store.searchLocal(q) } catch (ex: Exception) { emptyList<Place>() }
                ui.post {
                    showSource("")
                    toast(R.string.error_network)
                    if (local.isNotEmpty()) { toast(R.string.offline_search_notice); showResults(local) }
                    else showResults(emptyList())
                }
            }
        }
    }

    private fun showSource(text: String) {
        tvSource.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        tvSource.text = text
    }

    private fun showResults(list: List<Place>) {
        results = list
        if (list.isEmpty()) {
            resultsCard.visibility = View.GONE
            toast(R.string.no_results)
        } else {
            lvResults.adapter = PlaceAdapter(list)
            lvResults.layoutParams.height =
                if (list.size > 5) dp(5 * 62) else ViewGroup.LayoutParams.WRAP_CONTENT
            lvResults.requestLayout()
            resultsCard.visibility = View.VISIBLE
        }
    }

    /** فهرست دو خطی نتایج: نام + فاصله تا کاربر (در صورت معلوم بودن موقعیت) */
    private inner class PlaceAdapter(private val list: List<Place>) :
        ArrayAdapter<Place>(this@MainActivity, R.layout.item_place, list) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_place, parent, false)
            val p = list[position]
            v.findViewById<TextView>(R.id.tvPlaceName).text = p.name
            val sub = v.findViewById<TextView>(R.id.tvPlaceSub)
            val loc = lastKnown()
            if (loc != null) {
                val km = haversineKm(loc.latitude, loc.longitude, p.lat, p.lon)
                sub.visibility = View.VISIBLE
                sub.text = getString(R.string.distance_from_here) + ": " +
                    NavigationController.formatDistance(this@MainActivity, km * 1000.0)
            } else {
                sub.visibility = View.GONE
            }
            return v
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun setDestMarker(p: Place?) {
        val src = style?.getSourceAs<GeoJsonSource>("dest-src") ?: return
        if (p == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
    }

    private fun select(p: Place) {
        if (NavigationController.navigating) return
        resultsCard.visibility = View.GONE
        hideKeyboard()
        etSearch.setText("")
        when (pick) {
            Pick.ORIGIN, Pick.DEST -> {
                assignPickedPoint(p)
                return
            }
            Pick.NONE -> {}
        }
        selected = p
        originPlace = null
        store.addRecent(p)
        refreshRecents()
        clearRoute()
        setDestMarker(p)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0))
        showPlaceSheet(p)
    }

    /** جای‌گذاری مبدأ/مقصد انتخاب‌شده (از جست‌وجو یا نقشه) — رفع مشکل تغییر دستی */
    private fun assignPickedPoint(p: Place) {
        when (pick) {
            Pick.ORIGIN -> {
                pick = Pick.NONE
                etSearch.setHint(R.string.where_to)
                originPlace = p
                toast(R.string.origin_set)
                if (selected != null) requestRoute(navProfile) else showPlaceSheet(p)
            }
            Pick.DEST -> {
                pick = Pick.NONE
                etSearch.setHint(R.string.where_to)
                selected = p
                originPlace = null
                store.addRecent(p)
                setDestMarker(p)
                refreshRecents()
                requestRoute(navProfile)
            }
            Pick.NONE -> {}
        }
    }

    /** برگه مکان انتخاب‌شده: نام، فاصله تا کاربر، دکمه مسیر */
    private fun showPlaceSheet(p: Place) {
        tvTitle.text = p.name
        val loc = lastKnown()
        tvInfo.text = if (loc != null) {
            getString(R.string.distance_from_here) + ": " +
                NavigationController.formatDistance(this, haversineKm(loc.latitude, loc.longitude, p.lat, p.lon) * 1000.0)
        } else ""
        setMode(Mode.PLACE)
    }

    /** تغییر مبدأ یا مقصد: بازگشت به نوار جست‌وجو؛ نتیجه انتخاب‌شده جایگزین می‌شود */
    private fun startPick(kind: Pick) {
        pick = kind
        setMode(Mode.HOME)
        etSearch.setHint(if (kind == Pick.ORIGIN) R.string.pick_origin_hint else R.string.pick_dest_hint)
        etSearch.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelPick() {
        pick = Pick.NONE
        etSearch.setHint(R.string.where_to)
        resultsCard.visibility = View.GONE
        hideKeyboard()
        if (altRoutes.isNotEmpty()) setMode(Mode.ROUTE) else selected?.let { showPlaceSheet(it) } ?: setMode(Mode.HOME)
    }

    /** جابه‌جایی مبدأ و مقصد */
    private fun swapEndpoints() {
        val oldDest = selected ?: return
        val o = originPlace
        val newDest: Place = o ?: lastKnown()?.let { Place(getString(R.string.my_position), it.latitude, it.longitude) }
            ?: run { toast(R.string.need_location_for_route); return }
        originPlace = oldDest
        selected = newDest
        setDestMarker(newDest)
        requestRoute(navProfile)
    }

    // ---------- مکان‌های ذخیره‌شده ----------
    private fun showPlaces() {
        val entries = ArrayList<Pair<String, Place>>()
        store.home?.let { entries.add(Pair(getString(R.string.home) + ": " + it.name, it)) }
        store.work?.let { entries.add(Pair(getString(R.string.work) + ": " + it.name, it)) }
        store.favorites().forEach { entries.add(Pair("★ " + it.name, it)) }
        store.recents().forEach { entries.add(Pair(it.name, it)) }
        if (entries.isEmpty()) { toast(R.string.no_saved); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.places)
            .setItems(entries.map { it.first }.toTypedArray()) { _, i -> select(entries[i].second) }
            .show()
    }

    private fun showSaveDialog() {
        val p = selected ?: return
        val items = arrayOf(getString(R.string.set_home), getString(R.string.set_work), getString(R.string.add_favorite))
        AlertDialog.Builder(this)
            .setTitle(R.string.save_place)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> store.home = p
                    1 -> store.work = p
                    else -> store.addFavorite(p)
                }
                toast(R.string.saved_ok)
                buildCategoryChips()
            }.show()
    }

    private fun shareSelected() {
        val p = selected ?: return
        val link = "https://www.openstreetmap.org/?mlat=${p.lat}&mlon=${p.lon}#map=17/${p.lat}/${p.lon}"
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.name + "\n" + link)
        startActivity(Intent.createChooser(i, getString(R.string.share)))
    }

    // ---------- مسیریابی ----------
    private fun lastKnown(): Location? =
        if (locationEnabled) try { map?.locationComponent?.lastKnownLocation } catch (e: Exception) { null } else null

    private fun onPlanRoute() {
        if (NavigationController.navigating) return
        if (selected != null) {
            requestRoute(navProfile)
            return
        }
        toast(R.string.need_location_for_route)
        etSearch.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun styleTabs() {
        val blue = Color.parseColor("#1976D2")
        val gray = Color.parseColor("#6B7788")
        fun style(tab: View, iv: ImageView, tv: TextView, active: Boolean) {
            tab.setBackgroundResource(if (active) R.drawable.bg_tab_sel else R.drawable.bg_tab)
            ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(if (active) blue else gray))
            tv.setTextColor(if (active) blue else gray)
        }
        style(tabCar, ivTabCar, tvTabCar, navProfile == Profile.CAR)
        style(tabMoto, ivTabMoto, tvTabMoto, navProfile == Profile.MOTO)
        style(tabBike, ivTabBike, tvTabBike, navProfile == Profile.BIKE)
        style(tabFoot, ivTabFoot, tvTabFoot, navProfile == Profile.FOOT)
        ivRouteMode.setImageResource(
            when (navProfile) {
                Profile.CAR -> R.drawable.ic_car
                Profile.MOTO -> R.drawable.ic_moto
                Profile.BIKE -> R.drawable.ic_bike
                Profile.FOOT -> R.drawable.ic_walk
            }
        )
    }

    private fun setProfile(p: Profile) {
        navProfile = p
        styleTabs()
        if (mode == Mode.ROUTE) requestRoute(p)
    }

    /** مبدأ مسیر: مبدأ انتخابی کاربر یا موقعیت فعلی */
    private fun originLatLon(): Pair<Double, Double>? {
        originPlace?.let { return Pair(it.lat, it.lon) }
        val loc = lastKnown() ?: return null
        return Pair(loc.latitude, loc.longitude)
    }

    private fun requestRoute(profile: Profile) {
        if (selected == null) return
        val o = originLatLon()
        if (o == null) {
            toast(R.string.need_location_for_route)
            if (!hasLocationPermission()) permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        requestRouteFrom(profile, o.first, o.second)
    }

    /** نام جاده‌های اصلی مسیر (بر اساس طول): «از طریق الف - ب - ج» */
    private fun viaText(r: RouteResult): String {
        val agg = LinkedHashMap<String, Double>()
        for (s in r.steps) {
            val n = s.name.trim()
            if (n.isNotEmpty()) agg[n] = (agg[n] ?: 0.0) + s.distance
        }
        val top = agg.entries.sortedByDescending { it.value }.take(3).map { it.key }
        return if (top.isEmpty()) getString(R.string.route_no_names) else getString(R.string.via_fmt, top.joinToString(" - "))
    }

    /** حباب‌های زمان روی نقشه؛ مسیر انتخابی بنفش، جایگزین‌ها خاکستری (کلیک = انتخاب) */
    private fun clearBubbles() {
        bubbleLayer.removeAllViews()
        bubbles.clear()
    }

    private fun rebuildBubbles() {
        clearBubbles()
        if (mode != Mode.ROUTE) return
        var altCount = 0
        for ((i, r) in altRoutes.withIndex()) {
            if (r.points.size < 2) continue
            val frac = if (i == selectedRouteIdx) 0.5 else if (altCount++ % 2 == 0) 0.3 else 0.7
            val idx = (r.points.size * frac).toInt().coerceIn(0, r.points.size - 1)
            val pt = r.points[idx]
            val v = TextView(this)
            v.text = NavigationController.formatDuration(this, r.durationS)
            v.textSize = 13f
            v.setTypeface(v.typeface, android.graphics.Typeface.BOLD)
            v.setTextColor(Color.WHITE)
            v.setPadding(dp(10), dp(5), dp(10), dp(5))
            v.setBackgroundResource(if (i == selectedRouteIdx) R.drawable.bg_bubble_sel else R.drawable.bg_bubble_alt)
            v.elevation = dp(4).toFloat()
            v.setOnClickListener { selectRoute(i) }
            bubbleLayer.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            bubbles.add(Bubble(v, LatLng(pt.second, pt.first)))
            v.post { positionBubbles() }
        }
    }

    private fun positionBubbles() {
        val m = map ?: return
        for (b in bubbles) {
            if (b.view.width == 0) continue
            val p = try { m.projection.toScreenLocation(b.anchor) } catch (e: Exception) { continue }
            b.view.x = p.x - b.view.width / 2f
            b.view.y = p.y - b.view.height - dp(4)
        }
    }

    /** انتخاب مسیر: مسیر انتخابی بنفش پررنگ، بقیه کم‌رنگ */
    private fun selectRoute(i: Int) {
        if (i < 0 || i >= altRoutes.size) return
        selectedRouteIdx = i
        drawnRoute = altRoutes[i]
        drawAllRoutes()
        rebuildBubbles()
        updateRouteInfo()
    }

    private fun updateRouteInfo() {
        val r = altRoutes.getOrNull(selectedRouteIdx) ?: return
        tvOrigin.text = originPlace?.name ?: getString(R.string.my_position)
        tvDestName.text = selected?.name ?: ""
        tvVia.text = viaText(r)
        tvRouteTime.text = NavigationController.formatDuration(this, r.durationS)
        tvRouteDist.text = NavigationController.formatDistance(this, r.distanceM)
        tvSafety.visibility = View.VISIBLE
    }

    /** ترسیم همه مسیرها: انتخابی بنفش ضخیم، جایگزین‌ها بنفش کم‌رنگ */
    private fun drawAllRoutes() {
        if (altRoutes.isEmpty()) return
        val altFc = FeatureCollection.fromFeatures(altRoutes.mapIndexedNotNull { i, r ->
            if (i == selectedRouteIdx) null
            else Feature.fromGeometry(
                LineString.fromLngLats(r.points.map { Point.fromLngLat(it.first, it.second) })
            )
        })
        style?.getSourceAs<GeoJsonSource>("route-alt-src")?.setGeoJson(altFc)
        val sel = altRoutes.getOrNull(selectedRouteIdx) ?: altRoutes[0]
        drawnRoute = sel
        val line = LineString.fromLngLats(sel.points.map { Point.fromLngLat(it.first, it.second) })
        style?.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(Feature.fromGeometry(line))
    }

    private fun fitRoutes() {
        val r = altRoutes.getOrNull(selectedRouteIdx) ?: return
        val b = LatLngBounds.Builder()
        r.points.forEach { b.include(LatLng(it.second, it.first)) }
        // حاشیه بالا/پایین برای سرصفحه و برگه مسیر
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(60), dp(170), dp(60), dp(300)))
    }

    private fun drawRoute(r: RouteResult) {
        drawnRoute = r
        val line = LineString.fromLngLats(r.points.map { Point.fromLngLat(it.first, it.second) })
        style?.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(Feature.fromGeometry(line))
    }

    /** «مرور مسیر»: فهرست دستورهای مسیر انتخابی */
    private fun showStepsDialog() {
        val r = altRoutes.getOrNull(selectedRouteIdx) ?: return
        val items = r.steps.map { s ->
            val base = NavigationController.instrTitle(NavigationController.keyOf(s), s.name).replace("\n", " • ")
            if (s.distance > 0) base + "  (" + NavigationController.formatDistance(this, s.distance) + ")" else base
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.steps_title)
            .setItems(items.toTypedArray(), null)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun clearRoute() {
        drawnRoute = null
        altRoutes = emptyList()
        selectedRouteIdx = 0
        clearBubbles()
        style?.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        style?.getSourceAs<GeoJsonSource>("route-alt-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        style?.getSourceAs<GeoJsonSource>("nav-arrow-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        style?.getSourceAs<GeoJsonSource>("origin-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    private fun clearAll() {
        if (NavigationController.navigating) NavigationController.stop()
        resetToHome()
    }

    /** بازگشت کامل به صفحه خانه: انتخاب، مسیر، مبدأ و نشانگرها پاک می‌شوند */
    private fun resetToHome() {
        selected = null
        originPlace = null
        pick = Pick.NONE
        etSearch.setHint(R.string.where_to)
        clearRoute()
        setDestMarker(null)
        showSource("")
        refreshRecents()
        buildCategoryChips()
        setMode(Mode.HOME)
    }

    /** نمایش بخش‌های مناسب هر حالت (خانه / مکان / مسیر / ناوبری) */
    private fun setMode(m: Mode) {
        mode = m
        val nav = m == Mode.NAV
        // در رانندگی آزاد برگه خانه (جست‌وجو) پنهان می‌شود تا کارت سرعت‌سنج و دکمه «پایان»
        // کامل روی صفحه جا شوند (اشکال نیمه‌صفحه در نسخه‌های قبل)
        val home = m == Mode.HOME && !NavigationController.freeMode
        homeSheet.visibility = if (home) View.VISIBLE else View.GONE
        panel.visibility = if (m == Mode.PLACE) View.VISIBLE else View.GONE
        routeSheet.visibility = if (m == Mode.ROUTE) View.VISIBLE else View.GONE
        routeHeader.visibility = if (m == Mode.ROUTE) View.VISIBLE else View.GONE
        btnRouteSettings.visibility = if (m == Mode.ROUTE) View.VISIBLE else View.GONE
        navBanner.visibility = if (nav) View.VISIBLE else View.GONE
        nextCard.visibility = View.GONE
        navBar.visibility = if (nav) View.VISIBLE else View.GONE
        leftCol.visibility = if (nav) View.VISIBLE else View.GONE
        fabColumn.visibility = if (nav) View.GONE else View.VISIBLE
        fabRoute.visibility = if (home) View.VISIBLE else View.GONE
        bottomNav.visibility = if (m == Mode.HOME) View.VISIBLE else View.GONE
        if (m != Mode.HOME) resultsCard.visibility = View.GONE
        if (m != Mode.ROUTE) clearBubbles()
        if (nav) tvSource.visibility = View.GONE
        if (m == Mode.ROUTE && bubbles.isEmpty() && altRoutes.isNotEmpty()) rebuildBubbles()
    }

    // ---------- ناوبری ----------
    private fun arrowRes(key: String): Int = when (key) {
        "right" -> R.drawable.ic_turn_right
        "left" -> R.drawable.ic_turn_left
        "slight_right" -> R.drawable.ic_turn_slight_right
        "slight_left" -> R.drawable.ic_turn_slight_left
        "sharp_right" -> R.drawable.ic_turn_sharp_right
        "sharp_left" -> R.drawable.ic_turn_sharp_left
        "uturn" -> R.drawable.ic_turn_uturn
        "roundabout", "exit_roundabout" -> R.drawable.ic_turn_roundabout
        "arrive" -> R.drawable.ic_turn_arrive
        else -> R.drawable.ic_turn_straight
    }

    private fun updateCompassLabel() {
        btnCompass.text = getString(if (northUp) R.string.north else R.string.heading)
    }

    private fun startNav() {
        val dest = selected ?: return
        val r = drawnRoute ?: return
        if (!hasLocationPermission()) { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return }
        // اجازه اعلان برای اندروید ۱۳+ (اگر رد شود سرویس باز هم کار می‌کند)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (originPlace != null) toast(R.string.nav_origin_note)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterNavChrome()
        val first = r.steps.firstOrNull()
        tvNavDist.text = ""
        tvNavText.text = first?.name?.takeIf { it.isNotEmpty() } ?: NavigationController.instrTitle("depart", "")
        tvNavInstr.text = NavigationController.instrTitle("depart", "")
        ivNavArrow.setImageResource(arrowRes("depart"))
        tvSpeed.text = "0"
        updateEta(r.durationS, r.distanceM)

        if (locationEnabled) {
            val lc = map?.locationComponent
            lc?.cameraMode = CameraMode.NONE
            lc?.renderMode = RenderMode.GPS
        }

        val ok = NavigationController.start(r, dest, navProfile)
        if (!ok) {
            toast(R.string.location_denied)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exitNavChrome(backToRoute = true)
        }
    }

    /** حالت نمایش ناوبری: بنر مشکی، نوار پایین با ساعت رسیدن، سرعت‌سنج و قطب‌نما */
    private fun enterNavChrome() {
        if (mode != Mode.NAV) setMode(Mode.NAV)
    }

    /** بازگشت پس از پایان ناوبری (توقف یا رسیدن) */
    private fun exitNavChrome(backToRoute: Boolean = false) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (locationEnabled) map?.locationComponent?.renderMode = RenderMode.NORMAL
        map?.let { m ->
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).tilt(0.0).bearing(0.0).build()))
        }
        updateMuteIcon()
        if (backToRoute && altRoutes.isNotEmpty()) setMode(Mode.ROUTE) else resetToHome()
    }

    private fun confirmStopNav() {
        AlertDialog.Builder(this)
            .setMessage(R.string.stop_confirm)
            .setPositiveButton(R.string.stop_nav) { _, _ -> NavigationController.stop() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** وضعیت ناوبری از کنترلر (کار می‌کند حتی اگر ناوبری در سرویس ادامه یابد) */
    private fun onNavState(st: NavigationController.UiState) {
        if (st.navigating) {
            enterNavChrome()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val d = if (st.toManeuverM < 1000) (st.toManeuverM / 10).roundToInt() * 10.0 else st.toManeuverM
            tvNavDist.text = NavigationController.formatDistance(this, d)
            val instr = NavigationController.instrTitle(st.instructionKey, "")
            tvNavText.text = if (st.instructionName.isNotEmpty()) st.instructionName else instr
            tvNavInstr.text = instr
            ivNavArrow.setImageResource(arrowRes(st.instructionKey))
            // «و بعد» با پیکان پیچ بعدی
            val nk = NavigationController.nextKey(st.stepIndex)
            if (nk == null) {
                nextCard.visibility = View.GONE
            } else {
                ivNextArrow.setImageResource(arrowRes(nk))
                nextCard.visibility = View.VISIBLE
            }
            val r = NavigationController.currentRoute
            if (r != null && r !== drawnRoute) drawRoute(r)
            updateEta(st.remainingS, st.remainingM)
        } else if (mode == Mode.NAV) {
            // ناوبری تمام شد (توقف یا رسیدن)
            exitNavChrome()
            if (st.arrived) toast(R.string.nav_arrive)
        }
    }

    /** نوار پایین: «رسیدن ۰۹:۲۱» و زیر آن مسافت و زمان باقی‌مانده */
    private fun updateEta(remainingS: Double, remainingM: Double) {
        val eta = Calendar.getInstance()
        eta.add(Calendar.MINUTE, (remainingS / 60).roundToInt())
        val t = String.format(Locale.US, "%02d:%02d", eta.get(Calendar.HOUR_OF_DAY), eta.get(Calendar.MINUTE))
        tvEta.text = getString(R.string.arrival_label) + " " + t
        tvRemainTime.text = NavigationController.formatDistance(this, remainingM) + "   " +
            NavigationController.formatDuration(this, remainingS)
    }

    /** هر قرائت GPS هنگام ناوبری: دوربین دنبال می‌کند + سرعت‌سنج */
    @SuppressLint("MissingPermission")
    private fun onFixDuringNav(loc: Location) {
        if (NavigationController.freeMode) {
            // رانندگی آزاد: سرعت‌سنج بزرگ + دنبال‌کردن نرم موقعیت
            if (loc.hasSpeed()) tvFreeSpeed.text = (loc.speed * 3.6).roundToInt().coerceIn(0, 240).toString()
            map?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude))
                        .zoom(16.0).tilt(0.0).build()
                ), 800
            )
            return
        }
        if (NavigationController.navigating && loc.hasSpeed()) {
            tvSpeed.text = (loc.speed * 3.6).roundToInt().coerceIn(0, 200).toString()
        }
        // تیرک جهت: روی نقطه چسبیده‌به‌مسیر می‌نشیند و به سمت قطعه مسیر می‌چرخد
        if (NavigationController.hasSnap) {
            try {
                val f = Feature.fromGeometry(Point.fromLngLat(NavigationController.lastSnapLon, NavigationController.lastSnapLat))
                f.addNumberProperty("bearing", NavigationController.lastSnapBearing)
                style?.getSourceAs<GeoJsonSource>("nav-arrow-src")?.setGeoJson(f)
            } catch (e: Exception) { }
            val bearing = if (northUp) 0.0 else NavigationController.lastSnapBearing
            map?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(NavigationController.lastSnapLat, NavigationController.lastSnapLon))
                        .zoom(17.5).tilt(45.0).bearing(bearing).build()
                ), 900
            )
        } else {
            val bearing = if (northUp) 0.0 else if (loc.hasBearing()) loc.bearing.toDouble() else (map?.cameraPosition?.bearing ?: 0.0)
            map?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude))
                        .zoom(17.0).tilt(40.0).bearing(bearing).build()
                ), 900
            )
        }
    }

    // ---------- موقعیت ----------
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun onMyLocation() {
        if (!hasLocationPermission()) { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return }
        if (!locationEnabled) enableLocation()
        if (locationEnabled && !NavigationController.navigating) map?.locationComponent?.cameraMode = CameraMode.TRACKING
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation() {
        val s = style ?: return
        val lc = map?.locationComponent ?: return
        if (!hasLocationPermission()) return
        if (!locationEnabled) {
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(this, s).build())
            lc.isLocationComponentEnabled = true
            locationEnabled = true
        }
        if (!NavigationController.navigating) lc.cameraMode = CameraMode.TRACKING
    }

    // ---------- تنظیمات: صدا، شب، زبان، درباره ----------
    private fun toggleVoice() {
        NavigationController.voiceEnabled = !NavigationController.voiceEnabled
        if (!NavigationController.voiceEnabled) NavigationController.stopVoice()
        updateMuteIcon()
        toast(if (NavigationController.voiceEnabled) R.string.voice_on else R.string.voice_off)
    }

    private fun updateMuteIcon() {
        btnMute.setImageResource(
            if (NavigationController.voiceEnabled) R.drawable.ic_voice else R.drawable.ic_voice_off
        )
    }

    /** انتخاب حالت نقشه: خودکار / روز / شب */
    private fun showMapModeDialog() {
        val items = arrayOf(getString(R.string.map_auto), getString(R.string.map_day), getString(R.string.map_night))
        AlertDialog.Builder(this)
            .setTitle(R.string.map_mode_title)
            .setSingleChoiceItems(items, prefs.getInt("night", 0)) { d, i ->
                prefs.edit().putInt("night", i).apply()
                loadStyle()
                d.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showSettings() {
        val items = arrayOf(
            getString(R.string.language),
            getString(R.string.night_mode),
            getString(R.string.voice_title),
            getString(R.string.alerts_title),
            getString(R.string.routing_title),
            getString(R.string.free_title),
            getString(R.string.map_labels_title),
            getString(R.string.traffic_item),
            getString(R.string.check_update),
            getString(R.string.about)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showLanguageDialog()
                    1 -> showMapModeDialog()
                    2 -> showVoiceDialog()
                    3 -> showAlertsDialog()
                    4 -> showRoutingDialog()
                    5 -> startFreeDriving()
                    6 -> showMapLabelsDialog()
                    7 -> showTrafficSettingsDialog()
                    8 -> checkUpdates()
                    else -> showAbout()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showAbout() {
        val msg = getString(R.string.version_fmt, Config.APP_VERSION) + "\n\n" +
            getString(R.string.safety_notice)
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(msg)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private var styleUrl = ""

    // شنوندهٔ تغییر وضعیت انترنت — سوییچ خودکار آنلاین/آفلاین نقشه.
    // فقط یک نمونه ثبت می‌شود (در onStart) و در onStop آزاد می‌شود تا هیچ نشتی و
    // هیچ انباشتِ شنونده‌ای رخ ندهد؛ انباشت شنونده‌ها هنگام خاموش/روشن‌کردن
    // انترنت باعث سنگینی و بسته‌شدن برنامه می‌شد.
    private var netListener: android.net.ConnectivityManager.NetworkCallback? = null
    private var netListenerInstalled = false

    private fun wantDark(): Boolean {
        val mode = prefs.getInt("night", 0)
        val sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return mode == 2 || (mode == 0 && sysNight)
    }

    /**
     * بارگذاری سبک نقشه — سوییچ خودکار آنلاین/آفلاین:
     *  - انترنت (وای‌فای/داده) روشن باشد → سبک آنلاین کامل؛ نقشه همان نمایش همیشگی دارد.
     *  - انترنت خاموش باشد → خودکار سبک آفلاین از بستهٔ نصب‌شده (کاشی روی خود گوشی).
     *  - هیچ‌کدام نبود → پیام روشن به کاربر.
     * تغییر وضعیت انترنت با netListener خودکار شنیده می‌شود و نقشه بدون هیچ اقدامی
     * از کاربر بین آنلاین و آفلاین جابه‌جا می‌شود — بدون تغییر در کارکرد برنامه.
     */
    private fun loadStyle() {
        val m = map ?: return
        if (isNetworkAvailable()) {
            applyOnlineStyle()
            probeOnlineReachability()
            return
        }
        // بدون انترنت — بستهٔ نقشهٔ بر اساس مکان فعلی/مرکز نقشه انتخاب می‌شود تا اگر
        // چند ولایت نصب باشد، بستهٔ همان ولایتِ کاربر استفاده شود؛ بیرون آن → بستهٔ پایه
        val pkg = pickOfflinePackage()
        if (pkg != null) {
            applyOfflineStyle(pkg)
            return
        }
        offlineStyleActive = false
        if (style == null) toast(R.string.map_offline_unavailable)
    }

    /** بستهٔ آفلاین مناسب موقعیت فعلی/مرکز نقشه */
    private fun pickOfflinePackage(): OfflineData.Installed? {
        // مرکز دیدِ نقشه اولویت دارد: کاربر همان‌جا را نگاه می‌کند (نه لزوماً جایی که GPS نشان می‌دهد)
        val target = map?.cameraPosition?.target
        val loc = lastKnown()
        return when {
            target != null -> OfflineData.activeMapPackage(target.latitude, target.longitude)
            loc != null -> OfflineData.activeMapPackage(loc.latitude, loc.longitude)
            else -> OfflineData.activeMapPackage()
        }
    }

    /** سطح عقب‌نشینی سبک آفلاین: ۰ = ولایت+پایه، ۱ = فقط ولایت، ۲ = فقط بسته پایه */
    private var offlineLevel = 0
    private var offlinePkgId = ""
    private var lastOfflinePkg: OfflineData.Installed? = null

    /** سبک آفلاین: کاشی‌های PMTiles روی خود گوشی — بدون هیچ درخواست انترنت */
    private fun applyOfflineStyle(pkg0: OfflineData.Installed) {
        val m = map ?: return
        if (pkg0.id != offlinePkgId) { offlinePkgId = pkg0.id; offlineLevel = 0 }
        lastOfflinePkg = pkg0
        val baseAll = OfflineData.basePackage()
        // سطح ۲: فقط بسته پایه؛ سطح ۱: فقط ولایت؛ سطح ۰: ولایت + پایه
        val pkg = if (offlineLevel >= 2 && baseAll != null) baseAll else pkg0
        val base = if (offlineLevel == 0) baseAll?.takeIf { it.id != pkg.id } else null
        val localJson = buildOfflineStyleJson(pkg, base) ?: return
        val key = "offline:${pkg.dirName}+${base?.dirName ?: "-"}:${if (wantDark()) "d" else "l"}:L$offlineLevel"
        if (styleUrl == key && style != null) return
        styleUrl = key
        offlineStyleActive = true
        findViewById<TextView>(R.id.tvAttribution).visibility = View.GONE
        locationEnabled = false
        m.setStyle(Style.Builder().fromJson(localJson)) { s ->
            style = s
            addRouteLayers(s)
            // پیام تشخیصی: کدام بسته و تا کدام زوم داده دارد (برای پیدا کردن علت نقشهٔ خالی)
            try {
                val z = pmtilesZoomRange(OfflineData.tilesFile(pkg))
                // بدون فایل گلیف (فونت) هیچ نامی روی نقشه نمی‌آید؛ اینجا وجودش را بررسی و گزارش می‌کنیم
                val fontFile = java.io.File(OfflineData.effectiveFontsDir(this, pkg), "Noto Sans Regular/0-255.pbf")
                val fontOk = fontFile.isFile && fontFile.length() > 0
                Toast.makeText(this, "نقشه آفلاین: ${pkg.id}" + (if (base != null) " + پایه" else "") +
                    (if (z != null) " • زوم داده ${z.first}–${z.second}" else "") +
                    (if (fontOk) " • فونت ✓" else " • ⚠ فونت نام‌ها در برنامه نیست"),
                    Toast.LENGTH_LONG).show()
            } catch (_: Exception) { }
        }
    }

    /**
     * رفع «نقشهٔ خاکستری»: اگر منبعی از سبک آفلاین بارگذاری نشود (مثلاً فایل کاشی ولایت یا پایه
     * مشکل دارد)، MapLibre کل نقشه را خالی نگه می‌دارد. پس به‌ترتیب ساده‌تر می‌شویم:
     * ولایت+پایه ← فقط ولایت ← فقط پایه؛ و پیام خطا نمایش داده می‌شود.
     */
    private fun handleOfflineStyleFailure(errMsg: String?) {
        val pkg = lastOfflinePkg ?: return
        android.util.Log.e("RahyabMap", "خطای سبک آفلاین (سطح $offlineLevel، بسته ${pkg.id}): $errMsg")
        Toast.makeText(this, "نقشه آفلاین (سطح $offlineLevel، ${pkg.id}): ${errMsg ?: "?"}".take(160), Toast.LENGTH_LONG).show()
        if (offlineLevel < 2) {
            offlineLevel++
            styleUrl = ""
            applyOfflineStyle(pkg)
        }
    }

    /** سبک آنلاین OpenFreeMap (روز یا شب) — همان نمایش کامل همیشگی */
    private fun applyOnlineStyle() {
        val m = map ?: return
        val url = if (wantDark()) Config.STYLE_DARK else Config.STYLE_LIGHT
        if (url == styleUrl && style != null) return
        styleUrl = url
        offlineStyleActive = false
        findViewById<TextView>(R.id.tvAttribution).visibility = View.GONE
        locationEnabled = false
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            style = s
            addRouteLayers(s)
        }
    }

    /**
     * اگر بارگیری سبک آنلاین شکست خورد (مثلاً وای‌فای وصل است ولی انترنت واقعی
     * پاسخ نمی‌دهد) → بی‌درنگ سبک آفلاین از بستهٔ نصب‌شده بار می‌شود تا نقشه هرگز
     * خالی/خاکستری نماند.
     */
    private fun handleOnlineStyleFailure() {
        if (offlineStyleActive) return
        val pkg = pickOfflinePackage() ?: return
        applyOfflineStyle(pkg)
    }

    /**
     * رفع اشکال «نقشه خالی با VPN/وای‌فایِ بدون اینترنت واقعی»: اندروید شبکه را «وصل» می‌بیند
     * ولی سرور نقشه جواب نمی‌دهد، پس سبک آنلاین بدون کاشی می‌ماند. اینجا یک بررسی سریع
     * (۳ ثانیه) انجام می‌شود و اگر شکست خورد، سبک آفلاین بسته نصب‌شده بار می‌شود.
     */
    private fun probeOnlineReachability() {
        if (probing || offlineStyleActive) return
        if (pickOfflinePackage() == null) return // بدون بستهٔ آفلاین راهی برای جایگزینی نیست
        probing = true
        lastProbeMs = System.currentTimeMillis()
        io.execute {
            val ok = try {
                val u = java.net.URL(if (wantDark()) Config.STYLE_DARK else Config.STYLE_LIGHT)
                val c = u.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 3000; c.requestMethod = "GET"
                try { c.responseCode; true } finally { c.disconnect() }
            } catch (e: Exception) { false }
            ui.post {
                probing = false
                if (!ok && !isDestroyed && !isFinishing) {
                    netReallyDown = true
                    loadStyle()
                } else if (ok) netReallyDown = false
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        if (netReallyDown) return false
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            // از API ۲۳ به بالا «تأییدشده» یعنی انترنت واقعاً پاسخ می‌دهد —
            // وای‌فای بدون انترنت (مثل کپتیو پورتال) → حالت آفلاین، نه نقشهٔ خالی
            if (Build.VERSION.SDK_INT >= 23 &&
                !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) return false
            true
        } catch (e: Exception) {
            true // در صورت تردید، تلاش برای بارگذاری آنلاین
        }
    }

    /** شنیدن تغییر انترنت — یک نمونهٔ واحد؛ بدون ثبت تکراری و بدون نشت حافظه */
    private fun ensureNetListener() {
        if (netListenerInstalled) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    ui.post {
                        if (!isDestroyed && !isFinishing) {
                            netReallyDown = false
                            loadStyle()
                            if (trafficOn) refreshTrafficData()
                        }
                    }
                }

                override fun onLost(network: android.net.Network) {
                    ui.post {
                        if (!isDestroyed && !isFinishing) loadStyle()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                cm.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(), cb
                )
            }
            netListener = cb
            netListenerInstalled = true
        } catch (e: Exception) { }
    }

    private fun clearNetListener() {
        if (!netListenerInstalled) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            netListener?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) { }
        netListener = null
        netListenerInstalled = false
    }

    private fun addRouteLayers(s: Style) {
        // منبع مسیرهای جایگزین
        s.addSource(GeoJsonSource("route-alt-src", FeatureCollection.fromFeatures(emptyList<Feature>())))
        s.addSource(GeoJsonSource("route-src", FeatureCollection.fromFeatures(emptyList<Feature>())))
        s.addSource(GeoJsonSource("dest-src", FeatureCollection.fromFeatures(emptyList<Feature>())))

        // مسیرهای جایگزین: بنفش کم‌رنگ با پوشش سفید (زیر مسیر اصلی)
        s.addLayer(
            LineLayer("route-alt-casing", "route-alt-src").withProperties(
                lineColor(Color.parseColor("#FFFFFF")), lineWidth(11f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        s.addLayer(
            LineLayer("route-alt-line", "route-alt-src").withProperties(
                lineColor(Color.parseColor("#B39DDB")), lineWidth(8f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        // مسیر اصلی: پوشش تیره + بنفش ضخیم (سبک نشان)
        s.addLayer(
            LineLayer("route-casing", "route-src").withProperties(
                lineColor(Color.parseColor("#4A1E9E")), lineWidth(12f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        s.addLayer(
            LineLayer("route-layer", "route-src").withProperties(
                lineColor(Color.parseColor("#7C4DFF")), lineWidth(9f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        // جهت حرکت: پیکان‌های سفید روی مسیر انتخابی (چیده‌شده در طول خط)
        try {
            if (s.getImage("route-arrow") == null) {
                s.addImage("route-arrow", makeRouteArrowBitmap())
            }
            s.addLayer(
                org.maplibre.android.style.layers.SymbolLayer("route-arrows", "route-src").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.iconImage("route-arrow"),
                    org.maplibre.android.style.layers.PropertyFactory.symbolPlacement(
                        org.maplibre.android.style.layers.Property.SYMBOL_PLACEMENT_LINE
                    ),
                    org.maplibre.android.style.layers.PropertyFactory.symbolSpacing(110f),
                    org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment(
                        org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP
                    ),
                    org.maplibre.android.style.layers.PropertyFactory.iconPadding(2f)
                )
            )
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "لایه پیکان مسیر ساخته نشد", e)
        }

        s.addLayer(
            CircleLayer("dest-layer", "dest-src").withProperties(
                circleColor(Color.parseColor("#E53935")), circleRadius(9f),
                circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)
            )
        )
        // مبدأ مسیر — نقطه آبی از مبدأ تا مقصد روی مسیر
        s.addSource(GeoJsonSource("origin-src", FeatureCollection.fromFeatures(emptyList<Feature>())))
        s.addLayer(
            CircleLayer("origin-layer", "origin-src").withProperties(
                circleColor(Color.parseColor("#1E88E5")), circleRadius(8f),
                circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)
            )
        )
        // تیرک جهت ناوبری — همیشه دقیقاً روی مسیر می‌نشیند (نه روی GPS خام)
        try {
            if (s.getImage("nav-arrow-icon") == null) s.addImage("nav-arrow-icon", makeNavArrowBitmap())
            s.addSource(GeoJsonSource("nav-arrow-src", FeatureCollection.fromFeatures(emptyList<Feature>())))
            s.addLayer(
                org.maplibre.android.style.layers.SymbolLayer("nav-arrow-layer", "nav-arrow-src").withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.iconImage("nav-arrow-icon"),
                    org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                    org.maplibre.android.style.layers.PropertyFactory.iconRotate(Expression.get("bearing")),
                    org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment(
                        org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_VIEWPORT
                    )
                )
            )
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "لایه تیرک ناوبری ساخته نشد", e)
        }
        addTrafficLayers(s)
        applyAfLabels(s)
        drawnRoute?.let { r ->
            val line = LineString.fromLngLats(r.points.map { Point.fromLngLat(it.first, it.second) })
            s.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(Feature.fromGeometry(line))
        }
        selected?.let {
            s.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)))
        }
        if (hasLocationPermission()) enableLocation()
    }

    /** بیت‌مپ تیرک ناوبری (پیکان آبی بزرگ به سبک نشان) */
    private fun makeNavArrowBitmap(): android.graphics.Bitmap {
        val size = 44
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44000000")
            style = android.graphics.Paint.Style.FILL
        }
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E88E5")
            style = android.graphics.Paint.Style.FILL
        }
        val white = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = size / 11f
        }
        val path = android.graphics.Path()
        path.moveTo(size / 2f, 3f)
        path.lineTo(size - 7f, size - 7f)
        path.lineTo(size / 2f, size - 16f)
        path.lineTo(7f, size - 7f)
        path.close()
        c.save(); c.translate(1.5f, 2.5f); c.drawPath(path, shadow); c.restore()
        c.drawPath(path, p)
        c.drawPath(path, white)
        return bmp
    }

    /** ساخت بیت‌مپ پیکان جهت حرکت (بدون نیاز به فایل خارجی) */
    private fun makeRouteArrowBitmap(): android.graphics.Bitmap {
        val size = 36
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.FILL
            setShadowLayer(2f, 0f, 1f, Color.parseColor("#55000000"))
        }
        // پیکان رو به بالا (شمال) — چرخش بر اساس جهت یال انجام می‌شود
        val path = android.graphics.Path()
        path.moveTo(size / 2f, 4f)
        path.lineTo(size - 7f, size - 6f)
        path.lineTo(size / 2f, size - 14f)
        path.lineTo(7f, size - 6f)
        path.close()
        c.drawPath(path, p)
        return bmp
    }

    /**
     * سبک آفلاین: خواندن style.json بسته و جای‌گذاری مسیر محلی کاشی‌ها و فونت‌ها.
     *
     * نقشه یکپارچه کل کشور: بسته پایه کشوری (کاشی زوم ۰ تا ۸ سراسر افغانستان) زیر
     * کاشی‌های ولایتی (زوم ۹ به بالا) قرار می‌گیرد؛ نتیجه: خارج از بسته‌های نصب‌شده
     * هم نقشه سراسری دیده می‌شود و مرز بسته‌ها هیچ «حفره خالی» ندارد.
     *  - منبع «rahyab» → بسته اصلی (ولایت نصب‌شده یا خود بسته پایه)
     *  - منبع «rahyab-base» → بسته پایه (فقط زوم ≤ ۸)
     * لایه‌های سبک برای منبع پایه با سقف زوم ۸ و برای منبع اصلی با کف زوم ۹ بازسازی می‌شوند.
     */
    /** بازهٔ زوم واقعی فایل PMTiles از سرآیند (بایت ۱۰۰ و ۱۰۱)؛ null اگر خوانده نشد */
    private fun pmtilesZoomRange(f: java.io.File): Pair<Int, Int>? {
        return try {
            java.io.FileInputStream(f).use { ins ->
                val h = ByteArray(127)
                val n = ins.read(h)
                if (n < 102 || String(h, 0, 7, Charsets.US_ASCII) != "PMTiles") null
                else Pair(h[100].toInt() and 0xFF, h[101].toInt() and 0xFF)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildOfflineStyleJson(pkg: OfflineData.Installed, base: OfflineData.Installed?): String? {
        return try {
            // سبک روز/شب داخل خود برنامه (ظاهر نقشه بدون بازسازی بسته‌های داده)؛ در صورت نبود، سبک خود بسته
            val assetStyle = try {
                assets.open(if (wantDark()) "style/rahyab_dark.json" else "style/rahyab_light.json")
                    .bufferedReader().use { it.readText() }
            } catch (e: Exception) { null }
            var json: String = assetStyle ?: (OfflineData.styleFile(pkg).takeIf { it.isFile }?.readText() ?: return null)
            val tiles = OfflineData.tilesFile(pkg)
            if (!tiles.isFile) return null
            // قالب استایل خودش «pmtiles://__PMTILES__» است؛ پس اینجا فقط «file://» + مسیر می‌آید (نتیجه pmtiles://file:///…).
            // ⚠️ این خط را به «pmtiles://file://» برنگردانید: می‌شود pmtiles://pmtiles://file://… و نقشه خالی می‌ماند.
            json = json.replace("__PMTILES__", "file://" + tiles.absolutePath)
            json = json.replace("__FONTS__", "file://" + OfflineData.effectiveFontsDir(this, pkg).absolutePath)

            if (base != null) {
                val baseTiles = OfflineData.tilesFile(base)
                if (baseTiles.isFile) {
                    val o = org.json.JSONObject(json)
                    val sources = o.getJSONObject("sources")
                    // منبع پایه — همان قالب کاشی
                    // ⚠️ رفع اشکال: منبع پایه از روی منبع اصلی کپی می‌شد و فیلد «url» (که به فایل
                    // ولایت اشاره داشت) می‌ماند؛ MapLibre «url» را بر «tiles» ترجیح می‌دهد، پس
                    // «پایه» هم از فایل ولایت خوانده می‌شد و بیرون از ولایت نقشه خاکستری بود.
                    val baseSrc = org.json.JSONObject(sources.getJSONObject("rahyab").toString())
                    baseSrc.remove("tiles")
                    baseSrc.put("url", "pmtiles://file://" + baseTiles.absolutePath)
                    sources.put("rahyab-base", baseSrc)
                    // لایه‌ها: نسخه پایه (زوم ≤ ۸) + نسخه اصلی (زوم ≥ ۹)
                    val layers = o.getJSONArray("layers")
                    val out = org.json.JSONArray()
                    for (i in 0 until layers.length()) {
                        val layer = layers.getJSONObject(i)
                        if (layer.optString("source") == "rahyab") {
                            val baseLayer = org.json.JSONObject(layer.toString())
                            baseLayer.put("id", layer.getString("id") + "-base")
                            baseLayer.put("source", "rahyab-base")
                            val mz = if (baseLayer.has("maxzoom")) baseLayer.getInt("maxzoom") else 22
                            // maxzoom در MapLibre انحصاری است (زیر این زوم دیده می‌شود): ۹ یعنی تا ۸٫۹۹
                            // و لایه ولایتی از ۹ شروع می‌شود → بدون «نوار خالی» بین زوم ۸ و ۹
                            baseLayer.put("maxzoom", minOf(mz, 9))
                            out.put(baseLayer)
                            val mz0 = if (layer.has("minzoom")) layer.getInt("minzoom") else 0
                            layer.put("minzoom", maxOf(mz0, 9))
                        }
                        out.put(layer)
                    }
                    o.put("layers", out)
                    json = o.toString()
                }
            }
            // بازهٔ زوم واقعی هر فایل را صریحاً در سبک می‌گذاریم تا MapLibre بالاتر از زوم داده،
            // کاشی آخرین زوم را بزرگ‌نمایی کند (overzoom) و نقشه در بزرگ‌نمایی زیاد خالی نماند
            try {
                val o2 = org.json.JSONObject(json)
                val srcs = o2.getJSONObject("sources")
                pmtilesZoomRange(tiles)?.let { (mn, mx) ->
                    srcs.optJSONObject("rahyab")?.apply { put("minzoom", mn); put("maxzoom", mx) }
                }
                if (base != null) pmtilesZoomRange(OfflineData.tilesFile(base))?.let { (mn, mx) ->
                    srcs.optJSONObject("rahyab-base")?.apply { put("minzoom", mn); put("maxzoom", mx) }
                }
                json = o2.toString()
            } catch (_: Exception) { }
            json
        } catch (e: Exception) {
            null
        }
    }

    private fun showLanguageDialog() {
        val names = arrayOf(
            getString(R.string.lang_dari), getString(R.string.lang_persian),
            getString(R.string.lang_pashto), getString(R.string.lang_english)
        )
        val tags = arrayOf("fa-AF", "fa-IR", "ps-AF", "en")
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_language)
            .setItems(names) { _, i ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[i]))
            }.show()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- ترافیک زنده (بخش سوم چک‌لیست) ----------
    private fun hideMapAttribution() {
        // پاک‌کردن نوشته‌های منبع نقشه و لوگو از روی صفحه (درخواست کاربر؛ اطلاعات منبع فقط در مستندات می‌ماند)
        try {
            val mc = mapView as android.view.ViewGroup
            for (i in 0 until mc.childCount) {
                val ch = mc.getChildAt(i)
                // نما‌های داخلی نقشه: اتریبیوشن و لوگو فقط ImageView هستند؛ قطب‌نما View جدا است
                if (ch is android.widget.ImageView) ch.visibility = View.GONE
            }
        } catch (e: Throwable) { }
    }

    /**
     * برچسب‌های افغانستان روی نقشه (درخواست کاربر: پاک‌کردن نام‌های بیرون افغانستان).
     *  - لایه‌های نام مکان سبک پایه (place…) وقتی «فقط افغانستان» فعال است پنهان می‌شوند.
     *  - به‌جای آن‌ها برچسب دری ولایات، شهرها و شهرستان‌ها از پایگاه‌داده داخلی
     *    «af_places.geojson» نمایش داده می‌شود — همه نام‌ها افغانی و دری‌اند.
     */
    private fun applyAfLabels(s: Style) {
        try {
            val afOnly = prefs.getBoolean("labels_af_only", true)
            for (l in s.layers) {
                if (l.id.startsWith("place")) {
                    l.setProperties(visibility(if (afOnly) Property.NONE else Property.VISIBLE))
                }
            }
            if (s.getSource("af-places") == null) {
                s.addSource(GeoJsonSource("af-places", "asset://places/af_places.geojson"))
                val nameExpr = Expression.get("n")
                val font = arrayOf("Noto Sans Regular")
                fun size(a: Float, sa: Float, b: Float, sb: Float) = Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(a, Expression.literal(sa)),
                    Expression.stop(b, Expression.literal(sb))
                )
                val prov = SymbolLayer("af-place-prov", "af-places").withProperties(
                    textField(nameExpr), textFont(font),
                    textSize(size(4.0f, 11.0f, 10.0f, 16.0f)),
                    textColor(Color.parseColor("#3D2E6E")),
                    textHaloColor(Color.WHITE), textHaloWidth(1.6f),
                    textAllowOverlap(true), textIgnorePlacement(true),
                    visibility(if (afOnly) Property.VISIBLE else Property.NONE)
                )
                prov.setFilter(Expression.eq(Expression.get("c"), Expression.literal("province")))
                prov.minZoom = 4.0f
                s.addLayer(prov)
                val city = SymbolLayer("af-place-city", "af-places").withProperties(
                    textField(nameExpr), textFont(font),
                    textSize(size(6.0f, 10.5f, 12.0f, 15.0f)),
                    textColor(Color.parseColor("#1B2735")),
                    textHaloColor(Color.WHITE), textHaloWidth(1.4f),
                    visibility(if (afOnly) Property.VISIBLE else Property.NONE)
                )
                city.setFilter(Expression.eq(Expression.get("c"), Expression.literal("city")))
                city.minZoom = 6.0f
                s.addLayer(city)
                val town = SymbolLayer("af-place-town", "af-places").withProperties(
                    textField(nameExpr), textFont(font),
                    textSize(size(9.0f, 10.0f, 13.0f, 12.5f)),
                    textColor(Color.parseColor("#3A4A5E")),
                    textHaloColor(Color.WHITE), textHaloWidth(1.2f),
                    visibility(if (afOnly) Property.VISIBLE else Property.NONE)
                )
                town.setFilter(Expression.any(
                    Expression.eq(Expression.get("c"), Expression.literal("town")),
                    Expression.eq(Expression.get("c"), Expression.literal("district"))
                ))
                town.minZoom = 9.0f
                s.addLayer(town)
            } else {
                for (id in listOf("af-place-prov", "af-place-city", "af-place-town")) {
                    s.getLayer(id)?.setProperties(visibility(if (afOnly) Property.VISIBLE else Property.NONE))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "برچسب‌های افغانی اعمال نشد", e)
        }
    }

    private fun addTrafficLayers(s: Style) {
        try {
            for (c in listOf("traffic-green" to "#23A455", "traffic-orange" to "#F5A623", "traffic-red" to "#E0342C")) {
                s.addSource(GeoJsonSource(c.first + "-src", FeatureCollection.fromFeatures(emptyList<Feature>())))
                s.addLayer(
                    CircleLayer(c.first, c.first + "-src").withProperties(
                        circleColor(Color.parseColor(c.second)), circleRadius(8f),
                        circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)
                    )
                )
            }
            // کاشی جریان TomTom فقط اگر کاربر کلید API خودش را ثبت کرده باشد (اختیاری کامل)
            val url = TrafficModule.tomtomTileUrl(this)
            if (url != null) {
                s.addSource(org.maplibre.android.style.sources.RasterSource("tomtom-flow-src", url, 256))
                s.addLayer(
                    org.maplibre.android.style.layers.RasterLayer("tomtom-flow", "tomtom-flow-src")
                        .withProperties(org.maplibre.android.style.layers.PropertyFactory.rasterOpacity(0.8f))
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "لایه ترافیک ساخته نشد", e)
        }
    }

    /** ترسیم نقاط ترافیک: گزارش‌های کاربران + فید جامعه — فقط رنگ واقعی، بدون داده جعلی */
    private fun renderTraffic() {
        val st = style ?: return
        val pts = if (trafficOn) TrafficModule.allPoints(this) else emptyList()
        val green = ArrayList<Feature>(); val orange = ArrayList<Feature>(); val red = ArrayList<Feature>()
        for (p in pts) {
            val f = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))
            f.addStringProperty("title", p.title)
            when (p.color) {
                "#23A455" -> green.add(f)
                "#F5A623" -> orange.add(f)
                else -> red.add(f)
            }
        }
        st.getSourceAs<GeoJsonSource>("traffic-green-src")?.setGeoJson(FeatureCollection.fromFeatures(green))
        st.getSourceAs<GeoJsonSource>("traffic-orange-src")?.setGeoJson(FeatureCollection.fromFeatures(orange))
        st.getSourceAs<GeoJsonSource>("traffic-red-src")?.setGeoJson(FeatureCollection.fromFeatures(red))
        st.getLayer("tomtom-flow")?.setProperties(
            org.maplibre.android.style.layers.PropertyFactory.rasterOpacity(if (trafficOn) 0.8f else 0f)
        )
    }

    private fun toggleTraffic() {
        trafficOn = !TrafficModule.isEnabled(this)
        TrafficModule.setEnabled(this, trafficOn)
        toast(if (trafficOn) R.string.traffic_on else R.string.traffic_off)
        refreshTrafficData()
    }

    /** تازه‌سازی فید ترافیک؛ شکست شبکه بی‌صدا است (قانون بدون داده جعلی).
     *  شنوندهٔ شبکه در ensureNetListener یک بار ثبت می‌شود — ثبت تکراری در هر فراخوانی،
     *  انباشت شنونده و بسته‌شدن برنامه هنگام تغییر انترنت می‌ساخت. */
    private fun refreshTrafficData() {
        io.execute {
            TrafficModule.refreshFeed(this@MainActivity)
            ui.post { renderTraffic() }
        }
        scheduleTrafficRefresh()
    }

    private fun scheduleTrafficRefresh() {
        if (trafficScheduled) return
        trafficScheduled = true
        refreshHandler.postDelayed({
            trafficScheduled = false
            if (trafficOn && !isDestroyed && !isFinishing) refreshTrafficData()
        }, 90_000L)
    }

    /** تنظیمات پیشرفته ترافیک — فقط با لمس طولانی دکمه یا از تنظیمات؛ عادی نیازی به تنظیم ندارد */
    private fun showTrafficSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val tvStatus = TextView(this)
        tvStatus.text = TrafficModule.statusLine(this)
        tvStatus.textSize = 13f
        val etKey = EditText(this)
        etKey.hint = getString(R.string.traffic_tomtom_hint)
        etKey.setText(TrafficModule.tomtomKey(this))
        etKey.textSize = 13f
        val etFeed = EditText(this)
        etFeed.hint = getString(R.string.traffic_feed_hint)
        etFeed.setText(TrafficModule.feedUrl(this))
        etFeed.textSize = 13f
        container.addView(tvStatus)
        container.addView(etKey)
        container.addView(etFeed)
        AlertDialog.Builder(this)
            .setTitle(R.string.traffic_settings)
            .setView(container)
            .setPositiveButton(R.string.save_ok) { _, _ ->
                TrafficModule.setTomtomKey(this, etKey.text.toString())
                TrafficModule.setFeedUrl(this, etFeed.text.toString())
                TrafficModule.lastFeedFetchReset()
                refreshTrafficData()
            }
            .setNeutralButton(
                getString(if (TrafficModule.isEnabled(this)) R.string.traffic_turn_off else R.string.traffic_turn_on)
            ) { _, _ ->
                TrafficModule.setEnabled(this, !TrafficModule.isEnabled(this))
                trafficOn = TrafficModule.isEnabled(this)
                refreshTrafficData()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- هواشناسی و کیفیت هوا (بخش نهم چک‌لیست) ----------
    private fun refreshWeatherCard() {
        val loc = lastKnown()
        val lat = loc?.latitude ?: prefs.getFloat("map_lat", Config.START_LAT.toFloat()).toDouble()
        val lon = loc?.longitude ?: prefs.getFloat("map_lon", Config.START_LON.toFloat()).toDouble()
        io.execute {
            val d = WeatherModule.refresh(this@MainActivity, lat, lon)
            ui.post {
                if (d != null) {
                    weatherCard.visibility = View.VISIBLE
                    tvWeather.text = getString(R.string.weather_short,
                        WeatherModule.emoji(d.code), d.tempC.roundToInt(), WeatherModule.describe(d.code, d.isDay),
                        WeatherModule.aqiLabel(d.aqi))
                }
            }
        }
    }

    private fun showWeatherDialog() {
        val d = WeatherModule.last
        if (d == null) { toast(R.string.weather_fetching); refreshWeatherCard(); return }
        val sb = StringBuilder()
        sb.append(WeatherModule.emoji(d.code) + " " + WeatherModule.describe(d.code, d.isDay) + " — " + d.tempC.roundToInt() + " درجه\n")
        sb.append(getString(R.string.weather_feels, d.feelsC.roundToInt()) + "\n")
        sb.append(getString(R.string.weather_humidity, d.humidity) + "\n")
        sb.append(getString(R.string.weather_wind, d.windKmh.roundToInt()) + "\n")
        if (d.pm25 != null) sb.append(getString(R.string.weather_pm25, d.pm25) + "\n")
        if (d.pm10 != null) sb.append(getString(R.string.weather_pm10, d.pm10) + "\n")
        sb.append(getString(R.string.weather_aqi, WeatherModule.aqiLabel(d.aqi)) + "\n\n")
        for (day in d.daily) {
            sb.append(day.dateIso + ": " + WeatherModule.emoji(day.code) + " " +
                day.minC.roundToInt() + "° — " + day.maxC.roundToInt() + "° • " +
                getString(R.string.weather_rain_prob, day.rainProb) + "\n")
        }
        sb.append("\n" + getString(R.string.weather_model_note) + "\n")
        sb.append(getString(R.string.weather_updated, NavigationController.formatTimeOfDay(d.fetchedMs)))
        AlertDialog.Builder(this).setTitle(R.string.weather_title).setMessage(sb.toString())
            .setPositiveButton(R.string.close, null).show()
    }

    // ---------- رادیو (بخش دهم چک‌لیست) ----------
    private fun showRadioDialog() {
        toast(R.string.radio_loading)
        io.execute {
            val stations = RadioModule.list(this@MainActivity)
            ui.post {
                if (stations.isEmpty()) { toast(R.string.radio_failed); return@post }
                val names = stations.map {
                    (if (RadioService.isPlaying() && RadioService.nowPlaying == it.name) "▶ " else "") +
                        it.name + " — " + it.tag
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.radio)
                    .setItems(names) { _, i -> playStation(stations[i]) }
                    .setPositiveButton(R.string.radio_stop) { _, _ -> RadioService.stop(this) }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
        }
    }

    private fun playStation(s: RadioModule.Station) {
        RadioService.start(this, s.url, s.name)
        Toast.makeText(this, getString(R.string.radio_playing_toast, s.name), Toast.LENGTH_SHORT).show()
    }

    // ---------- گزارش حوادث (بخش هشتم چک‌لیست) ----------
    private fun showReportDialog() {
        val target = selected
        val loc = if (target != null) Pair(target.lat, target.lon) else mapCenter()
        if (loc == null) { toast(R.string.need_location_for_route); return }
        showReportTypePicker(loc.first, loc.second)
    }

    /** انتخاب نوع گزارش، سپس یادداشت */
    private fun showReportTypePicker(lat: Double, lon: Double) {
        val labels = arrayOf(
            getString(R.string.report_accident), getString(R.string.report_damage),
            getString(R.string.report_closed), getString(R.string.report_jam),
            getString(R.string.report_hazard), getString(R.string.report_bump),
            getString(R.string.report_cam)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.report_title)
            .setItems(labels) { _, i ->
                val type = when (i) {
                    0 -> ReportsModule.TYPE_ACCIDENT
                    1 -> ReportsModule.TYPE_ROAD_DAMAGE
                    2 -> ReportsModule.TYPE_ROAD_CLOSED
                    3 -> ReportsModule.TYPE_HEAVY_TRAFFIC
                    4 -> ReportsModule.TYPE_HAZARD
                    5 -> ReportsModule.TYPE_SPEED_BUMP
                    else -> ReportsModule.TYPE_SPEED_CAMERA
                }
                showReportNoteDialog(type, lat, lon)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun mapCenter(): Pair<Double, Double>? {
        val t = map?.cameraPosition?.target ?: return null
        return Pair(t.latitude, t.longitude)
    }

    private fun showReportNoteDialog(type: String, lat: Double, lon: Double) {
        val et = EditText(this)
        et.hint = getString(R.string.report_note_hint)
        val pad = dp(20)
        et.setPadding(pad, pad / 2, pad, pad / 2)
        AlertDialog.Builder(this)
            .setTitle(R.string.report_note_title)
            .setView(et)
            .setPositiveButton(R.string.report_save) { _, _ ->
                val note = et.text.toString().trim().take(300)
                val sensitive = ReportsModule.isSensitive(note)
                try {
                    ReportsModule.add(this, type, note, lat, lon)
                    ReportsModule.purgeExpired(this)
                    toast(if (sensitive) R.string.report_saved_local else R.string.report_saved)
                    if (!sensitive) NavigationController.voiceReportSaved()
                    renderTraffic()
                } catch (e: Exception) {
                    toast(R.string.report_failed)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- راهنمای صوتی — تنظیمات کامل سخنگو (مثل «تنظیمات سخنگو» نشان) ----------
    /**
     * تنظیمات سخنگو به سبک برنامه نشان:
     *  - کلید روشن/خاموش سخنگو
     *  - فهرست صداهای ضبط‌شده داخل برنامه (امیر، دلارا، فرید، نگار، همایون) با دکمه پخش
     *    برای شنیدن هر صدا پیش از انتخاب — کاملاً آفلاین
     *  - گزینه موتور گفتار گوشی برای کسانی که صدای فارسی گوشی دارند
     *  - بلندی صدا و سرعت گفتار با لغزنده؛ بلوتوث؛ پخش در جریان مکالمه
     *  - دکمه «آزمایش صدا» و ذخیره
     */
    private fun showVoiceDialog() {
        val vg = NavigationController.voiceGuide
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(6))
        }

        // ---------- کلید سخنگو ----------
        val swRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val swOn = android.widget.Switch(this)
        swOn.text = getString(R.string.voice_state)
        swOn.isChecked = NavigationController.voiceEnabled
        swRow.addView(swOn)
        c.addView(swRow)

        // ---------- زبان راهنما از زبان برنامه ----------
        val curLang = vg?.appLang() ?: "fa"
        val langName = getString(when (curLang) {
            "ps" -> R.string.voice_lang_pashto_name
            "en" -> R.string.voice_lang_english_name
            else -> R.string.voice_lang_dari_name
        })
        c.addView(TextView(this).apply {
            text = getString(R.string.voice_lang_note, langName)
            textSize = 12f; setTextColor(0xFF2E6DB4.toInt()); setPadding(0, dp(12), 0, dp(2))
        })

        // ---------- فهرست صداها (به تفکیک زبان) ----------
        c.addView(TextView(this).apply { text = getString(R.string.voice_source); setPadding(0, dp(8), 0, dp(4)); textSize = 14f })
        val packs = vg?.packs() ?: emptyList()
        val hasPack = packs.isNotEmpty()
        if (hasPack) {
            c.addView(TextView(this).apply {
                text = getString(R.string.voice_list_note, vg!!.packClipCount(vg!!.packId))
                textSize = 12f; setTextColor(0xFF555555.toInt()); setPadding(0, 0, 0, dp(6))
            })
        }
        val selectedPack = arrayOf(if (hasPack) vg!!.packId else "")
        val selectedSource = arrayOf(prefs.getString("voice_source", "pack") ?: "pack")
        val radios = ArrayList<Pair<String, android.widget.RadioButton>>() // (packId یا "system")

        fun selectWhich(which: String) {
            selectedSource[0] = if (which == "system") "system" else "pack"
            if (which != "system") selectedPack[0] = which
            for ((id, rb) in radios) rb.isChecked = id == which
        }

        /** یک ردیف صدا: دکمه رادیو + نام + جنسیت/تعداد + دکمه پخش */
        fun addPackRow(p: af.rahyab.app.VoiceGuide.PackInfo) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val rb = android.widget.RadioButton(this).apply {
                isChecked = selectedSource[0] == "pack" && selectedPack[0] == p.id
            }
            radios.add(p.id to rb)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = p.name + "  —  " + getString(when (p.lang) {
                    "ps" -> R.string.voice_lang_pashto_name
                    "en" -> R.string.voice_lang_english_name
                    else -> R.string.voice_lang_dari_name
                })
                textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            col.addView(TextView(this).apply {
                text = getString(if (p.gender == "female") R.string.voice_female else R.string.voice_male) +
                    " • " + getString(R.string.voice_pack_status, p.clips).substringAfter(": ").substringBefore(" —") +
                    (if (p.id == selectedPack[0] && selectedSource[0] == "pack" && p.lang == curLang)
                        " • " + getString(R.string.voice_active_tag) else "")
                textSize = 11f; setTextColor(0xFF777777.toInt())
            })
            val play = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_play)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ContextCompat.getDrawable(context, R.drawable.bg_btn_ghost)
                contentDescription = getString(R.string.voice_play)
            }
            play.setOnClickListener {
                // جمله پشتیبان به زبان همان بسته — اگر فایل صوتی پخش نشد، موتور گوشی حرف می‌زند
                val sentence = when (p.lang) {
                    "ps" -> "غږیز لارښود راحیاب چمتو دی."
                    "en" -> "Rahyab voice guidance is ready."
                    else -> "راهنمای صوتی راه‌یاب آماده است."
                }
                vg?.previewPack(p.id, sentence)
                selectWhich(p.id)
            }
            row.addView(rb)
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) })
            row.addView(play)
            row.setPadding(0, dp(2), 0, dp(2))
            c.addView(row)
            rb.setOnClickListener { selectWhich(p.id) }
        }

        // صداهای ضبط‌شده داخل برنامه — به سه زبان گروه‌بندی شده
        // (زبان راهنما همیشه همان زبان برنامه است؛ صدای زبان دیگر پس از تغییر زبان پخش می‌شود)
        val groups = listOf(
            Triple("fa", R.string.voice_group_dari, R.string.voice_lang_dari_name),
            Triple("ps", R.string.voice_group_pashto, R.string.voice_lang_pashto_name),
            Triple("en", R.string.voice_group_english, R.string.voice_lang_english_name)
        )
        for ((gl, labelRes, nameRes) in groups) {
            val gp = packs.filter { it.lang == gl }
            if (gp.isEmpty()) continue
            c.addView(TextView(this).apply {
                text = getString(labelRes) +
                    (if (gl == curLang) "  •  " + getString(R.string.voice_active_tag) else "")
                textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (gl == curLang) 0xFF2E6DB4.toInt() else 0xFF888888.toInt())
                setPadding(0, dp(10), 0, dp(2))
            })
            for (p in gp) addPackRow(p)
        }

        // گزینه موتور گفتار گوشی
        val rbSystem = android.widget.RadioButton(this).apply {
            text = getString(R.string.voice_system_option)
            isChecked = selectedSource[0] == "system"
        }
        radios.add("system" to rbSystem)
        c.addView(rbSystem)
        rbSystem.setOnClickListener { selectWhich("system") }
        val sysOk = vg?.hasPersianVoice() == true && (vg?.packAvailable() == false || selectedSource[0] == "system")
        if (!sysOk) {
            c.addView(TextView(this).apply {
                text = getString(R.string.voice_system_unavailable) + " — «" + getString(R.string.voice_test) + "» را بزنید و در صورت نیاز «" + getString(R.string.voice_install) + "» را از راهنما استفاده کنید."
                textSize = 11f; setTextColor(0xFFB00020.toInt()); setPadding(0, 0, 0, dp(4))
            })
        }

        // ---------- بلندی صدا ----------
        c.addView(TextView(this).apply { text = getString(R.string.voice_volume); setPadding(0, dp(12), 0, dp(2)); textSize = 14f })
        val sbVol = android.widget.SeekBar(this)
        sbVol.max = 100
        sbVol.progress = prefs.getInt("voice_vol", 85)
        c.addView(sbVol)

        // ---------- سرعت گفتار ----------
        c.addView(TextView(this).apply { text = getString(R.string.voice_speed); setPadding(0, dp(10), 0, dp(2)); textSize = 14f })
        val sbSpeed = android.widget.SeekBar(this)
        sbSpeed.max = 100
        sbSpeed.progress = prefs.getInt("voice_speed", 40)
        c.addView(sbSpeed)

        // ---------- بلوتوث ----------
        val swBt = android.widget.Switch(this)
        swBt.text = getString(R.string.voice_bluetooth)
        swBt.isChecked = vg?.bluetoothAllowed ?: true
        c.addView(swBt)

        // ---------- مکالمه ----------
        val swCall = android.widget.Switch(this)
        swCall.text = getString(R.string.voice_during_call)
        swCall.isChecked = vg?.speakDuringCalls ?: false
        c.addView(swCall)

        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.voice_title)
            .setView(android.widget.ScrollView(this).apply { addView(c) })
            .setPositiveButton(R.string.saved_ok) { _, _ ->
                NavigationController.voiceEnabled = swOn.isChecked
                // ذخیره صدا برای زبان خودِ آن صدا؛ با تغییر زبان برنامه همان صدا پخش می‌شود
                var mismatchName: String? = null
                var mismatchLang: String? = null
                val edit = prefs.edit()
                if (selectedSource[0] == "system") {
                    edit.putString("voice_source", "system")
                } else {
                    val p = selectedPack[0].ifEmpty { "dari" }
                    val pLang = packs.firstOrNull { it.id == p }?.lang ?: curLang
                    edit.putString("voice_source", "pack").putString("voice_pack_$pLang", p)
                    if (pLang != curLang) {
                        mismatchName = packs.firstOrNull { it.id == p }?.name ?: p
                        mismatchLang = getString(when (pLang) {
                            "ps" -> R.string.voice_lang_pashto_name
                            "en" -> R.string.voice_lang_english_name
                            else -> R.string.voice_lang_dari_name
                        })
                    }
                }
                edit.putInt("voice_vol", sbVol.progress).putInt("voice_speed", sbSpeed.progress).apply()
                vg?.bluetoothAllowed = swBt.isChecked
                vg?.speakDuringCalls = swCall.isChecked
                updateMuteIcon()
                if (mismatchName != null && mismatchLang != null) {
                    toast(getString(R.string.voice_saved_msg) + " — " +
                        getString(R.string.voice_lang_mismatch_toast, mismatchName, mismatchLang))
                } else {
                    toast(R.string.voice_saved_msg)
                }
            }
            .setNeutralButton(R.string.voice_test) { _, _ ->
                // آزمایش با انتخاب فعلی صفحه — پیش از ذخیره هم درست بشنود
                val wasEnabled = NavigationController.voiceEnabled
                if (!wasEnabled) NavigationController.voiceEnabled = true
                val ok = if (selectedSource[0] == "system" || selectedPack[0].isEmpty()) {
                    NavigationController.testVoice()
                } else {
                    // پیش‌نمایش مستقیم همان صدای انتخابی — مستقل از زبان فعلی برنامه.
                    // اگر فایل صوتی بسته به هر دلیل پخش نشد، previewPack خودش جمله
                    // پشتیبان را با موتور گوشی (به زبان همان بسته) می‌گوید.
                    val p = selectedPack[0]
                    val pLang = packs.firstOrNull { it.id == p }?.lang ?: curLang
                    val sentence = when (pLang) {
                        "ps" -> "غږیز لارښود راحیاب چمتو دی."
                        "en" -> "Rahyab voice guidance is ready."
                        else -> "راهنمای صوتی راه‌یاب آماده است."
                    }
                    vg?.previewPack(p, sentence)
                    true
                }
                if (!ok) toast(R.string.voice_test_failed)
                if (!wasEnabled) NavigationController.voiceEnabled = false
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun voiceClipCount(): Int = try {
        assets.list("voice/dari")?.count { it.endsWith(".ogg") } ?: 0
    } catch (e: Exception) { 0 }

    // ---------- تنظیمات هشدارها (مثل «تنظیمات هشدارها» نشان) ----------
    private fun showAlertsDialog() {
        val p = getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(6))
        }
        val keys = listOf(
            "a_bump" to R.string.alert_bump,
            "a_accident" to R.string.alert_accident,
            "a_traffic" to R.string.alert_traffic,
            "a_closed" to R.string.alert_closed,
            "a_hazard" to R.string.alert_hazard,
            "a_cam" to R.string.alert_cam,
            "a_speed" to R.string.alert_speed
        )
        val switches = HashMap<String, android.widget.Switch>()
        for ((k, label) in keys) {
            val sw = android.widget.Switch(this)
            sw.text = getString(label)
            sw.isChecked = p.getBoolean(k, true)
            c.addView(sw)
            switches[k] = sw
        }
        c.addView(TextView(this).apply {
            text = getString(R.string.alert_speed_limit, p.getInt("a_limit", 80))
            setPadding(0, dp(14), 0, dp(2)); textSize = 14f
        })
        val sbLimit = android.widget.SeekBar(this)
        sbLimit.max = 9 // ۵۰ تا ۱۴۰ با گام ۱۰
        sbLimit.progress = ((p.getInt("a_limit", 80) - 50) / 10).coerceIn(0, 9)
        val tvLimit = c.getChildAt(c.childCount - 1) as TextView
        sbLimit.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                tvLimit.text = getString(R.string.alert_speed_limit, 50 + value * 10)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { }
        })
        c.addView(sbLimit)
        c.addView(TextView(this).apply {
            text = getString(R.string.alerts_note)
            textSize = 11f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(10), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.alerts_title)
            .setView(c)
            .setPositiveButton(R.string.saved_ok) { _, _ ->
                val e = p.edit()
                for ((k, _) in keys) e.putBoolean(k, switches[k]!!.isChecked)
                e.putInt("a_limit", 50 + sbLimit.progress * 10)
                e.apply()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- تنظیمات مسیریابی (مثل «تنظیمات طرح و مسیریابی» نشان) ----------
    private fun showRoutingDialog() {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(6))
        }
        val keys = listOf(
            "r_avoid_traffic" to R.string.routing_avoid_traffic,
            "r_avoid_unpaved" to R.string.routing_avoid_unpaved,
            "r_avoid_toll" to R.string.routing_avoid_toll
        )
        val switches = HashMap<String, android.widget.Switch>()
        for ((k, label) in keys) {
            val sw = android.widget.Switch(this)
            sw.text = getString(label)
            sw.isChecked = prefs.getBoolean(k, false)
            c.addView(sw)
            switches[k] = sw
        }
        c.addView(TextView(this).apply {
            text = getString(R.string.routing_note)
            textSize = 11f; setTextColor(0xFF666666.toInt()); setPadding(0, dp(12), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.routing_title)
            .setView(c)
            .setPositiveButton(R.string.saved_ok) { _, _ ->
                val e = prefs.edit()
                for ((k, _) in keys) e.putBoolean(k, switches[k]!!.isChecked)
                e.apply()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** نام‌های روی نقشه: فقط افغانستان (پیش‌فرض) یا همه کشورها */
    private fun showMapLabelsDialog() {
        val items = arrayOf(getString(R.string.map_labels_af), getString(R.string.map_labels_all))
        val cur = if (prefs.getBoolean("labels_af_only", true)) 0 else 1
        AlertDialog.Builder(this)
            .setTitle(R.string.map_labels_title)
            .setSingleChoiceItems(items, cur) { d, i ->
                prefs.edit().putBoolean("labels_af_only", i == 0).apply()
                style?.let { applyAfLabels(it) }
                d.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- رانندگی آزاد (مثل نشان) ----------
    private fun startFreeDriving() {
        if (NavigationController.navigating) { toast(R.string.stop_nav_first); return }
        if (!hasLocationPermission()) { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return }
        if (!locationEnabled) enableLocation()
        if (NavigationController.startFree()) {
            freeCard.visibility = View.VISIBLE
            tvFreeSpeed.text = "0"
            tvFreeAlert.text = ""
            setMode(Mode.HOME)
        }
    }

    private fun endFreeDriving() {
        NavigationController.stopFree()
        freeCard.visibility = View.GONE
        setMode(Mode.HOME) // برگه جست‌وجو دوباره نمایش داده شود
    }

    // ---------- به‌روزرسانی مستقیم (بخش دوازدهم چک‌لیست) ----------
    private fun checkUpdates() {
        toast(R.string.update_checking)
        io.execute {
            try {
                val rel = Updater.checkLatest() ?: throw BadResponseException("no release")
                val newer = Updater.isNewer(rel, this@MainActivity)
                ui.post {
                    if (!newer) toast(R.string.update_latest)
                    else AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_available, rel.versionName))
                        .setMessage(getString(R.string.update_info, rel.sizeBytes / 1048576) + "\n\n" + rel.notes.take(500))
                        .setPositiveButton(R.string.update_download) { _, _ -> downloadUpdate(rel) }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
            } catch (e: Exception) {
                ui.post { toast(R.string.update_unavailable) }
            }
        }
    }

    private fun downloadUpdate(rel: Updater.Release) {
        val tv = TextView(this)
        tv.setPadding(dp(24), dp(16), dp(24), dp(16))
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.update_downloading)
            .setView(tv)
            .setNegativeButton(R.string.close, null)
            .show()
        Updater.download(this, rel,
            onProgress = { p -> ui.post { tv.text = getString(R.string.update_progress, p) } },
            onDone = { f ->
                ui.post {
                    dlg.dismiss()
                    if (!Updater.canInstall(this)) {
                        Updater.requestInstallPermission(this)
                    } else {
                        Updater.install(this, f) { ok, msg ->
                            ui.post { toast(if (ok) getString(R.string.update_done) else getString(R.string.update_failed, msg)) }
                        }
                    }
                }
            },
            onError = { msg ->
                ui.post {
                    dlg.dismiss()
                    toast(getString(R.string.update_failed, msg))
                }
            }
        )
    }

    // ---------- تغییر مبدأ/مقصد دستی روی نقشه ----------
    private fun pointName(lat: Double, lon: Double): String {
        val la = String.format(java.util.Locale.US, "%.4f", lat)
        val lo = String.format(java.util.Locale.US, "%.4f", lon)
        return getString(R.string.point_on_map, la, lo)
    }

    /** لمس/لمس طولانی روی نقشه: در حالت انتخاب مستقیماً مبدأ/مقصد؛ در غیر این صورت لمس طولانی گزینه می‌دهد */
    private fun onMapPointChosen(lat: Double, lon: Double, longPress: Boolean) {
        if (pick != Pick.NONE) {
            val p = Place(pointName(lat, lon), lat, lon)
            resultsCard.visibility = View.GONE
            hideKeyboard()
            assignPickedPoint(p)
            return
        }
        if (longPress) showPointOptionsDialog(lat, lon)
    }

    /** گفت‌وگوی نقطه انتخابی روی نقشه: مقصد، مبدأ، گزارش رویداد، نمایش برگه مکان */
    private fun showPointOptionsDialog(lat: Double, lon: Double) {
        val name = pointName(lat, lon)
        val items = arrayOf(
            getString(R.string.set_as_dest),
            getString(R.string.set_as_origin),
            getString(R.string.report_here),
            getString(R.string.show_place)
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val p = Place(name, lat, lon)
                        selected = p
                        originPlace = null
                        setDestMarker(p)
                        store.addRecent(p)
                        refreshRecents()
                        requestRoute(navProfile)
                    }
                    1 -> {
                        originPlace = Place(name, lat, lon)
                        toast(R.string.origin_set)
                        if (selected != null) requestRoute(navProfile)
                    }
                    2 -> showReportTypePicker(lat, lon)
                    else -> {
                        val p = Place(name, lat, lon)
                        selected = p
                        originPlace = null
                        setDestMarker(p)
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0))
                        showPlaceSheet(p)
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- چرخه حیات ----------
    override fun onStart() {
        super.onStart()
        mapView.onStart()
        // سوییچ خودکار آنلاین/آفلاین نقشه با تغییر انترنت (یک نمونه؛ در onStop آزاد می‌شود)
        ensureNetListener()
        NavigationController.addUiListener(uiListener)
        NavigationController.addFixListener(fixListener)
        NavigationController.addAlertListener(alertListener)
        if (NavigationController.freeMode) freeCard.visibility = View.VISIBLE
        // با بسته صدای داخل برنامه، هیچ‌وقت پیام «صدای فارسی یافت نشد» نشان داده نمی‌شود
        if (!voiceDialogShown && !NavigationController.voiceHasPersian() &&
            !prefs.getBoolean("voice_dialog_shown", false)
        ) {
            voiceDialogShown = true
            prefs.edit().putBoolean("voice_dialog_shown", true).apply()
            ui.post { showVoiceDialog() }
        }
        // اگر ناوبری در سرویس در جریان است (مثلاً پس از بازگشت به برنامه)، رابط همگام شود
        if (NavigationController.navigating) onNavState(
            NavigationController.UiState(true, NavigationController.lastRemainingM, NavigationController.lastRemainingS,
                NavigationController.lastStepIndex, NavigationController.lastToManeuver,
                NavigationController.lastKey, NavigationController.lastName, false, false, false)
        )
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // بازگشت از صفحه مجوز: اگر اجازه داده شده، موقعیت فعال شود
        if (hasLocationPermission() && style != null) enableLocation()
        // اگر بسته‌ای (مثلاً بسته پایه در نخستین اجرا) نصب شد، سبک نقشه به‌روز شود
        OfflineData.onDataChanged = { ui.post { loadStyle() } }
        buildCategoryChips()
        refreshRecents()
        trafficOn = TrafficModule.isEnabled(this)
        refreshWeatherCard()
        refreshTrafficData()
    }

    override fun onStop() {
        clearNetListener()
        NavigationController.removeUiListener(uiListener)
        NavigationController.removeFixListener(fixListener)
        NavigationController.removeAlertListener(alertListener)
        mapView.onStop()
        super.onStop()
    }

    override fun onPause() {
        OfflineData.onDataChanged = null
        mapView.onPause(); super.onPause()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onDestroy() {
        mapView.onDestroy()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
