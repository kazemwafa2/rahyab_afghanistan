package af.rahyab.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.LinkedList
import java.util.Locale

/**
 * راهنمای صوتی برنامه — نسخه ۰٫۹٫۳ با صداهای چندزبانه (دری، پشتو، انگلیسی).
 *
 * تغییرهای مهم نسبت به نسخه‌های قبل:
 *  ۱. زبان راهنمای صوتی **همیشه از زبان خود برنامه** گرفته می‌شود، نه از زبان گوشی.
 *     اگر زبان برنامه پشتو باشد پشتو صحبت می‌کند، اگر انگلیسی باشد انگلیسی و
 *     اگر دری باشد دری — مستقل از این که گوشی به چه زبانی تنظیم شده باشد.
 *  ۲. صداهای ضبط‌شده برای هر سه زبان داخل برنامه هست: دری (امیر، دلارا، فرید،
 *     همایون، نگار، عطیه، بانو، مرد)، پشتو (گل‌نواز، لطیفه) و انگلیسی (اریا، دیوید).
 *     هر بسته ۲۹ پیام کامل دارد و همیشه قابل انتخاب و پیش‌نمایش است.
 *  ۳. ارقام پیش از گفتن به حروف تبدیل می‌شوند (TtsText) تا موتور گوشی
 *     «۲۰۰» را «دوصد» بگوید نه «دویست» — کاملاً دری افغانستان.
 *  ۴. زبان موتور گفتار گوشی هم مطابق زبان برنامه تنظیم می‌شود
 *     (دری → fa، پشتو → ps، انگلیسی → en).
 */
class VoiceGuide(private val ctx: Context, private val onNoVoice: () -> Unit) :
    TextToSpeech.OnInitListener {

    /** یک بسته صدای ضبط‌شده */
    data class PackInfo(
        val id: String,
        val name: String,
        val gender: String,   // female / male
        val lang: String,     // fa / ps / en
        val clips: Int
    )

    /** زبان برنامه: «fa» (دری/فارسی)، «ps» (پشتو) یا «en» (انگلیسی) — از تنظیم زبان خود برنامه */
    fun appLang(): String {
        val tags: LocaleListCompat = AppCompatDelegate.getApplicationLocales()
        val tag = if (!tags.isEmpty) tags.toLanguageTags() else
            ctx.resources.configuration.locales[0].toLanguageTag()
        return langOfTag(tag)
    }

    private fun langOfTag(tag: String): String = when {
        tag.startsWith("ps", ignoreCase = true) -> "ps"
        tag.startsWith("en", ignoreCase = true) -> "en"
        else -> "fa"
    }

    var enabled = true

    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var ttsReady = false
    private var warned = false
    private var warnedLang: String? = null
    private var curEnginePkg: String? = null
    private var curLang: Locale? = null
    private val pending = LinkedList<Pair<String, List<String>>>()
    private val lock = Any()

    private var player: MediaPlayer? = null
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** آخرین وضعیت صدا برای نمایش در تنظیمات */
    var status: String = ""
        private set
    private var voiceLang: Locale? = null

    // ---------- تمرکز صوتی ----------
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private var callWatcher: PhoneCallWatcher? = null

    init {
        enabled = prefs.getBoolean("voice", true)
        if (!speakDuringCalls) watchCalls()
    }

    // ---------- بسته‌های صدای ضبط‌شده ----------

    private val packDirs: List<String> = try {
        ctx.assets.list("voice")?.filter { it != "dari-legacy" }?.sorted() ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private val clipsCache = HashMap<String, Set<String>>()
    private val metaCache = HashMap<String, org.json.JSONObject>()

    private fun clipsFor(pack: String): Set<String> = clipsCache.getOrPut(pack) {
        try { ctx.assets.list("voice/$pack")?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
    }

    private fun metaFor(pack: String): org.json.JSONObject = metaCache.getOrPut(pack) {
        try {
            org.json.JSONObject(
                ctx.assets.open("voice/$pack/meta.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) { org.json.JSONObject() }
    }

    private fun packLangOf(pack: String): String =
        langOfTag(try { metaFor(pack).optString("lang", "fa-AF") } catch (e: Exception) { "fa-AF" })

    /** شمار فایل‌های گفتاری بسته (بدون زنگ) */
    private fun speechClips(pack: String): Int =
        clipsFor(pack).count { it != "chime.ogg" && (it.endsWith(".ogg") || it.endsWith(".wav") || it.endsWith(".mp3")) }

    /** فهرست همه صداهای ضبط‌شده داخل برنامه (برای صفحه انتخاب صدا) */
    fun packs(): List<PackInfo> {
        val out = ArrayList<PackInfo>()
        for (dir in packDirs) {
            val n = speechClips(dir)
            if (n < 5) continue // پوشه بدون بسته معتبر
            val name = try { metaFor(dir).optString("name", dir) } catch (e: Exception) { dir }
            val gender = try { metaFor(dir).optString("gender", "") } catch (e: Exception) { "" }
            out.add(PackInfo(dir, name, gender, packLangOf(dir), n))
        }
        return out
    }

    /** صداهای یک زبان مشخص (fa / ps / en) */
    fun packsFor(lang: String): List<PackInfo> = packs().filter { it.lang == lang }

    /** تعداد عبارت‌های بسته (برای نمایش در تنظیمات) */
    fun packClipCount(pack: String): Int = speechClips(pack)

    /** زبان بسته صدای انتخابی فعلی */
    fun packLang(): String {
        val p = packs().firstOrNull { it.id == packId }
        return p?.lang ?: appLang()
    }

    /**
     * بسته صدای انتخابی کاربر — برای هر زبان برنامه جداگانه ذخیره می‌شود
     * (voice_pack_fa / voice_pack_ps / voice_pack_en) تا با تغییر زبان برنامه،
     * صدای همان زبان — که آخرین بار کاربر انتخاب کرده — پخش شود.
     */
    var packId: String
        get() {
            val lang = appLang()
            val saved = prefs.getString("voice_pack_$lang", null)
            if (saved != null && packsFor(lang).any { it.id == saved }) return saved
            // مهاجرت از نسخه‌های قدیمی (یک کلید مشترک)
            if (lang == "fa") {
                val old = prefs.getString("voice_pack", null)
                if (old != null && packsFor("fa").any { it.id == old }) return old
            }
            return defaultPackId(lang)
        }
        set(v) { prefs.edit().putString("voice_pack_${appLang()}", v).apply() }

    private fun defaultPackId(lang: String): String =
        packsFor(lang).firstOrNull()?.id ?: when (lang) {
            "ps" -> "gulnawaz"
            "en" -> "aria"
            else -> "dari"
        }

    /** آیا فایل‌های صدای بسته انتخابی موجودند؟ */
    fun packAvailable(): Boolean = packClipCount(packId) >= 5

    /** تعداد عبارت‌های صوتی بسته فعال (برای نمایش در تنظیمات) */
    fun packClipCount(): Int = packClipCount(packId)

    /** منبع صدا: «pack» = صدای داخل برنامه (پیش‌فرض)، «system» = موتور گوشی */
    private fun source(): String = prefs.getString("voice_source", "pack") ?: "pack"

    /** بلندی صدا ۰٫۰ تا ۱٫۰ از تنظیمات */
    fun volumeLevel(): Float = prefs.getInt("voice_vol", 85).coerceIn(0, 100) / 100f

    /** سرعت گفتار ۰٫۸ تا ۱٫۴ از تنظیمات */
    fun speechRate(): Float = 0.8f + 0.6f * prefs.getInt("voice_speed", 40).coerceIn(0, 100) / 100f

    /** پخش از طریق بلوتوث (خاموش = فقط بلندگوی گوشی) */
    var bluetoothAllowed: Boolean
        get() = prefs.getBoolean("voice_bt", true)
        set(v) { prefs.edit().putBoolean("voice_bt", v).apply() }

    /** هنگام مکالمه تیلفون هم صحبت کند؟ */
    var speakDuringCalls: Boolean
        get() = prefs.getBoolean("voice_call", false)
        set(v) {
            prefs.edit().putBoolean("voice_call", v).apply()
            if (!v) watchCalls() else stopCallWatch()
        }

    /** آیا بسته انتخابی از زبان برنامه است؟ در این صورت پیام‌ها از فایل‌های ضبط‌شده پخش می‌شوند */
    private fun usePackClips(): Boolean =
        source() == "pack" && packAvailable() && packLang() == appLang()

    private fun clipFile(pack: String, name: String): String? = when {
        clipsFor(pack).contains("$name.ogg") -> "$name.ogg"
        clipsFor(pack).contains("$name.wav") -> "$name.wav"
        clipsFor(pack).contains("$name.mp3") -> "$name.mp3"
        else -> null
    }

    private fun clipFile(name: String): String? = clipFile(packId, name)

    // ---------- پخش ----------

    /**
     * clipNames: نام فایل‌های ضبط‌شده به ترتیب پخش؛ text: جمله کامل برای موتور سیستم.
     * text همیشه به پخش‌کننده داده می‌شود تا اگر فایل ضبط‌شده به هر دلیل پخش نشد
     * (خرابی، عدم پشتیبانی دستگاه)، جمله با موتور گفتار گوشی پخش شود — هیچ‌گاه بی‌صدا نمی‌مانیم.
     */
    fun speak(text: String, clipNames: List<String> = emptyList()) {
        if (!enabled) return
        if (!speakDuringCalls && inCall()) return // حین مکالمه صحبت نکند (قابل تنظیم)
        if (usePackClips() && clipNames.isNotEmpty()) {
            val files = clipNames.map { clipFile(it) }
            if (files.all { it != null }) { play(files.filterNotNull(), packId, text); return }
        }
        // بسته انتخابی از زبان برنامه است ولی این عبارت در آن ضبط نشده:
        // برای همگون‌ماندن صدا، این عبارت با صدای ضبط‌شده گفته نمی‌شود (فقط دری)
        if (source() == "pack" && packAvailable() && packLang() == appLang() && appLang() == "fa") {
            return
        }
        speakText(text)
    }

    /** گفتن متن با موتور گفتار گوشی — زبان مطابق زبان برنامه، ارقام به حروف دری/پشتو/انگلیسی */
    private fun speakText(text: String) {
        val ready = synchronized(lock) { ttsReady && ttsOk }
        if (!ready) {
            ensureTts()
            synchronized(lock) {
                if (!ttsReady && pending.size < 6) pending.addLast(Pair(text, emptyList()))
            }
            return
        }
        speakNow(text)
    }

    /**
     * هشدار صوتی: زنگ کوتاه + جمله ضبط‌شده. اگر بسته انتخابی جمله/زنگ را نداشته باشد
     * از بسته نخست همان زبان برنامه استفاده می‌شود.
     */
    fun speakAlert(key: String, text: String) {
        if (!enabled) return
        if (!speakDuringCalls && inCall()) return
        if (source() == "pack") {
            val lang = appLang()
            val fallback = packsFor(lang).firstOrNull()?.id ?: "dari"
            val chime = clipFile(packId, "chime") ?: clipFile(fallback, "chime")
            val clip = clipFile(packId, key) ?: clipFile(fallback, key)
            if (clip != null) {
                val fromSelected = clipFile(packId, key) != null
                val chimePack = if (clipFile(packId, "chime") != null) packId else fallback
                val seq = ArrayList<Pair<String, String>>()
                if (chime != null) seq.add(chimePack to chime)
                seq.add(Pair(if (fromSelected) packId else fallback, clip))
                playMulti(seq, text)
                return
            }
        }
        speak(text)
    }

    private fun ensureTts() {
        if (tts != null || ttsReady) return
        try {
            tts = TextToSpeech(ctx, this)
        } catch (e: Exception) {
            synchronized(lock) { ttsReady = true; ttsOk = false }
        }
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            synchronized(lock) { ttsReady = true; ttsOk = false }
            if (curEnginePkg == null) tryOtherEngines() else reportNoVoice()
            return
        }
        try {
            t.setAudioAttributes(attrs)
        } catch (e: Exception) { }
        synchronized(lock) { ttsReady = true }
        if (setupLanguage()) {
            synchronized(lock) { ttsOk = true }
            try { t.setSpeechRate(speechRate()) } catch (e: Exception) { }
            flushPending()
        } else {
            if (curEnginePkg == null) tryOtherEngines() else reportNoVoice()
        }
    }

    /** اگر موتور فعلی زبان برنامه را نداشت، دیگر موتورهای نصب‌شده را می‌آزماید */
    private fun tryOtherEngines() {
        val engines = try { tts?.engines ?: emptyList() } catch (e: Exception) { emptyList<TextToSpeech.EngineInfo>() }
        for (e in engines) {
            val pkg = e.name ?: continue
            if (pkg.isBlank()) continue
            if (pkg == curEnginePkg) continue
            prefs.edit().putString("tts_engine", pkg).apply()
            startEngine(pkg)
            return
        }
        prefs.edit().putString("tts_engine", null).apply()
        reportNoVoice()
    }

    private fun startEngine(pkg: String?) {
        synchronized(lock) { ttsReady = false; ttsOk = false }
        try { tts?.shutdown() } catch (e: Exception) { }
        tts = null
        curEnginePkg = pkg
        try {
            tts = if (pkg.isNullOrBlank()) TextToSpeech(ctx, this) else TextToSpeech(ctx, this, pkg)
        } catch (e: Exception) {
            synchronized(lock) { ttsReady = true; ttsOk = false }
        }
        if (tts == null) reportNoVoice()
    }

    /**
     * برقراری زبان روی موتور سیستم — مطابق زبان برنامه (مستقل از زبان گوشی):
     * دری → fa (افغانستان/ایران)، پشتو → ps، انگلیسی → en
     */
    private fun setupLanguage(): Boolean {
        val t = tts ?: return false
        val cands = when (appLang()) {
            "ps" -> listOf(Locale("ps", "AF"), Locale("ps"), Locale("fa", "AF"), Locale("fa"))
            "en" -> listOf(Locale("en", "US"), Locale("en", "GB"), Locale("en"))
            else -> listOf(Locale("fa", "AF"), Locale("fa", "IR"), Locale("fa"))
        }
        for (l in cands) {
            val r = try { t.setLanguage(l) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                voiceLang = l
                status = "voice_ok:" + l.toString()
                prefs.edit().putBoolean("voice_ok_last", true).apply()
                return true
            }
        }
        voiceLang = null
        status = "no_voice_for_" + appLang()
        prefs.edit().putBoolean("voice_ok_last", false).apply()
        return false
    }

    /** آیا صدای گفتار موجود است؟ بسته داخل برنامه همیشه کافی است */
    fun hasVoice(): Boolean {
        if (packAvailable()) return true
        synchronized(lock) { if (!ttsReady) return prefs.getBoolean("voice_ok_last", true) }
        return ttsOk
    }

    /** سازگاری با کد قبلی */
    fun hasPersianVoice(): Boolean = hasVoice()

    private fun reportNoVoice() {
        prefs.edit().putBoolean("voice_ok_last", false).apply()
        // برای هر تغییر زبان، یک‌بار هشدار بده (نه برای همیشه)
        if (!warned || warnedLang != appLang()) {
            warned = true; warnedLang = appLang()
            onNoVoice()
        }
    }

    private fun speakNow(text: String) {
        val t = tts ?: run { ensureTts(); return }
        val ok = if (ttsOk) true else setupLanguage()
        if (!ok) { reportNoVoice(); return }
        if (!gainFocus()) return
        try {
            try { t.setSpeechRate(speechRate()) } catch (e: Exception) { }
            // ارقام به حروف — تلفظ کاملاً دری/پشتو/انگلیسی (دوصد، نه دویست)
            val spoken = TtsText.forTts(text, appLang())
            val id = "rah-${System.currentTimeMillis()}"
            t.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: Exception) {
            android.util.Log.w("VoiceGuide", "پخش صوتی ناموفق", e)
        }
    }

    private fun flushPending() {
        synchronized(lock) {
            while (pending.isNotEmpty()) {
                val (text, _) = pending.pollFirst()
                speakNow(text)
                if (pending.isNotEmpty()) Thread.sleep(150)
            }
        }
    }

    /** جمله آزمایشی مطابق زبان برنامه (دکمه «آزمایش صدا») */
    fun testVoice(): Boolean {
        if (!enabled) return false
        val sentence = when (appLang()) {
            "ps" -> "غږیز لارښود راحیاب چمتو دی."
            "en" -> "Rahyab voice guidance is ready."
            else -> "راهنمای صوتی راه‌یاب آماده است."
        }
        if (usePackClips()) {
            val f = clipFile("test")
            if (f != null) { play(listOf(f), packId, sentence); return true }
        }
        if (source() == "pack" && packAvailable() && packLang() == appLang()) {
            val f = clipFile("test")
            if (f != null) { play(listOf(f), packId, sentence); return true }
        }
        speakText(sentence)
        return hasVoice()
    }

    /**
     * پیش‌نمایش: مستقل از کلید سخنگو، صدای همان بسته پخش می‌شود (دکمه پخش/آزمایش).
     * اگر فایل به هر دلیل قابل پخش نبود، fallbackText با موتور گفتار گوشی
     * (به زبان همان بسته) پخش می‌شود تا کاربر همیشه یک صدا بشنود.
     */
    fun previewPack(pack: String, fallbackText: String? = null): Boolean {
        val f = clipFile(pack, "test") ?: clipFile(pack, "depart")
        if (f != null) { play(listOf(f), pack, fallbackText); return true }
        if (fallbackText != null) speakText(fallbackText)
        return false
    }

    // ---------- موتور سیستم: ابزارهای کمکی برای تنظیمات ----------

    /** گفت‌وگوی رسمی اندروید برای نصب داده صدای موتور گوشی */
    fun installVoiceDataIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)

    /** صفحه تنظیمات متن‌به‌گفتار گوشی */
    fun ttsSettingsIntent(): Intent =
        Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** جست‌وجوی فروشگاه برای موتور رایگان eSpeak NG (صدای آفلاین فارسی/پشتو) */
    fun espeakStoreIntent(): Intent =
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
            "https://play.google.com/store/search?q=eSpeak%20NG%20text%20to%20speech&c=apps"
        ))

    // ---------- تمرکز صوتی ----------
    private fun gainFocus(): Boolean {
        if (hasFocus) return true
        return try {
            val ok: Boolean
            if (Build.VERSION.SDK_INT >= 26) {
                val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).build()
                focusRequest = fr
                ok = audioManager.requestAudioFocus(fr) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                ok = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            hasFocus = ok
            ok
        } catch (e: Exception) { true }
    }

    private fun loseFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
        } catch (e: Exception) { }
        hasFocus = false
    }

    private fun watchCalls() {
        if (callWatcher != null) return
        try { callWatcher = PhoneCallWatcher(ctx) } catch (e: Exception) { callWatcher = null }
    }

    private fun stopCallWatch() { callWatcher?.stop(); callWatcher = null }

    private fun inCall(): Boolean = callWatcher?.inCall() ?: false

    // ---------- پخش‌کننده فایل صوتی ----------

    private fun play(files: List<String>, pack: String = packId, spokenText: String? = null) {
        stopPlayer()
        playQueue(files, 0, pack, spokenText)
    }

    /** پخش ترتیبی از چند بسته (مثلاً زنگ از بسته نخست + جمله از بسته انتخابی) */
    private fun playMulti(seq: List<Pair<String, String>>, spokenText: String? = null) {
        stopPlayer()
        playMultiQueue(seq, 0, spokenText)
    }

    private fun playQueue(files: List<String>, idx: Int, pack: String, spokenText: String?) {
        if (idx >= files.size) { loseFocus(); return }
        playOne(pack, files[idx], spokenText) {
            playQueue(files, idx + 1, pack, spokenText)
        }
    }

    private fun playMultiQueue(seq: List<Pair<String, String>>, idx: Int, spokenText: String?) {
        if (idx >= seq.size) { loseFocus(); return }
        val (pack, file) = seq[idx]
        playOne(pack, file, spokenText) {
            playMultiQueue(seq, idx + 1, spokenText)
        }
    }

    /**
     * پخش یک فایل بسته. اگر فایل باز/پخش نشد (خرابی یا عدم پشتیبانی دستگاه)
     * و spokenText داده شده باشد، جمله با موتور گفتار گوشی پخش می‌شود و
     * زنجیره پخش قطع می‌گردد تا صدای تکراری نشنویم.
     */
    private fun playOne(pack: String, file: String, spokenText: String? = null, onDone: () -> Unit) {
        try {
            val afd = ctx.assets.openFd("voice/$pack/$file")
            val m = MediaPlayer()
            m.setAudioAttributes(attrs)
            m.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            val vol = volumeLevel()
            m.setVolume(vol, vol)
            if (Build.VERSION.SDK_INT >= 23) {
                try { m.playbackParams = m.playbackParams.setSpeed(speechRate()) } catch (e: Exception) { }
            }
            if (!bluetoothAllowed) routeToSpeaker(m)
            if (!gainFocus()) { try { m.release() } catch (e: Exception) { }; onDone(); return }
            m.setOnPreparedListener { it.start() }
            m.setOnCompletionListener { mp ->
                try { mp.release() } catch (e: Exception) { }
                if (player === mp) player = null
                onDone()
            }
            m.setOnErrorListener { mp, _, _ ->
                try { mp.release() } catch (e: Exception) { }
                if (player === mp) player = null
                android.util.Log.w("VoiceGuide", "خطای پخش $pack/$file — رفتن به موتور گفتار")
                if (spokenText != null) speakText(spokenText) else onDone()
                true
            }
            m.prepareAsync()
            player = m
        } catch (e: Exception) {
            android.util.Log.w("VoiceGuide", "پخش $pack/$file ناموفق: ${e.message}")
            if (spokenText != null) speakText(spokenText) else onDone()
        }
    }

    /** اگر بلوتوث خاموش باشد صدا فقط از بلندگوی گوشی بیاید */
    private fun routeToSpeaker(m: MediaPlayer) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val devs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val speaker = devs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
            }
            if (speaker != null) m.setPreferredDevice(speaker)
        } catch (e: Exception) { }
    }

    private fun stopPlayer() {
        try { player?.release() } catch (e: Exception) { }
        player = null
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) { }
        stopPlayer()
        synchronized(lock) { pending.clear() }
        loseFocus()
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (e: Exception) { }
        tts = null
        stopCallWatch()
    }

    /** تماشای وضعیت مکالمه تیلفون (برای تنظیم «پخش در جریان مکالمه») */
    private class PhoneCallWatcher(context: Context) {
        private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        private var listening = false
        private var callState = android.telephony.TelephonyManager.CALL_STATE_IDLE
        private val listener = object : android.telephony.PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) { callState = state }
        }
        init { start() }
        fun start() {
            if (listening) return
            try { tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE); listening = true } catch (e: Exception) { }
        }
        fun stop() {
            if (!listening) return
            try { tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE) } catch (e: Exception) { }
            listening = false
        }
        fun inCall(): Boolean = callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
    }
}
