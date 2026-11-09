package af.rahyab.app

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * جست‌وجوی محلی آفلاین روی اندیس دودویی (RAFI1 نسخه ۱ — مستند در docs/DATA_FORMATS.md).
 *
 * ساختار: nameBlob(نام اصلی) + normBlob(نام نرمال‌شده) + رکوردها
 *   نسخه ۱ (۲۰ بایت): nameOff u32, nameLen u8, normOff u32, normLen u8, lat i32(×1e7), lon i32, kind u8, rank u8
 *   نسخه ۲ (۲۱ بایت): + دسته (cat u8) برای جست‌وجوی دسته‌ای مکان‌ها (مسجد، شفاخانه، دواخانه و…)
 *
 * امتیازدهی: برابری کامل > شروع نام > شروع کلمه > شامل بودن؛ به‌علاوه اهمیت نوع مکان
 * (شهر، شهرک، قریه، ساحه، ساحه‌ای، ساحه‌ای؟ نه: استree، POI)، رتبه داده و نزدیکی به کاربر.
 */
object OfflineGeocoder {

    private const val TAG = "OfflineGeocoder"
    private const val MAGIC = "RAFI1\u0000"

    // انواع مکان (هماهنگ با tools/build_package.py)
    const val K_CITY = 0; const val K_TOWN = 1; const val K_VILLAGE = 2; const val K_HAMLET = 3
    const val K_SUBURB = 4; const val K_NEIGHBOURHOOD = 5; const val K_STREET = 6; const val K_POI = 7
    const val K_OTHER = 8
    private const val ALIAS_BIT = 128

    // دسته‌های POI (نسخه ۲ اندیس — هماهنگ با CAT_* در tools/build_package.py)
    const val C_RELIGION = 1    // مسجد، زیارت
    const val C_HEALTH = 2      // شفاخانه، کلینیک
    const val C_PHARMACY = 3    // دواخانه
    const val C_EDUCATION = 4   // مکتب، پوهنتون
    const val C_FUEL = 5        // پمپ‌بنزین
    const val C_BANK = 6        // بانک
    const val C_MARKET = 7      // بازار، دوکان
    const val C_FOOD = 8        // رستورانت، تیه‌خانه
    const val C_HOTEL = 9       // هوتل
    const val C_PARK = 10       // پارک، باغ
    const val C_SERVICES = 11   // پولیس، آتش‌نشانی
    const val C_TRANSPORT = 12  // ایستگاه، ترمینال، میدان هوایی
    const val C_GOV = 13        // اداره‌های دولتی
    const val C_PARKING = 14    // پارکینگ
    const val C_CULTURE = 15    // موزیم، اثر تاریخی

    private val KIND_BONUS = intArrayOf(
        55, 50, 42, 36, 32, 30, 38, 16, 24 // city, town, village, hamlet, suburb, nhood, street, poi, other
    )

    class Index private constructor(
        private val nameBlob: ByteArray,
        private val normBlob: ByteArray,
        private val rec: ByteArray,
        val recordCount: Int,
        private val recSize: Int,
        val formatVersion: Int
    ) {
        companion object {
            /** بررسی سریع سلامت سرآیند فایل (بدون بارگذاری کامل) */
            fun probe(file: File) {
                if (!file.isFile || file.length() < 34) throw IllegalArgumentException("فایل اندیس موجود نیست")
                java.io.RandomAccessFile(file, "r").use { r ->
                    val m = ByteArray(6); r.readFully(m)
                    require(String(m, Charsets.ISO_8859_1) == MAGIC) { "قالب اندیس جست‌وجو معتبر نیست" }
                    val b = ByteArray(4); r.readFully(b)
                    val v = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
                    require(v == 1 || v == 2) { "نسخه اندیس پشتیبانی نمی‌شود: $v" }
                }
            }

            fun load(file: File): Index {
                val t0 = System.currentTimeMillis()
                val bytes = file.readBytes()
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(6); bb.get(magic)
                require(String(magic, Charsets.ISO_8859_1) == MAGIC) { "قالب اندیس جست‌وجو معتبر نیست" }
                val version = bb.int
                require(version == 1 || version == 2) { "نسخه اندیس پشتیبانی نمی‌شود: $version" }
                val recSize = if (version == 2) 21 else 20
                val recordCount = bb.int
                val nameBlobSize = bb.int
                val normBlobSize = bb.int
                bb.position(bb.position() + 12) // رزرو
                val nameBlob = bytes.copyOfRange(bb.position(), bb.position() + nameBlobSize)
                bb.position(bb.position() + nameBlobSize)
                val normBlob = bytes.copyOfRange(bb.position(), bb.position() + normBlobSize)
                bb.position(bb.position() + normBlobSize)
                val rec = bytes.copyOfRange(bb.position(), bb.position() + recordCount * recSize)
                Log.d(TAG, "اندیس بارگذاری شد: $recordCount رکورد (نسخه $version) در ${System.currentTimeMillis() - t0}ms")
                return Index(nameBlob, normBlob, rec, recordCount, recSize, version)
            }
        }

        private fun recInt(i: Int, off: Int): Int {
            val b = rec
            val p = i * recSize + off
            return (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
        }

        fun recName(i: Int): String {
            val off = recInt(i, 0); val len = rec[i * recSize + 4].toInt() and 0xFF
            return String(nameBlob, off, len, Charsets.UTF_8)
        }

        private fun recLat(i: Int): Double = recInt(i, 10) / 1e7
        private fun recLon(i: Int): Double = recInt(i, 14) / 1e7
        private fun recKind(i: Int): Int = rec[i * recSize + 18].toInt() and 0x7F
        private fun recRank(i: Int): Int = rec[i * recSize + 19].toInt() and 0xFF
        /** دسته POI — فقط در نسخه ۲؛ در نسخه ۱ همیشه صفر */
        fun recCategory(i: Int): Int =
            if (recSize >= 21) rec[i * recSize + 20].toInt() and 0xFF else 0

        private fun normOff(i: Int): Int = recInt(i, 5)
        private fun normLen(i: Int): Int = rec[i * recSize + 9].toInt() and 0xFF

        /** جست‌وجوی متنی؛ userLat/userLon اگر معلوم باشد در رتبه‌بندی اثر می‌گذارد */
        fun search(query: String, userLat: Double?, userLon: Double?, limit: Int = 20): List<Place> {
            val q = TextNorm.norm(query)
            if (q.isEmpty()) return emptyList()
            val qb = q.toByteArray(Charsets.UTF_8)
            val out = ArrayList<Scored>(min(4000, recordCount / 4 + 16))
            var cap = 4000

            for (i in 0 until recordCount) {
                val off = normOff(i); val len = normLen(i)
                if (len == 0) continue
                val sc = matchScore(normBlob, off, len, qb)
                if (sc <= 0) continue
                var score = sc + KIND_BONUS[recKind(i)] + recRank(i) / 3
                if (userLat != null && userLon != null) {
                    val km = distKm(userLat, userLon, recLat(i), recLon(i))
                    if (km > 2.0) score -= min(35.0, km * 0.5).toInt()
                }
                if (out.size < cap) {
                    out.add(Scored(i, score))
                    if (out.size == cap && cap >= recordCount / 8) {
                        out.sortByDescending { it.s }; cap = min(4000, max(200, out.size / 2))
                    }
                } else if (score > out[out.size - 1].s) {
                    out[out.size - 1] = Scored(i, score)
                    out.sortByDescending { it.s }
                }
            }
            out.sortByDescending { it.s }

            // حذف تکراری‌ها (نام+مکان نزدیک) و برگرداندن نتیجه
            val seen = HashSet<Long>()
            val res = ArrayList<Place>(limit)
            for (x in out) {
                val i = x.i
                val key = (Math.round(recLat(i) * 1e4)) * 1000003L + Math.round(recLon(i) * 1e4)
                if (!seen.add(key)) continue
                res.add(Place(recName(i), recLat(i), recLon(i)))
                if (res.size >= limit) break
            }
            return res
        }

        private class Scored(val i: Int, val s: Int)

        /**
         * جست‌وجوی دسته‌ای: نزدیک‌ترین مکان‌های یک دسته (مسجد، شفاخانه، دواخانه و…).
         * مبنای رتبه: فاصله تا کاربر + اهمیت داده. فقط POIهای دسته‌دار (نسخه ۲ اندیس).
         */
        fun searchCategory(cat: Int, userLat: Double?, userLon: Double?, limit: Int = 20): List<Place> {
            if (cat <= 0) return emptyList()
            val out = ArrayList<Scored>(128)
            for (i in 0 until recordCount) {
                if (recKind(i) != K_POI) continue
                if (recCategory(i) != cat) continue
                var score = recRank(i)
                if (userLat != null && userLon != null) {
                    val km = distKm(userLat, userLon, recLat(i), recLon(i))
                    score += when {
                        km < 2.0 -> 100
                        km < 5.0 -> 80
                        km < 15.0 -> 60
                        km < 40.0 -> 40
                        else -> 15
                    }
                }
                out.add(Scored(i, score))
            }
            out.sortByDescending { it.s }
            val seen = HashSet<Long>()
            val res = ArrayList<Place>(limit)
            for (x in out) {
                val i = x.i
                val key = (Math.round(recLat(i) * 1e4)) * 1000003L + Math.round(recLon(i) * 1e4)
                if (!seen.add(key)) continue
                res.add(Place(recName(i), recLat(i), recLon(i)))
                if (res.size >= limit) break
            }
            return res
        }

        /** آیا این اندیس دسته‌بندی دارد؟ (نسخه ۲) */
        fun hasCategories(): Boolean = formatVersion >= 2

        /** امتیاز تطبیق بایت‌های پرس‌وجو با نام نرمال‌شده */
        private fun matchScore(blob: ByteArray, off: Int, len: Int, q: ByteArray): Int {
            if (len == q.size && bytesEqual(blob, off, q, 0, q.size)) return 130
            if (startsWith(blob, off, len, q)) return 120
            // شروع کلمه
            var pos = 0
            while (pos + q.size <= len) {
                val at = indexOfBytes(blob, off + pos, off + len, q)
                if (at < 0) break
                if (at > off && blob[at - 1] == ' '.code.toByte()) return 100
                pos = at - off + 1
                if (pos + q.size > len) break
            }
            if (containsBytes(blob, off, len, q)) return 70
            return 0
        }

        private fun bytesEqual(a: ByteArray, ao: Int, b: ByteArray, bo: Int, n: Int): Boolean {
            for (k in 0 until n) if (a[ao + k] != b[bo + k]) return false
            return true
        }

        private fun startsWith(blob: ByteArray, off: Int, len: Int, q: ByteArray): Boolean {
            if (len < q.size) return false
            return bytesEqual(blob, off, q, 0, q.size)
        }

        private fun indexOfBytes(blob: ByteArray, from: Int, to: Int, q: ByteArray): Int {
            if (q.isEmpty() || to - from < q.size) return -1
            val first = q[0]
            var p = from
            val end = to - q.size
            while (p <= end) {
                if (blob[p] == first && bytesEqual(blob, p, q, 0, q.size)) return p
                p++
            }
            return -1
        }

        private fun containsBytes(blob: ByteArray, off: Int, len: Int, q: ByteArray): Boolean =
            indexOfBytes(blob, off, off + len, q) >= 0

        private fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
            return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        }
    }
}
