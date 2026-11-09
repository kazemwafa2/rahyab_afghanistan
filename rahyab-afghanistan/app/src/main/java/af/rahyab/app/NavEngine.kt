package af.rahyab.app

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** موتور ناوبری روی گوشی: پیشرفت روی مسیر، پیچ بعدی، تشخیص خروج از مسیر. بدون نیاز به سرور. */
class NavEngine(val route: RouteResult) {

    class State(
        val progress: Double,
        val offRouteM: Double,
        val remaining: Double,
        val stepIndex: Int,
        val toManeuver: Double
    )

    private class Proj(val progress: Double, val dist: Double, val seg: Int)

    private val n = route.points.size
    private val lat = DoubleArray(n) { route.points[it].second }
    private val lon = DoubleArray(n) { route.points[it].first }
    private val cum = DoubleArray(n)
    val total: Double
    private val stepAt = DoubleArray(route.steps.size)
    private var lastSeg = 0

    init {
        for (i in 1 until n) cum[i] = cum[i - 1] + Geo.dist(lat[i - 1], lon[i - 1], lat[i], lon[i])
        total = if (n > 0) cum[n - 1] else 0.0
        var prev = 0.0
        for (i in route.steps.indices) {
            val s = route.steps[i]
            val p = when {
                n < 2 -> 0.0
                i == 0 -> 0.0
                i == route.steps.size - 1 -> total
                else -> project(s.lat, s.lon, 0, n - 2).progress
            }
            stepAt[i] = max(prev, p)
            prev = stepAt[i]
        }
    }

    private fun project(pLat: Double, pLon: Double, from: Int, to: Int): Proj {
        var best = Double.MAX_VALUE
        var bestProgress = 0.0
        var bestSeg = from
        val k = cos(Math.toRadians(pLat))
        for (i in from..to) {
            val ax = (lon[i] - pLon) * 111320.0 * k
            val ay = (lat[i] - pLat) * 110540.0
            val bx = (lon[i + 1] - pLon) * 111320.0 * k
            val by = (lat[i + 1] - pLat) * 110540.0
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best) {
                best = d
                bestSeg = i
                bestProgress = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        return Proj(bestProgress, best, bestSeg)
    }

    fun update(la: Double, lo: Double): State {
        if (n < 2) return State(0.0, 0.0, 0.0, 0, 0.0)
        var pr = project(la, lo, max(0, lastSeg - 2), min(n - 2, lastSeg + 300))
        if (pr.dist > 50) {
            val g = project(la, lo, 0, n - 2)
            if (g.dist < pr.dist) pr = g
        }
        lastSeg = pr.seg
        var idx = max(0, stepAt.size - 1)
        for (i in 1 until stepAt.size) {
            if (stepAt[i] > pr.progress + 5) { idx = i; break }
        }
        val toMan = if (stepAt.isEmpty()) total - pr.progress else stepAt[idx] - pr.progress
        return State(pr.progress, pr.dist, max(0.0, total - pr.progress), idx, max(0.0, toMan))
    }
}
