package com.deepdiver.bot

import kotlin.math.abs

/** Ek screen frame (chhota size, ARGB pixels). */
class Frame(val w: Int, val h: Int, val px: IntArray, val t: Long)

/** Capture service yahan latest frame rakhti hai, bot yahan se padhta hai. */
object FrameStore {
    @Volatile var frame: Frame? = null
    @Volatile var realW: Int = 1080
    @Volatile var realH: Int = 2400

    fun initSize(wm: android.view.WindowManager) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            realW = b.width(); realH = b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            realW = dm.widthPixels; realH = dm.heightPixels
        }
    }
}

enum class Screen { GAME, MENU, CLAIM, CONTINUE, UNKNOWN }

class Fish(val x0: Float, val x1: Float, val y0: Float, val y1: Float, val cells: Int) {
    val cx: Float get() = (x0 + x1) / 2f
    val cy: Float get() = (y0 + y1) / 2f
}

class Analysis(val diverX: Float, val diverFound: Boolean, val fish: List<Fish>)

object Vision {

    // ---------- colour helpers ----------
    private fun hsv(c: Int, o: FloatArray) {
        val r = ((c shr 16) and 255) / 255f
        val g = ((c shr 8) and 255) / 255f
        val b = (c and 255) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        var h = 0f
        if (d > 1e-6f) {
            h = when (mx) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h *= 60f
            if (h < 0f) h += 360f
        }
        o[0] = h
        o[1] = if (mx > 0f) d / mx else 0f
        o[2] = mx
    }

    private val tmp = FloatArray(3)

    private fun at(f: Frame, x: Float, y: Float): Int {
        val ix = (x * f.w).toInt().coerceIn(0, f.w - 1)
        val iy = (y * f.h).toInt().coerceIn(0, f.h - 1)
        return f.px[iy * f.w + ix]
    }

    private fun isBlue(c: Int): Boolean { hsv(c, tmp); return tmp[0] in 205f..232f && tmp[1] in 0.6f..0.9f && tmp[2] >= 0.9f }
    private fun isPurple(c: Int): Boolean { hsv(c, tmp); return tmp[0] in 250f..275f && tmp[1] >= 0.7f && tmp[2] >= 0.85f }
    private fun isYellow(c: Int): Boolean { hsv(c, tmp); return tmp[0] in 42f..58f && tmp[1] >= 0.8f && tmp[2] >= 0.9f }
    private fun isGreen(c: Int): Boolean { hsv(c, tmp); return tmp[0] in 80f..140f && tmp[1] >= 0.8f && tmp[2] >= 0.7f }
    private fun isOrange(c: Int): Boolean { hsv(c, tmp); return tmp[0] in 28f..45f && tmp[1] >= 0.7f && tmp[2] >= 0.9f }

    // ---------- screen state ----------
    fun screen(f: Frame): Screen {
        // MENU: bada purple LEVEL button
        if (isPurple(at(f, 0.2f, 0.54f)) && isPurple(at(f, 0.8f, 0.54f)) && isPurple(at(f, 0.5f, 0.51f))) return Screen.MENU

        val banner = isYellow(at(f, 0.05f, 0.02f)) && isYellow(at(f, 0.95f, 0.02f))
        if (banner) {
            if (isGreen(at(f, 0.74f, 0.594f)) && isOrange(at(f, 0.34f, 0.691f))) return Screen.CLAIM
            if (isBlue(at(f, 0.26f, 0.594f)) && isBlue(at(f, 0.74f, 0.594f))) return Screen.CONTINUE
            return Screen.UNKNOWN
        }
        if (isBlue(at(f, 0.917f, 0.068f))) {
            hsv(at(f, 0.5f, 0.5f), tmp)
            if (tmp[0] in 190f..225f) return Screen.GAME
        }
        return Screen.UNKNOWN
    }

    // ---------- diver + fish ----------
    const val DIVER_Y = 0.37f
    const val DIVER_TOP = 0.335f
    const val DIVER_BOTTOM = 0.41f

    private const val CS = 6 // cell size (px)

    fun analyse(f: Frame, lastX: Float): Analysis {
        val w = f.w
        val h = f.h
        val hh = FloatArray(3)

        // --- diver (low-saturation light body in a band) ---
        val y0 = (DIVER_TOP * h).toInt()
        val y1 = (DIVER_BOTTOM * h).toInt()
        val xa = (maxOf(0.08f, lastX - 0.2f) * w).toInt()
        val xb = (minOf(0.92f, lastX + 0.2f) * w).toInt()
        val xs = IntArray((xb - xa).coerceAtLeast(1) * (y1 - y0).coerceAtLeast(1))
        var n = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in xa until xb) {
                hsv(f.px[row + x], hh)
                if (hh[1] < 0.3f && hh[2] > 0.3f && hh[2] < 0.95f) { xs[n++] = x }
            }
        }
        var found = false
        var dx = lastX
        if (n > 30) {
            java.util.Arrays.sort(xs, 0, n)
            dx = xs[n / 2].toFloat() / w
            found = true
        }

        // --- fish: orange/brown pixels, coins ko hata ke ---
        val cw = w / CS
        val ch = h / CS
        val fishCnt = IntArray(cw * ch)
        val coinCnt = IntArray(cw * ch)
        val yStart = (0.30f * h).toInt()
        val yEnd = (0.925f * h).toInt()
        val dxL = ((dx - 0.075f) * w).toInt()
        val dxR = ((dx + 0.075f) * w).toInt()
        val dyT = (0.31f * h).toInt()
        val dyB = (0.42f * h).toInt()
        val ovL = (0.70f * w).toInt()
        val ovT = (0.70f * h).toInt()
        val ovB = (0.84f * h).toInt()
        var y = yStart
        while (y < yEnd) {
            var x = (0.02f * w).toInt()
            val xe = (0.98f * w).toInt()
            while (x < xe) {
                val skipDiver = x in dxL..dxR && y in dyT..dyB
                val skipOverlay = x > ovL && y in ovT..ovB
                if (!skipDiver && !skipOverlay) {
                    hsv(f.px[y * w + x], hh)
                    val hue = hh[0]; val s = hh[1]; val v = hh[2]
                    val cell = (y / CS).coerceAtMost(ch - 1) * cw + (x / CS).coerceAtMost(cw - 1)
                    if (hue in 30f..60f && s > 0.55f && v > 0.55f) coinCnt[cell]++
                    else if (hue in 8f..38f && s in 0.3f..0.65f && v in 0.22f..0.9f) fishCnt[cell]++
                }
                x += 2
            }
            y += 2
        }
        // coin cells ko 1 cell dilate karo
        val coin = BooleanArray(cw * ch)
        for (cy in 0 until ch) for (cx in 0 until cw) {
            if (coinCnt[cy * cw + cx] >= 1) {
                for (j in -1..1) for (i in -1..1) {
                    val yy = cy + j; val xx = cx + i
                    if (yy in 0 until ch && xx in 0 until cw) coin[yy * cw + xx] = true
                }
            }
        }
        val on = BooleanArray(cw * ch)
        for (i in on.indices) on[i] = fishCnt[i] >= 2 && !coin[i]

        // connected components (8-neigh)
        val seen = BooleanArray(cw * ch)
        val out = ArrayList<Fish>()
        val stack = IntArray(cw * ch)
        for (start in on.indices) {
            if (!on[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = cw; var maxX = 0; var minY = ch; var maxY = 0; var cnt = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cy = cur / cw; val cx = cur % cw
                cnt++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                for (j in -1..1) for (i in -1..1) {
                    val yy = cy + j; val xx = cx + i
                    if (yy < 0 || yy >= ch || xx < 0 || xx >= cw) continue
                    val k = yy * cw + xx
                    if (on[k] && !seen[k]) { seen[k] = true; stack[sp++] = k }
                }
            }
            if (cnt >= 4) {
                out.add(
                    Fish(
                        minX * CS / w.toFloat(), (maxX + 1) * CS / w.toFloat(),
                        minY * CS / h.toFloat(), (maxY + 1) * CS / h.toFloat(), cnt
                    )
                )
            }
        }
        return Analysis(dx, found, out)
    }

    fun avgAbs(a: Float, b: Float) = abs(a - b)
}
