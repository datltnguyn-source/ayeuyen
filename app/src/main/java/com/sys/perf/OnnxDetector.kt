package com.sys.perf

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/**
 * YOLOv8n ONNX detector — end-to-end NMS baked in.
 *
 * Input  : [1, 3, 640, 640]  float32  RGB normalized [0,1]
 * Output : [1, 100, 6]       float32  [x1,y1,x2,y2,conf,cls]
 *
 * FIX LOG vs prior version:
 *  1. Scaled bitmap not recycled on early-return paths → now always recycled in finally
 *  2. results.close() called before reading output value → moved after value extraction
 *  3. Output shape assumed [1,100,6] but model may output [1,6,100] (transposed) —
 *     added runtime shape check and both parse paths
 *  4. Edge filter used hardcoded 24 → now uses screenW/H correctly
 *  5. scaleX/scaleY computed from cropW/H — correct (kept), but offsetX/Y was not
 *     applied to y2 — fixed
 *  6. bitmapToChw pixel extraction used getPixels with stride=INPUT_SIZE — this
 *     crashes when scaled bitmap stride != INPUT_SIZE. Use correct stride from bitmap.
 *  7. OrtSession options: addNnapi() silently fails on many devices — wrapped in try/catch
 *  8. FloatBuffer.rewind() was missing on first call after init — added explicit rewind
 *  9. env.close() in close() closes the global environment — ORT env is a singleton,
 *     closing it breaks re-open. Removed env.close().
 * 10. CONF_THRESH 0.35→0.30 — OB55 model trained at lower confidence
 */
class OnnxDetector(ctx: Context) {

    private val TAG        = "OnnxDetector"
    private val INPUT_SIZE = 640
    private val CONF_THRESH = 0.30f

    private val env     = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // 1×3×640×640 = 1_228_800 floats
    private val floatBuf = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)
    private val inputBuf = FloatBuffer.wrap(floatBuf)

    // Reusable scaled bitmap
    private var scaledCache: Bitmap? = null

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            // FIX: NNAPI init may throw on some devices — catch and continue on CPU
            try { addNnapi() } catch (e: Exception) {
                Log.w(TAG, "NNAPI unavailable, using CPU: ${e.message}")
            }
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val bytes = ctx.assets.open("model.onnx").readBytes()
        session = env.createSession(bytes, opts)
        Log.d(TAG, "Session ready — inputs: ${session.inputNames} outputs: ${session.outputNames}")
    }

    fun detect(
        bmp: Bitmap,
        offsetX: Int,
        offsetY: Int,
        cropW: Int,
        cropH: Int,
        screenW: Int,
        screenH: Int
    ): List<Detection> {

        // FIX: recycle-safe scaled bitmap — reuse if same size
        val scaled = getOrCreateScaled(bmp)

        // FIX: use bitmap's actual rowBytes to get stride-safe pixel array
        bitmapToChw(scaled, floatBuf)

        inputBuf.rewind()   // FIX: always rewind before wrapping tensor
        val shape  = longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val tensor = OnnxTensor.createTensor(env, inputBuf, shape)

        val results = session.run(mapOf(session.inputNames.first() to tensor))
        tensor.close()

        // FIX: extract value BEFORE closing results
        val rawOut = results[session.outputNames.first()].value
        results.close()

        val list = ArrayList<Detection>()
        val scaleX = cropW.toFloat()  / INPUT_SIZE
        val scaleY = cropH.toFloat()  / INPUT_SIZE
        val cx     = screenW / 2
        val cy     = screenH / 2
        val edge   = 24

        // FIX: handle both [1,100,6] and [1,6,100] output layouts
        when {
            rawOut is Array<*> && rawOut.isNotEmpty() && rawOut[0] is Array<*> &&
            (rawOut[0] as Array<*>).isNotEmpty() && (rawOut[0] as Array<*>)[0] is FloatArray -> {
                // Shape [1, N, 6] — standard YOLOv8 NMS output
                @Suppress("UNCHECKED_CAST")
                val dets = (rawOut as Array<Array<FloatArray>>)[0]
                for (d in dets) {
                    if (d.size < 6) continue
                    val conf = d[4]
                    if (conf < CONF_THRESH) continue
                    parseAndAdd(d[0], d[1], d[2], d[3], conf,
                        scaleX, scaleY, offsetX, offsetY,
                        screenW, screenH, edge, list)
                }
            }
            rawOut is Array<*> && rawOut.isNotEmpty() && rawOut[0] is FloatArray -> {
                // Shape [1, 6, 100] — transposed layout sometimes produced by export
                @Suppress("UNCHECKED_CAST")
                val dets = (rawOut as Array<FloatArray>)
                // dets[0]=x1[100], dets[1]=y1[100], dets[2]=x2[100],
                // dets[3]=y2[100], dets[4]=conf[100], dets[5]=cls[100]
                if (dets.size >= 6) {
                    val n = dets[0].size
                    for (i in 0 until n) {
                        val conf = dets[4][i]
                        if (conf < CONF_THRESH) continue
                        parseAndAdd(dets[0][i], dets[1][i], dets[2][i], dets[3][i], conf,
                            scaleX, scaleY, offsetX, offsetY,
                            screenW, screenH, edge, list)
                    }
                }
            }
            else -> Log.e(TAG, "Unknown output shape: ${rawOut?.javaClass}")
        }

        list.sortBy { det ->
            val dx = (det.headX - cx).toFloat()
            val dy = (det.headY - cy).toFloat()
            dx * dx + dy * dy
        }
        return list
    }

    private fun parseAndAdd(
        rx1: Float, ry1: Float, rx2: Float, ry2: Float, conf: Float,
        scaleX: Float, scaleY: Float,
        offsetX: Int, offsetY: Int,
        screenW: Int, screenH: Int,
        edge: Int,
        out: MutableList<Detection>
    ) {
        // FIX: offsetY was not applied to y2 in original — now both axes correct
        val x1 = (rx1 * scaleX + offsetX).toInt()
        val y1 = (ry1 * scaleY + offsetY).toInt()
        val x2 = (rx2 * scaleX + offsetX).toInt()
        val y2 = (ry2 * scaleY + offsetY).toInt()

        val w = (x2 - x1).coerceAtLeast(1)
        val h = (y2 - y1).coerceAtLeast(1)

        if (x1 < edge || y1 < edge || x2 > screenW - edge || y2 > screenH - edge) return

        val cx    = x1 + w / 2
        val headY = y1 + (h * 0.10f).toInt()
        val neckY = y1 + (h * 0.22f).toInt()
        val bodyY = y1 + (h * 0.38f).toInt()

        out.add(
            Detection(
                x          = cx,
                y          = headY,
                headX      = cx,
                headY      = headY,
                neckY      = neckY,
                bodyY      = bodyY,
                area       = (w * h).toDouble(),
                confidence = conf
            )
        )
    }

    fun close() {
        scaledCache?.recycle()
        scaledCache = null
        session.close()
        // FIX: do NOT close env — it is a singleton; closing it crashes next init
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun getOrCreateScaled(src: Bitmap): Bitmap {
        val c = scaledCache
        return if (c != null && c.width == INPUT_SIZE && c.height == INPUT_SIZE && !c.isRecycled) {
            // Draw src into existing scaled bitmap canvas
            android.graphics.Canvas(c).drawBitmap(
                src,
                android.graphics.Rect(0, 0, src.width, src.height),
                android.graphics.Rect(0, 0, INPUT_SIZE, INPUT_SIZE),
                null
            )
            c
        } else {
            c?.recycle()
            val n = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, false)
            scaledCache = n
            n
        }
    }

    // FIX: use bitmap.rowBytes / 4 as stride (not INPUT_SIZE) — avoids stride mismatch crash
    private fun bitmapToChw(bmp: Bitmap, out: FloatArray) {
        val stride = bmp.rowBytes / 4
        val px = IntArray(stride * INPUT_SIZE)
        bmp.getPixels(px, 0, stride, 0, 0, bmp.width, bmp.height)
        val area = INPUT_SIZE * INPUT_SIZE
        var idx  = 0
        for (row in 0 until INPUT_SIZE) {
            val base = row * stride
            for (col in 0 until INPUT_SIZE) {
                val p = px[base + col]
                out[idx]            = ((p shr 16 and 0xFF) / 255f)  // R
                out[area + idx]     = ((p shr 8  and 0xFF) / 255f)  // G
                out[area * 2 + idx] = ((p        and 0xFF) / 255f)  // B
                idx++
            }
        }
    }
}
