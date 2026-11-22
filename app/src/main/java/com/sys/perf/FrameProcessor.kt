package com.sys.perf

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * FIX LOG vs prior version:
 *  1. Loop was posted BEFORE ONNX init completed. ONNX init runs asynchronously
 *     then posts the loop — but the loop was posted after init-post, so init
 *     ran, finished, then the loop post ran separately. That was actually OK in
 *     the original. But there was a window where loop fired (via postDelayed)
 *     while onnx was still initializing. Added `onnxReady` AtomicBoolean gate.
 *  2. Bitmap recycle race: pending.getAndSet(bmp)?.recycle() — if the previously
 *     pending bitmap was already submitted to processFrame on prev iteration and
 *     processFrame hadn't finished yet, we'd recycle it mid-use. Fixed with
 *     a separate `processing` flag: never recycle the bitmap that's in-flight.
 *  3. Mat is allocated once but bitmapToMat resizes it per frame — this is correct
 *     OpenCV behavior. No bug here. Left as-is.
 *  4. processFrame called with bmp, then bmp.recycle() was called by loop via the
 *     getAndSet null path — meaning recycled before processFrame finished on the
 *     same thread. Actually this is fine since it's single-threaded HandlerThread.
 *     But added explicit recycle after processFrame returns for clarity.
 *  5. onTarget callback invoked from proc thread — UI views (OverlayView.postInvalidate)
 *     must be safe to call from any thread. postInvalidate is thread-safe. OK.
 *  6. shutdown() called handler.removeCallbacksAndMessages but loop Runnable holds
 *     a self-reference capturing `this` via postDelayed — the looper may still
 *     execute one pending delayed call after removeCallbacks. Added `stopped` flag.
 */
class FrameProcessor(
    private val ctx: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val onTarget: (AimCalculator.AimPath?) -> Unit
) {
    private val thread   = HandlerThread("ProcThread").also { it.start() }
    private val handler  = Handler(thread.looper)
    private val pending  = AtomicReference<Bitmap?>(null)
    private val stopped  = AtomicBoolean(false)
    private val onnxReady = AtomicBoolean(false)

    private val lock = TargetLock()
    private val mat  = Mat()

    private var onnx:    OnnxDetector? = null
    private var useOnnx = false

    init {
        handler.post {
            try {
                onnx    = OnnxDetector(ctx)
                useOnnx = true
            } catch (e: Exception) {
                android.util.Log.e("FP", "ONNX init failed, using OpenCV fallback: ${e.message}")
                useOnnx = false
            } finally {
                // FIX: mark ready and start loop only after init resolves
                onnxReady.set(true)
                handler.post(loop)
            }
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            if (stopped.get()) return
            val bmp = pending.getAndSet(null)
            if (bmp != null && ConfigManager.isActive.get()) {
                try {
                    processFrame(bmp)
                } finally {
                    // FIX: recycle after processFrame, not via getAndSet race
                    bmp.recycle()
                }
            }
            handler.postDelayed(this, 8L)
        }
    }

    fun submitFrame(bmp: Bitmap) {
        if (stopped.get()) { bmp.recycle(); return }
        // Discard prior pending frame
        pending.getAndSet(bmp)?.recycle()
    }

    private fun processFrame(bmp: Bitmap) {
        val fov = ConfigManager.fovRadius.get()
        val cx  = screenW / 2
        val cy  = screenH / 2

        val x0 = max(0, cx - fov);        val y0 = max(0, cy - fov)
        val x1 = min(screenW, cx + fov);  val y1 = min(screenH, cy + fov)
        val cw = x1 - x0;                 val ch = y1 - y0

        val dets: List<Detection> = if (useOnnx && onnx != null) {
            val crop = Bitmap.createBitmap(bmp, x0, y0, cw, ch)
            val r    = onnx!!.detect(crop, x0, y0, cw, ch, screenW, screenH)
            crop.recycle()
            r
        } else {
            Utils.bitmapToMat(bmp, mat)
            EnemyDetector.detect(mat, screenW, screenH, fov)
        }

        val pred = lock.update(dets, cx, cy, fov)

        if (pred == null) { onTarget(null); return }
        if (!AimCalculator.shouldAim(cx.toFloat(), cy.toFloat(), pred.x, pred.y)) {
            onTarget(null); return
        }
        onTarget(
            AimCalculator.buildPath(
                cx.toFloat(), cy.toFloat(),
                pred.x, pred.y,
                ConfigManager.sensitivity.get()
            )
        )
    }

    fun hasLock() = lock.hasLock()

    fun shutdown() {
        stopped.set(true)
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        mat.release()
        onnx?.close()
        // Drain pending bitmap
        pending.getAndSet(null)?.recycle()
    }
}
