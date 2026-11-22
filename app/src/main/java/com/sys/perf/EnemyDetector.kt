package com.sys.perf

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Free Fire MAX OB55 skeleton detector (portrait 1080×1920)
 *
 * FIX LOG vs prior version:
 *  1. MatOfPoint contour leak → explicit release() every loop iteration
 *  2. HSV red range S-floor 130→80  (catches distant/faded HP bars)
 *  3. Green-teal outline hue shifted 32-88 → 78-105 (OB55 cyan outline shader)
 *  4. kDil5 width 7→5 (was merging adjacent-enemy bars)
 *  5. area ceiling 20000→28000 (misses large close-range blobs)
 *  6. isBar height threshold 26→30 (OB55 HP bar taller on hi-DPI)
 *  7. isBar width threshold 340→380
 *  8. Pure-bar body-height estimator: coefficient 2.8→2.4 (over-estimated)
 *  9. Edge margin 32→24 (was rejecting near-edge legitimate targets)
 * 10. nzRatio denominator guard: use rect.area().toInt() not sr.area()
 * 11. nameAbove look-up height factor 2.5→1.8 (was scanning too far, false positives)
 * 12. Confidence weights rebalanced: bar=0.40, blob=0.40, name=0.20
 * 13. Reusable Mat pool: added explicit release-on-detect for contours list
 */
object EnemyDetector {

    // Skeleton fractions (0.0 = top of bounding rect)
    private const val F_HEAD  = 0.10f
    private const val F_NECK  = 0.22f
    private const val F_BODY  = 0.38f

    // ── HSV ranges (OB55-calibrated) ────────────────────────────────────────
    // Red HP bar / red accent — wraps 0° and 360°
    // FIX: S-floor lowered 130→80 to catch distant faded bars
    private val redLo1 = Scalar(  0.0,  80.0, 100.0)
    private val redHi1 = Scalar( 10.0, 255.0, 255.0)
    private val redLo2 = Scalar(165.0,  80.0, 100.0)
    private val redHi2 = Scalar(180.0, 255.0, 255.0)

    // FIX: OB55 enemy outline is bright cyan, hue 78-105 (not 32-88 green)
    private val outLo  = Scalar( 78.0,  60.0, 130.0)
    private val outHi  = Scalar(105.0, 255.0, 255.0)

    // Near-white name text
    private val nameLo = Scalar(  0.0,  0.0, 200.0)
    private val nameHi = Scalar(180.0, 35.0, 255.0)

    // ── Reusable Mats ────────────────────────────────────────────────────────
    private val bgr       = Mat()
    private val hsv       = Mat()
    private val roiMat    = Mat()
    private val mRed1     = Mat()
    private val mRed2     = Mat()
    private val mRed      = Mat()
    private val mOutline  = Mat()
    private val mName     = Mat()
    private val mCombined = Mat()
    private val hierarchy = Mat()

    // FIX: kDil5 width 7→5 (was fusing adjacent enemy HP bars)
    private val kOpen3 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
    private val kDil5  = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,    Size(5.0, 3.0))
    private val kOpen5 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    private val kDil3  = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))

    fun detect(frame: Mat, screenW: Int, screenH: Int, fovRadius: Int): List<Detection> {
        val cx = screenW / 2; val cy = screenH / 2; val r = fovRadius
        val x0 = max(0, cx - r);         val y0 = max(0, cy - r)
        val x1 = min(screenW, cx + r);   val y1 = min(screenH, cy + r)
        val roiW = x1 - x0;              val roiH = y1 - y0
        if (roiW <= 0 || roiH <= 0) return emptyList()

        frame.submat(Rect(x0, y0, roiW, roiH)).copyTo(roiMat)
        Imgproc.cvtColor(roiMat, bgr, Imgproc.COLOR_BGRA2BGR)
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

        // Red mask
        Core.inRange(hsv, redLo1, redHi1, mRed1)
        Core.inRange(hsv, redLo2, redHi2, mRed2)
        Core.add(mRed1, mRed2, mRed)
        Imgproc.morphologyEx(mRed, mRed, Imgproc.MORPH_OPEN, kOpen3)
        Imgproc.dilate(mRed, mRed, kDil5)

        // Outline mask (cyan OB55)
        Core.inRange(hsv, outLo, outHi, mOutline)
        Imgproc.morphologyEx(mOutline, mOutline, Imgproc.MORPH_OPEN, kOpen5)
        Imgproc.dilate(mOutline, mOutline, kDil3)

        // Name text mask
        Core.inRange(hsv, nameLo, nameHi, mName)

        // Combined for contour finding
        Core.add(mRed, mOutline, mCombined)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            mCombined, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        val results = ArrayList<Detection>()

        for (c in contours) {
            try {
                // FIX: area ceiling 20000→28000 (close-range targets clipped)
                val area = Imgproc.contourArea(c)
                if (area < 35.0 || area > 28000.0) continue

                val rect = Imgproc.boundingRect(c)
                if (rect.width < 6 || rect.height < 4) continue

                val redR = nzRatio(mRed, rect)
                val outR = nzRatio(mOutline, rect)
                val ar   = rect.width.toFloat()  / max(rect.height, 1)
                val arV  = rect.height.toFloat() / max(rect.width,  1)

                // FIX: bar thresholds: height 26→30, width 340→380 (OB55 hi-DPI)
                val isBar  = redR > 0.20f && ar >= 1.8f && rect.width <= 380 && rect.height <= 30
                val isBlob = outR > 0.10f && arV >= 0.30f
                if (!isBar && !isBlob) continue

                val hasName = nameAbove(mName, rect)
                val cues = (if (isBar) 1 else 0) + (if (isBlob) 1 else 0) + (if (hasName) 1 else 0)
                if (cues < 2) continue

                val absLeft = x0 + rect.x
                val absTop  = y0 + rect.y
                val h       = rect.height

                val characterTopAbs: Int
                val characterH: Int
                if (isBar && !isBlob) {
                    // FIX: coefficient 2.8→2.4 (prior version over-estimated body height)
                    val estBodyH = (rect.width * 2.4f).toInt().coerceIn(40, 320)
                    characterTopAbs = absTop + rect.height + 2
                    characterH      = estBodyH
                } else {
                    characterTopAbs = absTop
                    characterH      = h
                }

                val headX = absLeft + rect.width / 2
                val headY = characterTopAbs + (characterH * F_HEAD).toInt()
                val neckY = characterTopAbs + (characterH * F_NECK).toInt()
                val bodyY = characterTopAbs + (characterH * F_BODY).toInt()

                // FIX: edge margin 32→24
                val edgeMargin = 24
                if (absLeft < edgeMargin || absTop < edgeMargin ||
                    absLeft + rect.width  > screenW - edgeMargin ||
                    absTop  + rect.height > screenH - edgeMargin) continue

                // FIX: confidence weights: bar=0.40, blob=0.40, name=0.20
                val conf = 0.40f * (if (isBar)  1f else 0f) +
                           0.40f * (if (isBlob) 1f else 0f) +
                           0.20f * (if (hasName) 1f else 0f)

                results.add(
                    Detection(
                        x          = headX,
                        y          = headY,
                        headX      = headX,
                        headY      = headY,
                        neckY      = neckY,
                        bodyY      = bodyY,
                        area       = area,
                        confidence = conf
                    )
                )
            } finally {
                // FIX: release every contour Mat to prevent native heap leak
                c.release()
            }
        }

        results.sortBy { d ->
            val dx = (d.headX - cx).toFloat()
            val dy = (d.headY - cy).toFloat()
            dx * dx + dy * dy
        }
        return results
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun nzRatio(mask: Mat, rect: Rect): Float {
        val sr  = safeRect(mask, rect) ?: return 0f
        val sub = mask.submat(sr)
        val nz  = Core.countNonZero(sub)
        sub.release()
        // FIX: denominator now uses rect.area() not sr.area() — sr may be clipped
        return nz.toFloat() / max(rect.area().toInt(), 1)
    }

    // FIX: look-up height factor 2.5→1.8 (was scanning too high, false positives)
    private fun nameAbove(mask: Mat, rect: Rect): Boolean {
        val lh = (rect.height * 1.8f).toInt().coerceAtLeast(12)
        val ny = (rect.y - lh).coerceAtLeast(0)
        val nh = rect.y - ny
        if (nh <= 0) return false
        val sr  = safeRect(mask, Rect(rect.x, ny, rect.width, nh)) ?: return false
        val sub = mask.submat(sr)
        val nz  = Core.countNonZero(sub)
        sub.release()
        return nz > 8
    }

    private fun safeRect(mat: Mat, rect: Rect): Rect? {
        val x  = rect.x.coerceAtLeast(0)
        val y  = rect.y.coerceAtLeast(0)
        val x2 = (rect.x + rect.width).coerceAtMost(mat.width())
        val y2 = (rect.y + rect.height).coerceAtMost(mat.height())
        return if (x2 <= x || y2 <= y) null else Rect(x, y, x2 - x, y2 - y)
    }
}
