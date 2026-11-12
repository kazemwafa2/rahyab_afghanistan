package af.rahyab.app

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * مجری پس‌زمینه‌ای که خطای پیش‌بینی‌نشده را می‌بلعد و فقط لاگ می‌کند.
 * رفع اشکال کرش: قطع اینترنت باعث پرتاب NetworkException از ترد پس‌زمینه (هوا، ترافیک و…) می‌شد و
 * چون کسی آن را نمی‌گرفت، کل برنامه بسته می‌شد.
 */
object SafeExecutors {
    private class Safe(n: Int) : ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) {
        override fun execute(command: Runnable) {
            super.execute {
                try { command.run() } catch (t: Throwable) { Log.e("SafeExecutors", "خطای پس‌زمینه نادیده گرفته شد", t) }
            }
        }
    }
    fun fixed(n: Int): ExecutorService = Safe(n)
}
