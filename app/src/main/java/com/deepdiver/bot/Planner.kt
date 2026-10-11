package com.deepdiver.bot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fish se bachne ka dimaag.
 * - Level 1..FAST_FROM-1  : fish slow aati hain  -> chhota look-ahead
 * - Level FAST_FROM+      : fish fast/very fast  -> bada look-ahead, zyada margin, tez reaction
 * Fish ki asli speed bhi frames se naapi jaati hai (speed estimate), isliye level badalne par
 * bot khud adjust ho jata hai.
 */
class Planner {
    companion object {
        const val FAST_FROM = 40
        const val MIN_X = 0.10f
        const val MAX_X = 0.90f
    }

    private var prev: List<Fish> = emptyList()
    private var prevT = 0L
    var speed = 0.25f          // fish upar ki taraf kitni screen-height per second
        private set
    var target = 0.5f
        private set

    fun reset() {
        prev = emptyList(); prevT = 0L; target = 0.5f; speed = 0.25f
    }

    fun update(a: Analysis, t: Long, level: Int): Float {
        val fast = level > FAST_FROM
        // ---- speed estimate ----
        if (prev.isNotEmpty() && prevT > 0L) {
            val dt = (t - prevT) / 1000f
            if (dt in 0.02f..0.3f) {
                val vs = ArrayList<Float>()
                for (c in a.fish) {
                    var best = -1f
                    for (p in prev) {
                        if (abs(p.cx - c.cx) < 0.07f) {
                            val dy = p.cy - c.cy
                            if (dy in 0.002f..0.15f && (best < 0f || dy < best)) best = dy
                        }
                    }
                    if (best > 0f) vs.add(best / dt)
                }
                if (vs.size >= 1) {
                    vs.sort()
                    val med = vs[vs.size / 2]
                    speed = (0.8f * speed + 0.2f * med).coerceIn(0.08f, 1.6f)
                }
            }
        }
        prev = a.fish; prevT = t

        val floor = if (fast) 0.35f else 0.12f
        val spd = max(speed, floor)
        val horizonSec = if (fast) 1.9f else 1.4f
        val look = (spd * horizonSec).coerceIn(0.15f, 0.60f)
        val margin = 0.055f + 0.02f + (if (fast) 0.025f else 0f)

        val dx = a.diverX
        // sirf wahi fish jo diver ke saamne/niche aa rahi hain
        class Threat(val lo: Float, val hi: Float, val w: Float)
        val threats = ArrayList<Threat>()
        for (f in a.fish) {
            if (f.y1 < Vision.DIVER_TOP) continue          // upar nikal gayi
            if (f.y0 > Vision.DIVER_BOTTOM + look) continue // abhi door hai
            val ttc = max(0f, (f.y0 - Vision.DIVER_BOTTOM) / spd)
            threats.add(Threat(f.x0 - margin, f.x1 + margin, 100f / (0.1f + ttc)))
        }

        var bestX = dx.coerceIn(MIN_X, MAX_X)
        var bestCost = Float.MAX_VALUE
        var x = MIN_X
        while (x <= MAX_X + 0.0001f) {
            var cost = 3f * abs(x - dx) + 1f * abs(x - 0.5f) + 2f * abs(x - target)
            for (th in threats) if (x >= th.lo && x <= th.hi) cost += th.w
            if (cost < bestCost) { bestCost = cost; bestX = x }
            x += 0.02f
        }
        target = bestX
        return target
    }

    /** finger ko kitna hilana hai (fraction of width). 0 = hold still. */
    fun fingerStep(diverX: Float, target: Float, level: Int): Float {
        val fast = level > FAST_FROM
        val err = target - diverX
        if (abs(err) < 0.012f) return 0f
        val maxStep = if (fast) 0.22f else 0.12f
        val step = 0.7f * err
        return min(maxStep, max(-maxStep, step))
    }
}
