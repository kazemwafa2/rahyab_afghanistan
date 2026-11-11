package af.rahyab.app

/**
 * تبدیل ارقام به حروف برای موتور گفتار (TTS) — نسخه ۰٫۹٫۳
 *
 * مشکل: موتورهای متن‌به‌گفتار ارقام را به شیوه فارسی ایران می‌خوانند؛ مثلاً
 * «۲۰۰» را «دویست» می‌گویند، در حالی که در دری افغانستان «دوصد» گفته می‌شود.
 * راه‌حل: پیش از فرستادن متن به موتور گفتار، همه ارقام به حروف تبدیل می‌شوند —
 * به فارسی/دری افغانستان، پشتو یا انگلیسی، مطابق زبان برنامه.
 *
 * این تبدیل فقط برای «متن گفته‌شده» است؛ روی صفحه هنوز ارقام نمایش داده می‌شوند.
 */
object TtsText {

    // ---------- واژه‌های دری (افغانستان) ----------
    private val faOnes = listOf("صفر", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
    private val faTeens = listOf("ده", "یازده", "دوازده", "سیزده", "چارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده")
    private val faTens = listOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    // نکته دری: ۲۰۰ = «دوصد» (نه «دویست» فارسی ایران)
    private val faHundreds = listOf("", "صد", "دوصد", "سیصد", "چهارصد", "پنجصد", "ششصد", "هفتصد", "هشتصد", "نهصد")

    // ---------- واژه‌های پشتو ----------
    private val psOnes = listOf("صفر", "یو", "دوه", "درې", "څلور", "پنځه", "شپږ", "اوه", "اته", "نهه")
    private val psTeens = listOf("لس", "یوولس", "دولس", "دیارلس", "څوارلس", "پنځلس", "شپاړلس", "اوه‌لس", "اتلس", "نولس")
    private val psTens = listOf("", "", "شل", "دېرش", "چهل", "پنځوس", "شپېتم", "اویا", "اتیا", "نوي")
    private val psHundreds = listOf("", "سل", "دوه سوه", "درې سوه", "څلور سوه", "پنځه سوه", "شپږ سوه", "اوه سوه", "اته سوه", "نهه سوه")

    // ---------- واژه‌های انگلیسی ----------
    private val enOnes = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val enTeens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val enTens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun wordsFa(n: Long): String {
        if (n < 0) return "منفی " + wordsFa(-n)
        if (n < 10) return faOnes[n.toInt()]
        if (n < 20) return faTeens[(n - 10).toInt()]
        if (n < 100) {
            val t = faTens[(n / 10).toInt()]
            val o = (n % 10).toInt()
            return if (o == 0) t else "$t و ${faOnes[o]}"
        }
        if (n < 1000) {
            val h = faHundreds[(n / 100).toInt()]
            val r = n % 100
            return if (r == 0L) h else "$h و ${wordsFa(r)}"
        }
        if (n < 1_000_000L) {
            val th = n / 1000
            val r = n % 1000
            val thw = if (th == 1L) "هزار" else wordsFa(th) + " هزار"
            return if (r == 0L) thw else "$thw و ${wordsFa(r)}"
        }
        val mi = n / 1_000_000L
        val r = n % 1_000_000L
        val miw = if (mi == 1L) "یک میلیون" else wordsFa(mi) + " میلیون"
        return if (r == 0L) miw else "$miw و ${wordsFa(r)}"
    }

    private fun wordsPs(n: Long): String {
        if (n < 0) return "منفي " + wordsPs(-n)
        if (n < 10) return psOnes[n.toInt()]
        if (n < 20) return psTeens[(n - 10).toInt()]
        if (n < 100) {
            val t = psTens[(n / 10).toInt()]
            val o = (n % 10).toInt()
            return if (o == 0) t else "$t او ${psOnes[o]}"
        }
        if (n < 1000) {
            val h = psHundreds[(n / 100).toInt()]
            val r = n % 100
            return if (r == 0L) h else "$h او ${wordsPs(r)}"
        }
        if (n < 1_000_000L) {
            val th = n / 1000
            val r = n % 1000
            val thw = if (th == 1L) "زره" else wordsPs(th) + " زره"
            return if (r == 0L) thw else "$thw او ${wordsPs(r)}"
        }
        val mi = n / 1_000_000L
        val r = n % 1_000_000L
        val miw = if (mi == 1L) "یو میلیون" else wordsPs(mi) + " میلیون"
        return if (r == 0L) miw else "$miw او ${wordsPs(r)}"
    }

    private fun wordsEn(n: Long): String {
        if (n < 0) return "minus " + wordsEn(-n)
        if (n < 10) return enOnes[n.toInt()]
        if (n < 20) return enTeens[(n - 10).toInt()]
        if (n < 100) {
            val t = enTens[(n / 10).toInt()]
            val o = (n % 10).toInt()
            return if (o == 0) t else "$t-${enOnes[o]}"
        }
        if (n < 1000) {
            val h = enOnes[(n / 100).toInt()] + " hundred"
            val r = n % 100
            return if (r == 0L) h else "$h and ${wordsEn(r)}"
        }
        if (n < 1_000_000L) {
            val th = n / 1000
            val r = n % 1000
            val thw = wordsEn(th) + " thousand"
            return if (r == 0L) thw else "$thw ${wordsEn(r)}"
        }
        val mi = n / 1_000_000L
        val r = n % 1_000_000L
        val miw = wordsEn(mi) + " million"
        return if (r == 0L) miw else "$miw ${wordsEn(r)}"
    }

    private fun words(n: Long, lang: String): String = when (lang) {
        "ps" -> wordsPs(n)
        "en" -> wordsEn(n)
        else -> wordsFa(n)
    }

    /**
     * متن را برای موتور گفتار آماده می‌کند:
     *  - ارقام لاتین/فارسی/عربی → حروف (۲۰۰ → «دوصد»)
     *  - اعشار: ۲٫۵ → «دو و نیم» (دری) / «دوه اعشاریه پنځه» (پشتو) / «two point five» (انگلیسی)
     */
    fun forTts(text: String, lang: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length + 32)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            // ارقام لاتین، فارسی و عربی
            val isDigit = c in '0'..'9' || c in '\u06F0'..'\u06F9' || c in '\u0660'..'\u0669'
            if (!isDigit) { sb.append(c); i++; continue }
            // جمع‌کردن عدد (ارقام متوالی — حتی ترکیبی لاتین/فارسی)
            var intPart = 0L
            var hasInt = false
            while (i < n) {
                val ch = text[i]
                val d = when {
                    ch in '0'..'9' -> ch - '0'
                    ch in '\u06F0'..'\u06F9' -> ch - '\u06F0'
                    ch in '\u0660'..'\u0669' -> ch - '\u0660'
                    else -> -1
                }
                if (d < 0) break
                intPart = intPart * 10 + d
                hasInt = true
                i++
            }
            // اعشار؟ (نقطه یا ممیز فارسی/عربی ٫ و ٬)
            val sep = if (i < n) text[i] else ' '
            if (sep == '.' || sep == '\u066B' || sep == '\u066C') {
                    var fracDigits = ""
                    var j = i + 1
                    while (j < n) {
                        val ch = text[j]
                        val d = when {
                            ch in '0'..'9' -> ch - '0'
                            ch in '\u06F0'..'\u06F9' -> ch - '\u06F0'
                            ch in '\u0660'..'\u0669' -> ch - '\u0660'
                            else -> -1
                        }
                        if (d < 0) break
                        fracDigits += d
                        j++
                    }
                    if (fracDigits.isNotEmpty()) {
                        // ۲٫۵ → «دو و نیم» در دری؛ سایر حالات به شکل رسمی
                        val half = (fracDigits == "5")
                        when (lang) {
                            "en" -> {
                                sb.append(words(intPart, lang)).append(" point")
                                for (d in fracDigits) sb.append(' ').append(enOnes[d - '0'])
                            }
                            "ps" -> {
                                sb.append(words(intPart, lang)).append(" اعشاریه")
                                for (d in fracDigits) sb.append(' ').append(psOnes[d - '0'])
                            }
                            else -> {
                                if (half && fracDigits.length == 1) sb.append(words(intPart, lang)).append(" و نیم")
                                else {
                                    sb.append(words(intPart, lang)).append(" ممیز")
                                    for (d in fracDigits) sb.append(' ').append(faOnes[d - '0'])
                                }
                            }
                        }
                        i = j
                        continue
                    }
            }
            if (hasInt) sb.append(words(intPart, lang)) else sb.append(c)
        }
        return sb.toString()
    }
}
