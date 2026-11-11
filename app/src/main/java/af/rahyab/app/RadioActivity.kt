package af.rahyab.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * صفحه رادیو افغانستان:
 *  - دسته‌بندی: همه / دری و فارسی / پشتو / انگلیسی و سایر
 *  - فهرست ایستگاه‌ها: تأییدشده‌های داخل برنامه + ایستگاه‌های زنده Radio Browser (با انترنت)
 *  - پخش/توقف، تغییر ایستگاه، پخش در پس‌زمینه (RadioService)، نام ایستگاه
 *  - مدیریت قطع انترنت: خطا با پیام مناسب اعلام می‌شود؛ تلاش دوباره با لمس کاربر
 */
class RadioActivity : AppCompatActivity() {

    private lateinit var lvStations: ListView
    private lateinit var tvStatus: TextView
    private lateinit var tvPlaying: TextView
    private var stations: List<RadioModule.Station> = emptyList()
    private var cat = "all"
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radio)

        val root = findViewById<View>(R.id.radioRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        lvStations = findViewById(R.id.lvStations)
        tvStatus = findViewById(R.id.tvRadioStatus)
        tvPlaying = findViewById(R.id.tvPlaying)

        findViewById<View>(R.id.btnRadioClose).setOnClickListener { finish() }
        findViewById<View>(R.id.tabAll).setOnClickListener { setCategory("all") }
        findViewById<View>(R.id.tabDari).setOnClickListener { setCategory("dari") }
        findViewById<View>(R.id.tabPashto).setOnClickListener { setCategory("pashto") }
        findViewById<View>(R.id.tabOther).setOnClickListener { setCategory("other") }
        findViewById<View>(R.id.btnRadioStop).setOnClickListener { stopRadio() }

        // وضعیت پخش از سرویس
        RadioService.stateListener = { name, playing ->
            runOnUiThread {
                if (name == null) {
                    tvPlaying.visibility = View.GONE
                } else {
                    tvPlaying.text = if (playing) getString(R.string.radio_now_playing, name)
                    else getString(R.string.radio_connecting, name)
                    tvPlaying.visibility = View.VISIBLE
                }
                if (loading) { loading = false; tvStatus.visibility = View.GONE }
            }
        }

        loadStations()
        styleCategoryTabs()
    }

    private fun setCategory(c: String) {
        cat = c
        styleCategoryTabs()
        renderList()
    }

    private fun styleCategoryTabs() {
        val ids = mapOf("all" to R.id.tabAll, "dari" to R.id.tabDari, "pashto" to R.id.tabPashto, "other" to R.id.tabOther)
        for ((k, id) in ids) {
            val v = findViewById<View>(id)
            v.setBackgroundResource(if (cat == k) R.drawable.bg_tab_sel else R.drawable.bg_tab)
        }
    }

    /** بارگذاری فهرست: اول تأییدشده‌ها (بدون انترنت)؛ سپس با انترنت فهرست زنده اضافه می‌شود */
    private fun loadStations() {
        stations = RadioModule.curated()
        renderList()
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.radio_loading_online)
        loading = true
        Thread {
            val live = try { RadioModule.fetchAll() } catch (e: Exception) { RadioModule.curated() }
            runOnUiThread {
                stations = live
                renderList()
                loading = false
                tvStatus.visibility = View.GONE
            }
        }.start()
    }

    private fun renderList() {
        val list = RadioModule.byCategory(stations, cat)
        tvStatus.visibility = if (loading) View.VISIBLE else View.GONE
        if (list.isEmpty()) {
            lvStations.adapter = SimpleAdapter(emptyList())
            toast(R.string.radio_empty_category)
            return
        }
        lvStations.adapter = SimpleAdapter(list)
    }

    /** فهرست ایستگاه‌ها: نام + برچسب تأییدشده/زنده */
    private inner class SimpleAdapter(private val list: List<RadioModule.Station>) :
        android.widget.BaseAdapter() {
        override fun getCount(): Int = list.size
        override fun getItem(position: Int): Any = list[position]
        override fun getItemId(position: Int): Long = position.toLong()

        @SuppressLint("InflateParams")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_station, parent, false)
            val s = list[position]
            v.findViewById<TextView>(R.id.tvStationName).text = s.name
            val badge = v.findViewById<TextView>(R.id.tvStationBadge)
            badge.text = getString(
                if (s.verified) R.string.radio_verified else R.string.radio_live
            )
            badge.setTextColor(
                if (s.verified) 0xFF2E9E4F.toInt() else 0xFF1976D2.toInt()
            )
            val playing = RadioService.isPlayingName == s.name
            v.findViewById<ImageView>(R.id.ivStationIcon).setImageResource(
                if (playing) R.drawable.ic_voice else R.drawable.ic_radio
            )
            v.alpha = if (playing) 1.0f else 0.95f
            return v
        }
    }

    override fun onPause() {
        super.onPause()
        if (::lvStations.isInitialized) lvStations.adapter = null
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun play(s: RadioModule.Station) {
        tvPlaying.text = getString(R.string.radio_connecting, s.name)
        tvPlaying.visibility = View.VISIBLE
        RadioService.play(this, s.name, s.url)
        renderList()
    }

    private fun stopRadio() {
        RadioService.stop(this)
        tvPlaying.visibility = View.GONE
        renderList()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
    }
}
