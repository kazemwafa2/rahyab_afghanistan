package af.rahyab.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * صفحه خوش‌آمد به سبک راه‌یاب:
 *   مرحله ۱: معرفی امکانات و دکمه «بزن بریم»
 *   مرحله ۲: توضیح شفاف اینکه چرا موقعیت دقیق لازم است (قبل از پرسش سیستمی)
 * فقط در نخستین اجرا نمایش داده می‌شود (پرچم welcomed در تنظیمات).
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var cardIntro: View
    private lateinit var cardPerm: View
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        cardIntro = findViewById(R.id.cardIntro)
        cardPerm = findViewById(R.id.cardPerm)

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
                finishFlow()
            } else {
                permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPermLater).setOnClickListener {
            finishFlow()
        }
    }

    private fun goPerm() {
        step = 1
        cardIntro.visibility = View.GONE
        cardPerm.visibility = View.VISIBLE
    }

    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        // چه اجازه داده شود چه نشود، کاربر به نقشه می‌رود؛ برنامه بدون مجوز هم کار می‌کند
        finishFlow()
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
        } else {
            super.onBackPressed()
        }
    }
}
