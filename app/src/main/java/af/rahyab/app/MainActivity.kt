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

    private val io = Executors.newFixedThreadPool(2)
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

    // حالت‌های صفحه (سبک نشان/بلد): خانه → مکان → پیش‌نمایش مسیر → ناوبری
    private enum class Mode { HOME, PLACE, ROUTE, NAV }
    private var mode = Mode.HOME

    // تغییر مبدأ/مقصد از سرصفحه مسیر
    private enum class Pick { NONE, ORIGIN, DEST }
    private var pick = Pick.NONE
    private var originPlace: Place? = null   // null = موقعیت فعلی
    private var northUp = false

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

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(Config.START_LAT, Config.START_LON))
                .zoom(Config.START_ZOOM).build()
            m.addOnCameraMoveListener { positionBubbles() }
            m.addOnCameraIdleListener { positionBubbles() }
            loadStyle()
        }

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
        btnMute.setOnClickListener { toggleVoice() }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_map -> { hideKeyboard(); resultsCard.visibility = View.GONE; true }
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
     * پذیرش درخواست مسیریابی از اپلیکیشن‌های دیگر:
     *   rahyab://navigate?origin_lat=..&origin_lon=..&destination_lat=..&destination_lon=..
     *                       [&travel_mode=driving|walking][&name=..]
     * اعتبارسنجی: مختصات باید در بازه معتبر جهانی باشد؛ در غیر این صورت درخواست رد می‌شود.
     * اگر اپ فراخوان EXTRA_RETURN_RESULT=true گذاشته باشد، فاصله/زمان مسیر برگردانده می‌شود.
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "rahyab" || uri.host != "navigate") return
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
    }

    private fun rejectDeepLink(why: String) {
        android.util.Log.w("MainActivity", "درخواست Deep Link رد شد: $why")
        toast(R.string.deep_link_invalid)
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
                        altRoutes = list
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
            Pick.ORIGIN -> {
                pick = Pick.NONE
                etSearch.setHint(R.string.where_to)
                originPlace = p
                toast(R.string.origin_set)
                if (selected != null) requestRoute(navProfile) else showPlaceSheet(p)
                return
            }
            Pick.DEST -> {
                pick = Pick.NONE
                etSearch.setHint(R.string.where_to)
                selected = p
                store.addRecent(p)
                setDestMarker(p)
                refreshRecents()
                requestRoute(navProfile)
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
        val car = navProfile == Profile.CAR
        val blue = Color.parseColor("#1976D2")
        val gray = Color.parseColor("#6B7788")
        tabCar.setBackgroundResource(if (car) R.drawable.bg_tab_sel else R.drawable.bg_tab)
        tabFoot.setBackgroundResource(if (!car) R.drawable.bg_tab_sel else R.drawable.bg_tab)
        ImageViewCompat.setImageTintList(ivTabCar, ColorStateList.valueOf(if (car) blue else gray))
        ImageViewCompat.setImageTintList(ivTabFoot, ColorStateList.valueOf(if (!car) blue else gray))
        tvTabCar.setTextColor(if (car) blue else gray)
        tvTabFoot.setTextColor(if (!car) blue else gray)
        ivRouteMode.setImageResource(if (car) R.drawable.ic_car else R.drawable.ic_walk)
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
        homeSheet.visibility = if (m == Mode.HOME) View.VISIBLE else View.GONE
        panel.visibility = if (m == Mode.PLACE) View.VISIBLE else View.GONE
        routeSheet.visibility = if (m == Mode.ROUTE) View.VISIBLE else View.GONE
        routeHeader.visibility = if (m == Mode.ROUTE) View.VISIBLE else View.GONE
        btnRouteSettings.visibility = if (m == Mode.ROUTE) View.VISIBLE else View.GONE
        navBanner.visibility = if (nav) View.VISIBLE else View.GONE
        nextCard.visibility = View.GONE
        navBar.visibility = if (nav) View.VISIBLE else View.GONE
        leftCol.visibility = if (nav) View.VISIBLE else View.GONE
        fabColumn.visibility = if (nav) View.GONE else View.VISIBLE
        fabRoute.visibility = if (m == Mode.HOME) View.VISIBLE else View.GONE
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
        if (NavigationController.navigating && loc.hasSpeed()) {
            tvSpeed.text = (loc.speed * 3.6).roundToInt().coerceIn(0, 200).toString()
        }
        val bearing = if (northUp) 0.0 else if (loc.hasBearing()) loc.bearing.toDouble() else (map?.cameraPosition?.bearing ?: 0.0)
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude))
                    .zoom(17.0).tilt(40.0).bearing(bearing).build()
            ), 900
        )
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
            getString(R.string.about)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showLanguageDialog()
                    1 -> showMapModeDialog()
                    else -> showAbout()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showAbout() {
        val msg = getString(R.string.version_fmt, Config.APP_VERSION) + "\n\n" +
            getString(R.string.data_note) + "\n\n" +
            getString(R.string.attribution_offline) + "\n\n" +
            getString(R.string.safety_notice)
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage(msg)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private var styleUrl = ""

    private fun wantDark(): Boolean {
        val mode = prefs.getInt("night", 0)
        val sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return mode == 2 || (mode == 0 && sysNight)
    }

    /**
     * بارگذاری سبک نقشه.
     * اگر بسته نقشه آفلاین نصب باشد → سبک محلی (PMTiles روی خود گوشی، بدون انترنت).
     * در غیر این صورت سبک آنلاین OpenFreeMap (روز یا شب)؛ اگر انترنت نبود پیام مناسب نمایش داده می‌شود.
     */
    private fun loadStyle() {
        val m = map ?: return
        val pkg = OfflineData.activeMapPackage()
        if (pkg != null) {
            val base = OfflineData.basePackage()
            val localJson = buildOfflineStyleJson(pkg, base?.takeIf { it.id != pkg.id })
            if (localJson != null) {
                val key = "offline:${pkg.dirName}+${base?.dirName ?: "-"}:${if (wantDark()) "d" else "l"}"
                if (styleUrl == key && style != null) return
                styleUrl = key
                offlineStyleActive = true
                findViewById<TextView>(R.id.tvAttribution).text = getString(R.string.attribution_offline)
                locationEnabled = false
                m.setStyle(Style.Builder().fromJson(localJson)) { s ->
                    style = s
                    addRouteLayers(s)
                }
                return
            }
        }
        offlineStyleActive = false
        findViewById<TextView>(R.id.tvAttribution).text = getString(R.string.attribution)
        val url = if (wantDark()) Config.STYLE_DARK else Config.STYLE_LIGHT
        if (url == styleUrl && style != null) return
        styleUrl = url
        locationEnabled = false
        if (!isNetworkAvailable()) {
            // بدون بسته آفلاین و بدون انترنت: پیام روشن به کاربر
            toast(R.string.map_offline_unavailable)
        }
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            style = s
            addRouteLayers(s)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true // در صورت تردید، تلاش برای بارگذاری آنلاین
        }
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
        drawnRoute?.let { r ->
            val line = LineString.fromLngLats(r.points.map { Point.fromLngLat(it.first, it.second) })
            s.getSourceAs<GeoJsonSource>("route-src")?.setGeoJson(Feature.fromGeometry(line))
        }
        selected?.let {
            s.getSourceAs<GeoJsonSource>("dest-src")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)))
        }
        if (hasLocationPermission()) enableLocation()
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
            json = json.replace("__PMTILES__", "pmtiles://file://" + tiles.absolutePath)
            json = json.replace("__FONTS__", "file://" + OfflineData.effectiveFontsDir(this, pkg).absolutePath)

            if (base != null) {
                val baseTiles = OfflineData.tilesFile(base)
                if (baseTiles.isFile) {
                    val o = org.json.JSONObject(json)
                    val sources = o.getJSONObject("sources")
                    // منبع پایه — همان قالب کاشی
                    val baseSrc = org.json.JSONObject(sources.getJSONObject("rahyab").toString())
                    baseSrc.put("tiles", org.json.JSONArray().put("pmtiles://file://" + baseTiles.absolutePath))
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
                            baseLayer.put("maxzoom", minOf(mz, 8))
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

    // ---------- چرخه حیات ----------
    override fun onStart() {
        super.onStart()
        mapView.onStart()
        NavigationController.addUiListener(uiListener)
        NavigationController.addFixListener(fixListener)
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
    }

    override fun onStop() {
        NavigationController.removeUiListener(uiListener)
        NavigationController.removeFixListener(fixListener)
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
