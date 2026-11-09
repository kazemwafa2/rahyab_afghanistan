package af.rahyab.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * راهنمای صوتی بدون سرور.
 * ۱) اگر فایل‌های صوتی ضبط‌شده دری در assets/voice/dari/ باشند، آن‌ها پخش می‌شوند (بهترین کیفیت).
 * ۲) در غیر این صورت از متن‌به‌گفتار خود اندروید استفاده می‌شود (فارسی/پشتو/انگلیسی، هر چه روی گوشی نصب باشد).
 */
class VoiceGuide(private val ctx: Context, private val onNoVoice: () -> Unit) : TextToSpeech.OnInitListener {

    var enabled = true
    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    private var ttsOk = false
    private var curLang = ""
    private var player: MediaPlayer? = null
    private var warned = false
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val clips: Set<String> = try {
        ctx.assets.list("voice/dari")?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }

    override fun onInit(status: Int) {
        tts?.setAudioAttributes(attrs)
        if (status != TextToSpeech.SUCCESS) { tts = null }
    }

    private fun setLangFor(appLang: String): Boolean {
        val t = tts ?: return false
        val cands = when (appLang) {
            "en" -> listOf(Locale.ENGLISH)
            "ps" -> listOf(Locale("ps"), Locale("fa", "AF"), Locale("fa", "IR"), Locale("fa"))
            else -> listOf(Locale("fa", "AF"), Locale("fa", "IR"), Locale("fa"))
        }
        for (l in cands) {
            val r = t.setLanguage(l)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return true
        }
        return false
    }

    private fun clipFile(name: String): String? = when {
        clips.contains("$name.mp3") -> "$name.mp3"
        clips.contains("$name.ogg") -> "$name.ogg"
        else -> null
    }

    /** clipNames: نام فایل‌های ضبط‌شده به ترتیب پخش؛ text: جمله کامل برای متن‌به‌گفتار */
    fun speak(text: String, clipNames: List<String> = emptyList()) {
        if (!enabled) return
        val appLang = ctx.resources.configuration.locales[0].language
        if (appLang == "fa" || appLang == "ps") {
            val files = clipNames.map { clipFile(it) }
            if (clipNames.isNotEmpty() && files.all { it != null }) {
                play(files.filterNotNull()); return
            }
        }
        val t = tts
        if (t == null) { warn(); return }
        if (appLang != curLang) { ttsOk = setLangFor(appLang); curLang = appLang }
        if (!ttsOk) { warn(); return }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    private fun warn() {
        if (!warned) { warned = true; onNoVoice() }
    }

    private fun play(files: List<String>) {
        player?.release(); player = null
        var i = 0
        fun next() {
            if (i >= files.size) return
            val f = files[i++]
            try {
                val afd = ctx.assets.openFd("voice/dari/$f")
                val m = MediaPlayer()
                m.setAudioAttributes(attrs)
                m.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                m.setOnPreparedListener { it.start() }
                m.setOnCompletionListener { it.release(); next() }
                m.prepareAsync()
                player = m
            } catch (e: Exception) { }
        }
        next()
    }

    fun stop() { tts?.stop(); player?.release(); player = null }

    fun shutdown() { stop(); tts?.shutdown(); tts = null }
}
