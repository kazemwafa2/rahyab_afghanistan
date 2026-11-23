package af.rahyab.app

/**
 * نرمال‌سازی متن دری/پشتو/انگلیسی برای جست‌وجو.
 *
 * همین قواعد دقیقاً در ابزار ساخت داده (tools/build_package.py) هم پیاده شده است،
 * پس اندیس ساخته‌شده و عبارت جست‌وجوی کاربر همیشه به یک شکل مقایسه می‌شوند.
 */
object TextNorm {

    /** نگاشت حروف عربی/فارسی متفاوت به شکل استاندارد دری */
    private val MAP: Map<Char, Char> = buildMap {
        // ی عربی → ی فارسی
        put('\u064A', '\u06CC')
        // ك عربی → ک فارسی
        put('\u0643', '\u06A9')
        // ة → ه
        put('\u0629', '\u0647')
        // همه شکل‌های الف → ا
        put('\u0623', '\u0627') // أ
        put('\u0625', '\u0627') // إ
        put('\u0671', '\u0627') // ٱ
        put('\u0622', '\u0627') // آ
        put('\u0672', '\u0627') // ٲ
        put('\u0673', '\u0627') // ٳ
        // ؤ → و
        put('\u0624', '\u0648')
        // ئ → ی
        put('\u0626', '\u06CC')
        // ۀ ۂ ہ ھ → ه
        put('\u06C0', '\u0647')
        put('\u06C2', '\u0647')
        put('\u06C1', '\u0647')
        put('\u06BE', '\u0647')
        // ں → ن
        put('\u06BA', '\u0646')
        // ھ دوتایی قبلاً پوشش داده شد
    }

    /** حروفی که کاملاً حذف می‌شوند (اعراب، کشیده، علامت‌های جهت) */
    private fun isDropped(c: Char): Boolean {
        val v = c.code
        return (v in 0x0610..0x061A) ||  // علامت‌های قرآنی
            (v in 0x064B..0x065F) ||  // اعراب
            v == 0x0670 ||             // superscript alef
            (v in 0x06D6..0x06ED) ||  // علامت‌های تلاوت
            v == 0x0640 ||             // ـ کشیده
            (v in 0x200B..0x200F) ||  // ZWSP، RLM و…
            v == 0x00AD || v == 0xFEFF
    }

    /** نرمال‌سازی برای مقایسه: نگاشت حروف، حذف اعراب، تبدیل ارقام، فاصله یکدست */
    fun norm(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length)
        for (ch0 in input) {
            var c = ch0
            if (isDropped(c)) continue
            if (c == '\u200C' || c == '\u200D') { // نیم‌فاصله → فاصله
                sb.append(' '); continue
            }
            MAP[c]?.let { c = it }
            // ارقام فارسی و عربی → ارقام انگلیسی
            if (c in '\u06F0'..'\u06F9') c = (c.code - 0x06F0 + '0'.code).toChar()
            if (c in '\u0660'..'\u0669') c = (c.code - 0x0660 + '0'.code).toChar()
            // حروف بزرگ انگلیسی → کوچک
            if (c in 'A'..'Z') c = (c.code + 32).toChar()
            // هر چیز غیر حرف/رقم → فاصله
            sb.append(if (c.isLetterOrDigit() || c == ' ') c else ' ')
        }
        // فاصله‌های تکراری را یکی کن
        val out = StringBuilder(sb.length)
        var prevSpace = true
        for (c in sb) {
            if (c == ' ') { if (!prevSpace) { out.append(' '); prevSpace = true } }
            else { out.append(c); prevSpace = false }
        }
        if (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
        return out.toString()
    }
}
