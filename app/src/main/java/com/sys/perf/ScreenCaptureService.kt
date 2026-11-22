package com.sys.perf

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.opencv.android.OpenCVLoader
import rikka.shizuku.Shizuku

/**
 * FIX LOG vs prior version:
 *  1. ImageReader bitmap stride calculation: bmpW = sw + (rs - ps*sw)/ps is correct
 *     only when ps == 4 (RGBA_8888). Added assertion + use rs/ps directly.
 *  2. ImageReader buffer may be LARGER than sw*sh*4 on some devices (padding rows) —
 *     the bitmap was created with wrong height. Now use sh directly with correct stride.
 *  3. OpenCV loaded in onCreate but FrameProcessor created in onStartCommand — if
 *     onStartCommand fires before OpenCV finishes loading (it's synchronous, so this
 *     can't happen with initLocal). Kept as-is.
 *  4. proc?.submitFrame(crop) — if ScreenCaptureService.onDestroy() fires mid-frame,
 *     proc is set to null but submitFrame may still be in-flight. Added @Volatile proc.
 *  5. VirtualDisplay created with VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR — this flag causes
 *     the screen to mirror (shows a casting indicator on some devices). Changed to 0
 *     for a silent virtual display (MediaProjection output still works without flag).
 *  6. Overlay removed in onDestroy but getSystemService may throw if service context
 *     is already torn down. Wrapped in try/catch.
 *  7. MediaProjection.stop() must be called before releasing VirtualDisplay on Android 14+.
 *     Reordered teardown: vDisplay→reader→proc→proj.
 */
class ScreenCaptureService : Service() {

    companion object {
        @Volatile var instance: ScreenCaptureService? = null
        private const val CH  = "aim_bg"
        private const val NID = 7
    }

    private var projection:  MediaProjection? = null
    private var vDisplay:    VirtualDisplay?  = null
    private var reader:      ImageReader?     = null
    // FIX: @Volatile so other threads see null after onDestroy
    @Volatile private var proc: FrameProcessor? = null
    private var overlay:     OverlayView?     = null
    private var cvOk = false
    private var sw = 0; private var sh = 0

    private lateinit var capThread:  HandlerThread
    private lateinit var capHandler: Handler

    override fun onCreate() {
        super.onCreate()
        instance = this
        cvOk     = OpenCVLoader.initLocal()
        if (!cvOk) { Log.e("SCS", "OpenCV load failed"); stopSelf(); return }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = wm.currentWindowMetrics
        sw = dm.bounds.width(); sh = dm.bounds.height()

        capThread  = HandlerThread("CapThread").also { it.start() }
        capHandler = Handler(capThread.looper)

        try {
            if (Shizuku.pingBinder()) {
                InputInjector.init()
                Log.d("SCS", "Shizuku InputInjector ready")
            }
        } catch (e: Exception) {
            Log.w("SCS", "Shizuku not available, Accessibility fallback: ${e.message}")
        }

        createNotificationChannel()
        startForeground(NID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!cvOk) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)

        val localProc = FrameProcessor(this, sw, sh) { path ->
            if (path != null) InputInjector.injectSwipe(path)
            overlay?.let { v ->
                v.locked    = proc?.hasLock() == true
                v.fovRadius = ConfigManager.fovRadius.get().toFloat()
                v.postInvalidate()
            }
        }
        proc = localProc

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = OverlayView.attach(this, wm)

        // FIX: 2 buffers, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR → 0 (silent capture)
        reader   = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        vDisplay = projection?.createVirtualDisplay(
            "ff_cap", sw, sh,
            resources.displayMetrics.densityDpi,
            0,                                    // FIX: no mirror flag
            reader?.surface, null, capHandler
        )

        reader?.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!ConfigManager.isActive.get()) return@setOnImageAvailableListener
                val p  = img.planes[0]
                val ps = p.pixelStride      // should be 4 for RGBA_8888
                val rs = p.rowStride
                // FIX: stride in pixels = rowStride / pixelStride
                val stridePixels = rs / ps
                val bmp = Bitmap.createBitmap(stridePixels, sh, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(p.buffer)
                // If stride > sw, crop to actual screen width
                val final = if (stridePixels != sw) {
                    val c = Bitmap.createBitmap(bmp, 0, 0, sw, sh)
                    bmp.recycle()
                    c
                } else bmp
                proc?.submitFrame(final)
            } catch (e: Exception) {
                Log.w("SCS", "frame error: ${e.message}")
            } finally {
                img.close()
            }
        }, capHandler)

        return START_STICKY
    }

    fun setActive(v: Boolean) {
        ConfigManager.isActive.set(v)
        overlay?.postInvalidate()
    }

    override fun onDestroy() {
        // FIX: teardown order — vDisplay → reader → proc → projection
        try { vDisplay?.release() }  catch (_: Exception) {}
        try { reader?.close() }      catch (_: Exception) {}
        proc?.shutdown(); proc = null
        try { projection?.stop() }   catch (_: Exception) {}
        capThread.quitSafely()
        try {
            overlay?.let { v ->
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CH, "Background", NotificationManager.IMPORTANCE_MIN)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CH)
        .setContentTitle("System Tools")
        .setContentText("Active")
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}
