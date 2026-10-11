package com.deepdiver.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager

/** Screen capture: chhote size ke frames FrameStore me daalti rehti hai. */
class ProjectionService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "diverbot"
        const val FRAME_W = 360
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bmp: Bitmap? = null
    private var lastT = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent == null) return START_NOT_STICKY
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        stopCapture()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp

        val t = HandlerThread("capture").also { it.start() }
        thread = t
        val handler = Handler(t.looper)

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                FrameStore.frame = null
                stopCapture()
                stopSelf()
            }
        }, handler)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        FrameStore.initSize(wm)
        val realW = FrameStore.realW
        val realH = FrameStore.realH
        val w = FRAME_W
        val h = (realH.toFloat() * w / realW).toInt()
        val dpi = resources.displayMetrics.densityDpi

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastT >= 14) {
                        lastT = now
                        val pl = img.planes[0]
                        val rs = pl.rowStride
                        val ps = pl.pixelStride
                        val bw = rs / ps
                        var b = bmp
                        if (b == null || b.width != bw || b.height != h) {
                            b = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
                            bmp = b
                        }
                        b!!.copyPixelsFromBuffer(pl.buffer)
                        val px = IntArray(w * h)
                        b.getPixels(px, 0, w, 0, 0, w, h)
                        FrameStore.frame = Frame(w, h, px, now)
                    }
                } catch (e: Exception) {
                    // ignore single bad frame
                } finally {
                    img.close()
                }
            }
        }, handler)

        display = mp.createVirtualDisplay(
            "diverbot", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Diver BOT", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Deep Sea Diver BOT")
            .setContentText("Screen capture chalu hai")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun stopCapture() {
        try { display?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        try { thread?.quitSafely() } catch (e: Exception) {}
        display = null; reader = null; projection = null; thread = null
    }

    override fun onDestroy() {
        FrameStore.frame = null
        stopCapture()
        super.onDestroy()
    }
}
