package af.rahyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** آزمون تبدیل ارقام به حروف برای موتور گفتار — تلفظ کاملاً دری/پشتو/انگلیسی */
class TtsTextTest {

    @Test
    fun `دری - دوصد نه دویست`() {
        assertEquals("دوصد", TtsText.forTts("200", "fa"))
        assertEquals("تا دوصد متر دیگر", TtsText.forTts("تا ۲۰۰ متر دیگر", "fa"))
        assertEquals("صد و پنج", TtsText.forTts("105", "fa"))
        assertEquals("دوصد و پانزده", TtsText.forTts("215", "fa"))
        assertEquals("چهل", TtsText.forTts("40", "fa"))
        assertEquals("پنجصد", TtsText.forTts("۵۰۰", "fa"))
        assertEquals("هزار و دوصد", TtsText.forTts("1200", "fa"))
        assertEquals("بیست و پنج هزار", TtsText.forTts("25000", "fa"))
        // ارقام عربی-فارسی هم پوشش داده می‌شوند
        assertEquals("نه", TtsText.forTts("۹", "fa"))
        assertEquals("نه", TtsText.forTts("٩", "fa"))
    }

    @Test
    fun `دری - اعشار`() {
        assertEquals("دو و نیم کیلومتر", TtsText.forTts("2.5 کیلومتر", "fa"))
        assertEquals("دو و نیم کیلومتر", TtsText.forTts("۲٫۵ کیلومتر", "fa"))
        assertEquals("سه ممیز دو کیلومتر", TtsText.forTts("3.2 کیلومتر", "fa"))
    }

    @Test
    fun `پشتو - دوه سوه`() {
        assertEquals("دوه سوه", TtsText.forTts("200", "ps"))
        assertEquals("تر دوه سوه متره", TtsText.forTts("تر ۲۰۰ متره", "ps"))
        assertEquals("څلور", TtsText.forTts("4", "ps"))
        assertEquals("سل او پنځه", TtsText.forTts("105", "ps"))
        assertEquals("دوه اعشاریه پنځه", TtsText.forTts("2.5", "ps"))
    }

    @Test
    fun `انگلیسی - استاندارد`() {
        assertEquals("two hundred", TtsText.forTts("200", "en"))
        assertEquals("In two hundred meters", TtsText.forTts("In 200 meters", "en"))
        assertEquals("two point five", TtsText.forTts("2.5", "en"))
        assertEquals("one thousand two hundred", TtsText.forTts("1200", "en"))
    }

    @Test
    fun `متن بدون عدد دست‌نخورده می‌ماند`() {
        assertEquals("به راست بپیچید", TtsText.forTts("به راست بپیچید", "fa"))
        assertEquals("", TtsText.forTts("", "fa"))
        assertTrue(TtsText.forTts("Turn 21 right", "en").contains("twenty-one"))
    }
}
