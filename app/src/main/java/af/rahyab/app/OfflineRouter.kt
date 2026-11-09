package af.rahyab.app

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * موتور مسیریابی آفلاین.
 *
 * قالب فایل گراف (RAFG1 نسخه ۱ — مستند در docs/DATA_FORMATS.md):
 *   header(22B) + lat[i32] + lon[i32] + csr[u32 ×(n+1)] + یال‌های جهت‌دار مرتب‌شده بر اساس گره مبدأ
 *   (to u32, lenM u16, speedKmh u8, flags u8, nameOff u32, nameLen u8) + جدول نام‌ها (UTF-8)
 *
 * پرچم‌ها: bit0=car, bit1=foot, bit2=roundabout
 * الگوریتم: A* روی وزن «زمان» با بخش‌پذیر هندسی؛ محدودیت یک‌طرفه، دوربرگردان و
 * دسترسی موتر/پیاده در زمان ساخت داده اعمال و اینجا رعایت می‌شود.
 */
object OfflineRouter {

    private const val TAG = "OfflineRouter"
    private const val MAGIC = "RAFG1\u0000"

    private const val F_CAR = 1
    private const val F_FOOT = 2
    private const val F_ROUNDABOUT = 4

    class Graph private constructor(
        val nodeCount: Int,
        val edgeCount: Int,
        private val lat: IntArray,          // ×1e7
        private val lon: IntArray,
        internal val csr: IntArray,          // nodeCount+1
        internal val to: IntArray,
        internal val lenM: IntArray,
        internal val speedKmh: IntArray,
        internal val flags: IntArray,
        private val nameOff: IntArray,
        private val nameLen: ByteArray,
        private val names: ByteArray
    ) {
        private val grid = HashMap<Int, IntArray>()

        /**
         * گراف پایه کشوری (فقط راه‌های اصلی سراسر کشور) — شعاع چسبیدن بزرگ‌تر می‌خواهد
         * چون خیابان‌های محلی شهرها در آن نیست؛ مسیرهای بین‌ولایتی با همین گراف یکپارچه
         * محاسبه می‌شوند و در مرز بسته‌ها قطع نمی‌شوند.
         */
        @Volatile
        var nationwide: Boolean = false

        companion object {
            /** بررسی سریع سلامت سرآیند فایل (بدون بارگذاری کامل) */
            fun probe(file: File) {
                if (!file.isFile || file.length() < 22) throw IllegalArgumentException("فایل گراف موجود نیست")
                java.io.RandomAccessFile(file, "r").use { r ->
                    val m = ByteArray(6); r.readFully(m)
                    require(String(m, Charsets.ISO_8859_1) == MAGIC) { "قالب فایل گراف معتبر نیست" }
                    val b = ByteArray(4); r.readFully(b)
                    val v = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
                    require(v == 1) { "نسخه گراف پشتیبانی نمی‌شود: $v" }
                }
            }

            fun load(file: File): Graph {
                val t0 = System.currentTimeMillis()
                val ch = FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ)
                val bb = ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    .order(ByteOrder.LITTLE_ENDIAN)
                try {
                    val magic = ByteArray(6)
                    bb.get(magic)
                    require(String(magic, Charsets.ISO_8859_1) == MAGIC) { "قالب فایل گراف معتبر نیست" }
                    val version = bb.int
                    require(version == 1) { "نسخه گراف پشتیبانی نمی‌شود: $version" }
                    val nodeCount = bb.int
                    val edgeCount = bb.int
                    val nameBlobSize = bb.int
                    bb.position(bb.position() + 16) // رزرو

                    fun ints(n: Int): IntArray =
                        IntArray(n).also { bb.asIntBuffer().get(it); bb.position(bb.position() + 4 * n) }

                    val lat = ints(nodeCount)
                    val lon = ints(nodeCount)
                    val csr = ints(nodeCount + 1)
                    val to = ints(edgeCount)

                    val lenM = IntArray(edgeCount)
                    val sb2 = bb.asShortBuffer()
                    for (i in 0 until edgeCount) lenM[i] = sb2.get().toInt() and 0xFFFF
                    bb.position(bb.position() + 2 * edgeCount)

                    val speed = IntArray(edgeCount)
                    for (i in 0 until edgeCount) speed[i] = bb.get().toInt() and 0xFF
                    val flags = IntArray(edgeCount)
                    for (i in 0 until edgeCount) flags[i] = bb.get().toInt() and 0xFF

                    val nameOff = ints(edgeCount)
                    val nameLen = ByteArray(edgeCount); bb.get(nameLen)
                    val names = ByteArray(nameBlobSize); bb.get(names)

                    val g = Graph(nodeCount, edgeCount, lat, lon, csr, to, lenM, speed, flags, nameOff, nameLen, names)
                    Log.d(TAG, "گراف بارگذاری شد: $nodeCount گره، $edgeCount یال، ${file.length() / 1048576}MB در ${System.currentTimeMillis() - t0}ms")
                    return g
                } finally {
                    ch.close()
                }
            }
        }

        fun edgeName(e: Int): String {
            val l = nameLen[e].toInt() and 0xFF
            if (l == 0) return ""
            val b = ByteArray(l)
            System.arraycopy(names, nameOff[e], b, 0, l)
            return String(b, Charsets.UTF_8)
        }

        fun nodeLat(n: Int): Double = lat[n] / 1e7
        fun nodeLon(n: Int): Double = lon[n] / 1e7

        /** شبکه سلولی برای یافتن سریع نزدیک‌ترین گره؛ یک بار پس از بارگذاری ساخته می‌شود */
        fun buildGrid() {
            if (grid.isNotEmpty()) return
            val cells = HashMap<Int, MutableList<Int>>(nodeCount / 2)
            for (n in 0 until nodeCount) {
                val k = cellKey(lat[n], lon[n])
                cells.getOrPut(k) { ArrayList(4) }.add(n)
            }
            for ((k, v) in cells) grid[k] = v.toIntArray()
        }

        private fun cellKey(latE7: Int, lonE7: Int): Int {
            val la = Math.floorDiv(latE7, 500_000) // ≈0.005 درجه
            val lo = Math.floorDiv(lonE7, 500_000)
            return la * 100_000 + lo
        }

        /** نزدیک‌ترین گره قابل پذیرش در شعاع مجاز؛ گره باید دست‌کم یک یال قابل استفاده برای پروفایل داشته باشد */
        fun nearest(latDeg: Double, lonDeg: Double, maxMeters: Double, car: Boolean): Int? {
            buildGrid()
            val latE7 = (latDeg * 1e7).toInt()
            val lonE7 = (lonDeg * 1e7).toInt()
            val baseLa = Math.floorDiv(latE7, 500_000)
            val baseLo = Math.floorDiv(lonE7, 500_000)
            val needFlag = if (car) F_CAR else F_FOOT
            var best = -1
            var bestM = maxMeters
            var ring = 0
            while (ring <= 80) {
                for (dla in -ring..ring) {
                    for (dlo in -ring..ring) {
                        if (max(abs(dla), abs(dlo)) != ring) continue
                        val arr = grid[(baseLa + dla) * 100_000 + (baseLo + dlo)] ?: continue
                        for (n in arr) {
                            val d = distMeters(latDeg, lonDeg, lat[n] / 1e7, lon[n] / 1e7)
                            if (d < bestM) {
                                var ok = false
                                for (e in csr[n] until csr[n + 1]) if (flags[e] and needFlag != 0) { ok = true; break }
                                if (!ok) continue
                                bestM = d; best = n
                            }
                        }
                    }
                }
                if (best >= 0 && ring >= 1) break
                ring++
            }
            return if (best >= 0) best else null
        }

        fun edgeCostSeconds(e: Int, car: Boolean): Double =
            if (car) lenM[e] * 3.6 / max(5, speedKmh[e])
            else lenM[e] / 1.33 // پیاده ≈ ۴٫۸ کیلومتر در ساعت
    }

    // ---------- محاسبات جغرافیایی ----------
    private fun distMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(Math.toRadians(lon2 - lon1)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lon2 - lon1))
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    // ---------- A* ----------
    private class Session(val n: Int) {
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val settled = BooleanArray(n)
    }

    /** محاسبه مسیر آفلاین؛ points در قالب برنامه به شکل (lon, lat) برمی‌گردد */
    fun route(
        g: Graph, profile: Profile,
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double
    ): RouteResult? {
        val car = profile == Profile.CAR
        val snap = if (car) (if (g.nationwide) 900.0 else 350.0) else (if (g.nationwide) 400.0 else 150.0)
        val s = g.nearest(fromLat, fromLon, snap, car)
        if (s == null) { Log.d(TAG, "نزدیک راه مبدأ، گره‌ای یافت نشد"); return null }
        val t = g.nearest(toLat, toLon, snap, car)
        if (t == null) { Log.d(TAG, "نزدیک راه مقصد، گره‌ای یافت نشد"); return null }

        val t0 = System.currentTimeMillis()
        val path = astar(g, s, t, car)
        Log.d(TAG, "A* در ${System.currentTimeMillis() - t0}ms؛ گره‌های مسیر: ${path?.size ?: 0}")
        if (path == null || path.size < 2) return null
        return buildResult(g, path, car)
    }

    /**
     * مسیرهای جایگزین به سبک نشان/بلد: مسیر اصلی (سریع‌ترین) + تا دو مسیر متمایز.
     * روش «جریمه»: یال‌های مسیرهای قبلی با جریمه زمانی مجدداً وزن‌دهی می‌شوند تا
     * A* راه‌های موازی متفاوت را پیدا کند. مسیرهای خیلی مشابه (>٪۷۵ هم‌پوشانی) دور ریخته می‌شوند.
     *
     * @param count حداکثر تعداد مسیرهای خروجی (شامل اصلی)
     */
    fun routeWithAlternatives(
        g: Graph, profile: Profile,
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        count: Int = 3
    ): List<RouteResult> {
        val car = profile == Profile.CAR
        val snap = if (car) (if (g.nationwide) 900.0 else 350.0) else (if (g.nationwide) 400.0 else 150.0)
        val s = g.nearest(fromLat, fromLon, snap, car) ?: return emptyList()
        val t = g.nearest(toLat, toLon, snap, car) ?: return emptyList()

        val paths = ArrayList<IntArray>()   // مسیرهای گره‌ای پذیرفته‌شده
        val penalized = HashSet<Long>()     // یال‌های جریمه‌خورده
        var attempt = 0
        while (paths.size < count && attempt < 6) {
            attempt++
            val path = if (paths.isEmpty()) astar(g, s, t, car)
            else astarWithPenalty(g, s, t, car, penalized, paths.size * 120.0)
            if (path == null || path.size < 2) break

            // بررسی تمایز با مسیرهای موجود (نسبت یال مشترک)
            if (paths.isNotEmpty()) {
                val keySet = HashSet<Long>()
                for (p in paths) addEdgeKeys(p, keySet)
                var common = 0.0
                val total = (path.size - 1).toDouble()
                for (i in 0 until path.size - 1) {
                    if (keySet.contains(edgeKey(path[i], path[i + 1]))) common += 1.0
                }
                if (total > 0 && common / total >= 0.75) {
                    // خیلی مشابه — جریمه بیشتر و تلاش دوباره
                    for (i in 0 until path.size - 1) penalized.add(edgeKey(path[i], path[i + 1]))
                    continue
                }
            }
            paths.add(path)
            addEdgeKeys(path, penalized)
        }

        val out = ArrayList<RouteResult>(paths.size)
        for (p in paths) out.add(buildResult(g, p, car))
        // مرتب‌سازی بر اساس زمان (سریع‌ترین اول) — قرارداد رابط کاربری
        out.sortBy { it.durationS }
        return out
    }

    private fun edgeKey(u: Int, v: Int): Long = (u.toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)

    private fun addEdgeKeys(path: IntArray, into: MutableSet<Long>) {
        for (i in 0 until path.size - 1) into.add(edgeKey(path[i], path[i + 1]))
    }

    /**
     * A* با جریمه روی یال‌های مشخص‌شده — برای تولید مسیرهای جایگزین.
     * penaltySeconds به هزینه هر یال جریمه‌خورده اضافه می‌شود.
     */
    private fun astarWithPenalty(
        g: Graph, s: Int, t: Int, car: Boolean,
        penalized: Set<Long>, penaltySeconds: Double
    ): IntArray? {
        val sess = Session(g.nodeCount)
        val maxSpeedMs = if (car) 25.0 else 1.5
        val tLat = g.nodeLat(t); val tLon = g.nodeLon(t)
        fun h(n: Int): Double = distMeters(g.nodeLat(n), g.nodeLon(n), tLat, tLon) / maxSpeedMs

        val pq = PriorityQueue<Long>()
        sess.dist[s] = 0.0
        pq.add(pack(h(s), s))
        val start = System.currentTimeMillis()
        while (pq.isNotEmpty()) {
            if (System.currentTimeMillis() - start > 10_000L) return null
            val key = pq.poll()
            val n = (key and 0xFFFFFFFFL).toInt()
            if (sess.settled[n]) continue
            sess.settled[n] = true
            if (n == t) break
            val dn = sess.dist[n]
            for (e in g.csr[n] until g.csr[n + 1]) {
                val f = g.flags[e]
                if (car && f and F_CAR == 0) continue
                if (!car && f and F_FOOT == 0) continue
                val v = g.to[e]
                if (sess.settled[v]) continue
                val penalty = if (penalized.contains(edgeKey(n, v))) penaltySeconds else 0.0
                val nd = dn + g.edgeCostSeconds(e, car) + penalty
                if (nd < sess.dist[v]) {
                    sess.dist[v] = nd
                    sess.prev[v] = n
                    pq.add(pack(nd + h(v), v))
                }
            }
        }
        if (!sess.settled[t]) return null
        val rev = ArrayList<Int>()
        var cur = t
        var guard = 0
        while (cur != -1 && guard++ <= sess.n) {
            rev.add(cur)
            if (cur == s) break
            cur = sess.prev[cur]
        }
        if (rev.lastOrNull() != s) return null
        rev.reverse()
        return rev.toIntArray()
    }

    private fun astar(g: Graph, s: Int, t: Int, car: Boolean): IntArray? {
        val sess = Session(g.nodeCount)
        val maxSpeedMs = if (car) 25.0 else 1.5 // سقف سرعت برای بخش‌پذیری تقریب H
        val tLat = g.nodeLat(t); val tLon = g.nodeLon(t)
        fun h(n: Int): Double = distMeters(g.nodeLat(n), g.nodeLon(n), tLat, tLon) / maxSpeedMs

        val pq = PriorityQueue<Long>()
        sess.dist[s] = 0.0
        pq.add(pack(h(s), s))
        val start = System.currentTimeMillis()
        while (pq.isNotEmpty()) {
            if (System.currentTimeMillis() - start > 10_000L) { Log.d(TAG, "A* تایم‌اوت شد"); return null }
            val key = pq.poll()
            val n = (key and 0xFFFFFFFFL).toInt()
            if (sess.settled[n]) continue
            sess.settled[n] = true
            if (n == t) break
            val dn = sess.dist[n]
            for (e in g.csr[n] until g.csr[n + 1]) {
                val f = g.flags[e]
                if (car && f and F_CAR == 0) continue
                if (!car && f and F_FOOT == 0) continue
                val v = g.to[e]
                if (sess.settled[v]) continue
                val nd = dn + g.edgeCostSeconds(e, car)
                if (nd < sess.dist[v]) {
                    sess.dist[v] = nd
                    sess.prev[v] = n
                    pq.add(pack(nd + h(v), v))
                }
            }
        }
        if (!sess.settled[t]) return null
        val rev = ArrayList<Int>()
        var cur = t
        var guard = 0
        while (cur != -1 && guard++ <= sess.n) {
            rev.add(cur)
            if (cur == s) break
            cur = sess.prev[cur]
        }
        if (rev.lastOrNull() != s) return null
        rev.reverse()
        return rev.toIntArray()
    }

    /** کلید صف اولویت: بیت‌های float بدون علامت در ۳۲ بیت بالا + شماره گره */
    private fun pack(fSeconds: Double, node: Int): Long {
        val fb = java.lang.Float.floatToIntBits(fSeconds.toFloat()).toLong() and 0xFFFFFFFFL
        return (fb shl 32) or (node.toLong() and 0xFFFFFFFFL)
    }

    // ---------- ساخت نتیجه و مرحله‌های ناوبری ----------
    private class RE(val from: Int, val to: Int, val len: Int, val name: String, val round: Boolean)

    private fun buildResult(g: Graph, path: IntArray, car: Boolean): RouteResult {
        val pts = ArrayList<Pair<Double, Double>>(path.size)
        for (n in path) pts.add(Pair(g.nodeLon(n), g.nodeLat(n)))

        val edges = ArrayList<RE>(path.size - 1)
        var totalLen = 0.0
        var totalTime = 0.0
        for (i in 0 until path.size - 1) {
            val u = path[i]; val v = path[i + 1]
            var found = -1
            for (e in g.csr[u] until g.csr[u + 1]) {
                if (g.to[e] != v) continue
                val f = g.flags[e]
                if (car && f and F_CAR == 0) continue
                if (!car && f and F_FOOT == 0) continue
                found = e; break
            }
            if (found < 0) continue
            val len = g.lenM[found].toDouble()
            totalLen += len
            totalTime += g.edgeCostSeconds(found, car)
            edges.add(RE(u, v, g.lenM[found], g.edgeName(found), g.flags[found] and F_ROUNDABOUT != 0))
        }

        val steps = ArrayList<Step>()
        if (edges.isEmpty()) {
            steps.add(Step("depart", null, "", 0.0, g.nodeLat(path[0]), g.nodeLon(path[0])))
            steps.add(Step("arrive", null, "", 0.0, g.nodeLat(path.last()), g.nodeLon(path.last())))
            return RouteResult(totalLen, totalTime, pts, steps)
        }

        fun maneuverOf(prevEdge: RE?, nextEdge: RE): String {
            if (prevEdge == null) return "depart"
            if (nextEdge.round && !prevEdge.round) return "roundabout"
            if (!nextEdge.round && prevEdge.round) return "exit_roundabout"
            val b1 = bearing(g.nodeLat(prevEdge.from), g.nodeLon(prevEdge.from), g.nodeLat(prevEdge.to), g.nodeLon(prevEdge.to))
            val b2 = bearing(g.nodeLat(nextEdge.from), g.nodeLon(nextEdge.from), g.nodeLat(nextEdge.to), g.nodeLon(nextEdge.to))
            val d = ((b2 - b1 + 540.0) % 360.0) - 180.0
            val ad = abs(d)
            val side = if (d > 0) "right" else "left"
            return when {
                ad <= 25.0 -> if (nextEdge.name != prevEdge.name) "continue" else ""
                ad <= 60.0 -> "slight_$side"
                ad <= 120.0 -> side
                ad <= 160.0 -> "sharp_$side"
                else -> "uturn"
            }
        }

        steps.add(Step("depart", null, edges[0].name, edges[0].len.toDouble(), g.nodeLat(edges[0].from), g.nodeLon(edges[0].from)))
        var pendingName = edges[0].name
        for (i in 1 until edges.size) {
            val prev = edges[i - 1]
            val cur = edges[i]
            val m = maneuverOf(prev, cur)
            if (m.isEmpty()) continue // ادامه در همان خیابان
            steps.add(Step(m, null, cur.name.ifEmpty { pendingName }, cur.len.toDouble(), g.nodeLat(cur.from), g.nodeLon(cur.from)))
            pendingName = cur.name
        }
        steps.add(Step("arrive", null, "", 0.0, g.nodeLat(path.last()), g.nodeLon(path.last())))

        if (steps.size > 150) {
            val kept = steps.filter { it.type != "continue" }
            if (kept.size > 10) return RouteResult(totalLen, totalTime, pts, kept)
        }
        return RouteResult(totalLen, totalTime, pts, steps)
    }
}
