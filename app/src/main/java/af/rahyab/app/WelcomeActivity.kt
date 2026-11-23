package af.rahyab.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * صفحه خوش‌آمد به سبک راه‌یاب:
 *   مرحله ۱: معرفی امکانات و دکمه «بزن بریم»
 *   مرحله ۲: توضیح شفاف اینکه چرا موقعیت دقیق لازم است (قبل از پرسش سیستمی)
 *   مرحله ۳ (سند جامع — بخش ۴): بررسی روشن‌بودن GPS؛ اگر خاموش باشد،
 *     توضیح ضرورت آن نمایش داده می‌شود و با تأیید کاربر، صفحهٔ رسمی
 *     اندروید برای روشن‌کردن Location باز می‌شود. پس از بازگشت، وضعیت
 *     دوباره بررسی می‌شود؛ اگر روشن شده بود جریان ادامه می‌یابد و اگر
 *     خاموش ماند، راهنما دوباره نمایش داده می‌شود (با گزینهٔ «بعداً»).
 *
 * فقط در نخستین اجرا نمایش داده می‌شود (پرچم welcomed در تنظیمات).
 * نکتهٔ فنی: اپلیکیشن عادی اندروید نمی‌تواند GPS را مخفیانه روشن کند؛
 * همین مسیر رسمی `ACTION_LOCATION_SOURCE_SETTINGS` استفاده می‌شود.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var cardIntro: View
    private lateinit var cardPerm: View
    private lateinit var cardGps: View
    private lateinit var gpsButtons: View
    private lateinit var tvGpsState: View
    private lateinit var btnGpsOpen: View
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        cardIntro = findViewById(R.id.cardIntro)
        cardPerm = findViewById(R.id.cardPerm)
        cardGps = findViewById(R.id.cardGps)
        gpsButtons = findViewById(R.id.gpsButtons)
        tvGpsState = findViewById(R.id.tvGpsState)
        btnGpsOpen = findViewById(R.id.btnGpsOpen)

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnWhat).setOnClickListener {
            val msg = listOf(R.string.feature_map, R.string.feature_route, R.string.feature_search, R.string.feature_voice)
                .joinToString("\n\n") { "✓ " + getString(it) }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.welcome_what)
                .setMessage(msg)
                .setPositiveButton(R.string.welcome_go) { _, _ -> goPerm() }
                .setNegativeButton(R.string.close, null)
                .show()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGo).setOnClickListener { goPerm() }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPermContinue).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                goGps()
            } else {
                permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPermLater).setOnClickListener {
            finishFlow()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGpsOpen).setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (e2: Exception) { }
            }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGpsLater).setOnClickListener {
            finishFlow()
        }
    }

    private fun goPerm() {
        step = 1
        cardIntro.visibility = View.GONE
        cardPerm.visibility = View.VISIBLE
    }

    /** مرحلهٔ ۳: بررسی GPS — اگر روشن بود مستقیم پایان جریان، وگرنه کارت راهنما */
    private fun goGps() {
        if (isLocationOn()) { finishFlow(); return }
        step = 2
        cardPerm.visibility = View.GONE
        cardGps.visibility = View.VISIBLE
        gpsButtons.visibility = View.VISIBLE
        refreshGpsCard()
    }

    private fun isLocationOn(): Boolean {
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { true }
    }

    private fun refreshGpsCard() {
        val on = isLocationOn()
        tvGpsState.visibility = if (on) View.VISIBLE else View.GONE
        btnGpsOpen.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** پس از بازگشت از تنظیمات اندروید، وضعیت GPS فوراً دوباره بررسی می‌شود */
    override fun onResume() {
        super.onResume()
        if (step == 2) {
            refreshGpsCard()
            // اگر کاربر موقعیت مکانی را روشن کرده بود، بدون بستن برنامه ادامه می‌دهیم
            if (isLocationOn()) finishFlow()
        }
    }

    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        // چه اجازه داده شود چه نشود، کاربر به مرحلهٔ بعد می‌رود؛ برنامه بدون مجوز هم کار می‌کند
        goGps()
    }

    private fun finishFlow() {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("welcomed", true).apply()
        finish()
    }

    override fun onBackPressed() {
        if (step == 1) {
            step = 0
            cardPerm.visibility = View.GONE
            cardIntro.visibility = View.VISIBLE
        } else if (step == 2) {
            // راهنمای GPS مزاحم نیست؛ کاربر می‌تواند بعداً از تنظیمات برنامه فعال کند
            finishFlow()
        } else {
            super.onBackPressed()
        }
    }
}
