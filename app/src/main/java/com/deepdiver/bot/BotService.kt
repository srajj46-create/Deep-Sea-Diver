package com.deepdiver.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Deep Sea Diver BOT
 *  MENU      -> LEVEL button tap
 *  GAME      -> finger hold + left/right drag se fish se bachna
 *  CLAIM     -> sirf CLAIM (neeche wala orange) tap, CLAIM 2X kabhi nahi
 *  CONTINUE  -> CONTINUE tap  -> agla level
 *  Ads       -> close button dhundh ke band, Play Store/browser khul jaye to BACK
 */
class BotService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var btn: Button? = null
    private var statusTv: TextView? = null

    @Volatile private var running = false
    private var worker: Thread? = null
    private val planner = Planner()

    // window tracking
    @Volatile private var curPkg = ""
    @Volatile private var curCls = ""
    @Volatile private var lastAppPkg = ""
    @Volatile private var gamePkg = ""

    // gesture state (main thread)
    private var stroke: GestureDescription.StrokeDescription? = null
    private var gBusy = false
    @Volatile private var holding = false
    @Volatile private var fingerTarget = 0.5f
    private var curX = 0.5f
    @Volatile private var segMs = 45L
    private val holdY = 0.62f

    // ------------------------------------------------------------------ lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        FrameStore.initSize(wm!!)
        createOverlay()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            if (p == packageName) return
            curPkg = p
            curCls = e.className?.toString() ?: ""
            if (p != "com.android.systemui" && !p.contains("inputmethod")) lastAppPkg = p
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        try { overlay?.let { wm?.removeView(it) } } catch (e: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ overlay
    private fun createOverlay() {
        val w = FrameStore.realW
        val h = FrameStore.realH
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply {
            text = "BOT off"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(8, 2, 8, 2)
        }
        val b = Button(this).apply {
            text = "START"
            setOnClickListener { toggle() }
        }
        lay.addView(tv)
        lay.addView(b)
        val lp = WindowManager.LayoutParams(
            (0.25f * w).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (0.74f * w).toInt()
        lp.y = (0.735f * h).toInt()
        wm?.addView(lay, lp)
        overlay = lay; btn = b; statusTv = tv
    }

    private fun setStatus(s: String) {
        main.post { statusTv?.text = s }
    }

    private fun toggle() {
        if (running) { stopBot(); return }
        if (FrameStore.frame == null) {
            Toast.makeText(this, "Pehle app me 'Screen capture allow karo'", Toast.LENGTH_LONG).show()
            return
        }
        gamePkg = lastAppPkg
        running = true
        btn?.text = "STOP"
        planner.reset()
        worker = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    private fun stopBot() {
        running = false
        releaseHold()
        btn?.text = "START"
        setStatus("BOT off")
    }

    // ------------------------------------------------------------------ main loop
    private fun loop() {
        var lastScreen = Screen.UNKNOWN
        var stable = 0
        var unknownSince = 0L
        var lastAction = 0L
        var attempt = 0
        var continueCounted = false
        var dbgSaved = false
        var lastProcessed = 0L
        var diverX = 0.5f

        while (running) {
            try {
                val f = FrameStore.frame
                if (f == null) {
                    setStatus("capture OFF")
                    Thread.sleep(300)
                    continue
                }
                val now = System.currentTimeMillis()
                val level = Prefs.level(this)
                val fast = level > Planner.FAST_FROM
                segMs = if (fast) 30L else 45L

                // Play Store / browser khul gaya (ad click) -> BACK
                if (isForeign() && now - lastAction > 1300) {
                    releaseHold()
                    main.post { performGlobalAction(GLOBAL_ACTION_BACK) }
                    lastAction = now
                    setStatus("ad page -> BACK")
                    Thread.sleep(400)
                    continue
                }

                val sc = Vision.screen(f)
                if (sc == lastScreen) stable++ else { stable = 0; lastScreen = sc }

                when (sc) {
                    Screen.GAME -> {
                        unknownSince = 0L; dbgSaved = false; continueCounted = false
                        if (f.t == lastProcessed) { Thread.sleep(5); continue }
                        lastProcessed = f.t
                        val a = Vision.analyse(f, diverX)
                        diverX = a.diverX
                        val tgt = planner.update(a, f.t, level)
                        if (!holding) beginHold(diverX)
                        val step = planner.fingerStep(diverX, tgt, level)
                        var ft = fingerTarget + step
                        // anti-windup: finger diver se bahut door na jaye
                        if (ft - diverX > 0.35f) ft = diverX + 0.35f
                        if (ft - diverX < -0.35f) ft = diverX - 0.35f
                        fingerTarget = ft.coerceIn(0.05f, 0.95f)
                        setStatus("Lv$level GAME v=${"%.2f".format(planner.speed)}${if (fast) " FAST" else ""}")
                        Thread.sleep(if (fast) 4 else 8)
                    }

                    Screen.MENU -> {
                        releaseHold(); planner.reset(); unknownSince = 0L; continueCounted = false
                        setStatus("Lv$level MENU")
                        if (stable >= 2 && now - lastAction > 1500) {
                            tap(0.5f, 0.54f); lastAction = now
                        }
                        Thread.sleep(120)
                    }

                    Screen.CLAIM -> {
                        releaseHold(); unknownSince = 0L
                        setStatus("Lv$level CLAIM")
                        if (stable >= 2 && now - lastAction > 1300) {
                            // sirf neeche wala orange CLAIM. CLAIM 2X (upar, green) kabhi nahi.
                            tap(0.5f, 0.691f); lastAction = now
                        }
                        Thread.sleep(120)
                    }

                    Screen.CONTINUE -> {
                        releaseHold(); unknownSince = 0L
                        setStatus("Lv$level CONTINUE")
                        if (stable >= 2 && now - lastAction > 1300) {
                            tap(0.5f, 0.594f); lastAction = now
                            if (!continueCounted) {
                                Prefs.setLevel(this, level + 1)
                                continueCounted = true
                            }
                        }
                        Thread.sleep(120)
                    }

                    Screen.UNKNOWN -> {
                        if (unknownSince == 0L) unknownSince = now
                        val dur = now - unknownSince
                        if (dur > 700) releaseHold()
                        setStatus("Lv$level ? ${dur / 1000}s")
                        if (dur > 1800 && now - lastAction > 1100) {
                            lastAction = now
                            attempt++
                            closeAd(attempt)
                        }
                        if (dur > 7000 && !dbgSaved) {
                            dbgSaved = true
                            saveDebug(f)
                        }
                        Thread.sleep(100)
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                try { Thread.sleep(100) } catch (_: Exception) {}
            }
        }
        releaseHold()
    }

    private fun isForeign(): Boolean {
        val g = gamePkg
        val c = curPkg
        if (g.isEmpty() || c.isEmpty()) return false
        return c != g && c != "com.android.systemui" && c != packageName && !c.contains("inputmethod")
    }

    // ------------------------------------------------------------------ ads
    private val closeTexts = setOf(
        "close", "close ad", "skip", "skip ad", "dismiss", "×", "✕", "✖", "x", "close button", "no thanks"
    )

    private fun closeAd(attempt: Int) {
        // 1) accessibility tree me close / skip button
        if (tryCloseByNode()) return
        // 2) top corner par close (X) ke jagah tap
        val pts = arrayOf(
            0.935f to 0.024f, 0.948f to 0.034f, 0.922f to 0.026f,
            0.06f to 0.03f, 0.955f to 0.012f, 0.97f to 0.03f
        )
        val p = pts[attempt % pts.size]
        tap(p.first, p.second)
        // 3) kabhi kabhi BACK
        if (attempt % 6 == 0) main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400)
    }

    private fun tryCloseByNode(): Boolean {
        val ws = (try { windows } catch (e: Exception) { null }) ?: return false
        for (w in ws) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == packageName) continue
            val node = findClose(root)
            if (node != null) {
                var n: AccessibilityNodeInfo? = node
                var depth = 0
                while (n != null && !n.isClickable && depth < 4) { n = n.parent; depth++ }
                if (n != null && n.isClickable) {
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0) {
                    tap(r.exactCenterX() / FrameStore.realW, r.exactCenterY() / FrameStore.realH)
                    return true
                }
            }
        }
        return false
    }

    private fun findClose(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 700) {
            val n = q.removeFirst()
            seen++
            val d = n.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val t = n.text?.toString()?.trim()?.lowercase() ?: ""
            val id = n.viewIdResourceName?.lowercase() ?: ""
            if (d in closeTexts || t in closeTexts ||
                id.endsWith("close") || id.contains("close_button") || id.contains("skip") || id.contains("dismiss")
            ) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun saveDebug(f: Frame) {
        try {
            val bm = Bitmap.createBitmap(f.px, f.w, f.h, Bitmap.Config.ARGB_8888)
            val dir = getExternalFilesDir("unknown") ?: return
            dir.mkdirs()
            val file = File(dir, "unknown_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bm.compress(Bitmap.CompressFormat.PNG, 90, it) }
            main.post { Toast.makeText(this, "Unknown screen save hua: ${file.name}", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------ gestures (main thread)
    private fun pxX(x: Float) = x * FrameStore.realW
    private fun pxY(y: Float) = y * FrameStore.realH

    fun beginHold(x: Float) {
        main.post {
            if (holding) return@post
            if (gBusy) { main.postDelayed({ if (!holding) beginHold(x) }, 25); return@post }
            holding = true
            curX = x
            fingerTarget = x
            val p = Path()
            p.moveTo(pxX(x), pxY(holdY))
            p.lineTo(pxX(x) + 2f, pxY(holdY))
            val s = GestureDescription.StrokeDescription(p, 0, segMs, true)
            stroke = s
            dispatch(s)
        }
    }

    fun releaseHold() {
        main.post {
            holding = false
            if (!gBusy && stroke != null) afterSegment()
        }
    }

    private fun afterSegment() {
        val s = stroke ?: return
        if (holding) {
            val from = curX
            var to = fingerTarget
            val p = Path()
            p.moveTo(pxX(from), pxY(holdY))
            if (Math.abs(to - from) < 0.0005f) p.lineTo(pxX(from) + 2f, pxY(holdY)) else p.lineTo(pxX(to), pxY(holdY))
            val ns = s.continueStroke(p, 0, segMs, true)
            stroke = ns
            curX = to
            dispatch(ns)
        } else {
            val p = Path()
            p.moveTo(pxX(curX), pxY(holdY))
            p.lineTo(pxX(curX) + 2f, pxY(holdY))
            val ns = s.continueStroke(p, 0, 10, false)
            stroke = null
            dispatch(ns)
        }
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        gBusy = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    gBusy = false
                    afterSegment()
                }

                override fun onCancelled(g: GestureDescription?) {
                    gBusy = false
                    stroke = null
                    holding = false // loop agle frame par dobara hold shuru karega
                }
            }, main
        )
        if (!ok) { gBusy = false; stroke = null; holding = false }
    }

    fun tap(xf: Float, yf: Float) {
        main.post { doTap(xf, yf, 0) }
    }

    private fun doTap(xf: Float, yf: Float, tries: Int) {
        if (gBusy) {
            if (tries < 20) main.postDelayed({ doTap(xf, yf, tries + 1) }, 60)
            return
        }
        val p = Path()
        p.moveTo(pxX(xf), pxY(yf))
        dispatch(GestureDescription.StrokeDescription(p, 0, 70))
    }
}
