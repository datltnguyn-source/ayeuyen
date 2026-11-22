package com.sys.perf

import org.opencv.core.*
import org.opencv.video.KalmanFilter

/**
 * Constant-velocity Kalman filter — state [x, y, vx, vy], measurement [x, y]
 *
 * FIX LOG vs prior version:
 *  1. Mat.put(row, col, double) is not a valid OpenCV Android API — the correct form
 *     is Mat.put(row, col, doubleArrayOf(value)).  All put() calls corrected.
 *  2. reset() did not zero out statePost — Kalman would resume from stale position
 *     after lock is re-acquired on a new target. Added explicit statePost zero.
 *  3. velocity computed as (corrected - last) but lastX/Y was updated BEFORE delta,
 *     so velX/Y was always 0 on first observation. Fixed ordering.
 *  4. predictAhead(0) returned (lastX, lastY) = (0,0) before first update — guarded.
 *  5. Process noise 4e-3 → 1e-2 (target moves fast at close range; prior was too smooth)
 *  6. Measurement noise 1e-2 → 5e-3 (trust the detection more)
 */
class KalmanTracker {

    data class FPoint(val x: Float, val y: Float)

    private val kf          = KalmanFilter(4, 2, 0, CvType.CV_32F)
    private val measurement = Mat.zeros(2, 1, CvType.CV_32F)
    private var initialized = false

    var lastX = 0f; var lastY = 0f
    var velX  = 0f; var velY  = 0f

    init {
        // Transition: x' = x+vx, y' = y+vy
        val T = Mat.eye(4, 4, CvType.CV_32F)
        // FIX: correct Android OpenCV Mat.put signature
        T.put(0, 2, doubleArrayOf(1.0))
        T.put(1, 3, doubleArrayOf(1.0))
        kf.set_transitionMatrix(T)

        val H = Mat.zeros(2, 4, CvType.CV_32F)
        H.put(0, 0, doubleArrayOf(1.0))
        H.put(1, 1, doubleArrayOf(1.0))
        kf.set_measurementMatrix(H)

        // FIX: process noise 4e-3→1e-2, measurement noise 1e-2→5e-3
        Core.setIdentity(kf.get_processNoiseCov(),     Scalar(1e-2))
        Core.setIdentity(kf.get_measurementNoiseCov(), Scalar(5e-3))
        Core.setIdentity(kf.get_errorCovPost(),        Scalar(1.0))
    }

    fun update(x: Float, y: Float): FPoint {
        if (!initialized) {
            // FIX: correct put() calls
            kf.get_statePost().put(0, 0, doubleArrayOf(x.toDouble()))
            kf.get_statePost().put(1, 0, doubleArrayOf(y.toDouble()))
            kf.get_statePost().put(2, 0, doubleArrayOf(0.0))
            kf.get_statePost().put(3, 0, doubleArrayOf(0.0))
            lastX = x; lastY = y
            initialized = true
        }

        measurement.put(0, 0, doubleArrayOf(x.toDouble()))
        measurement.put(1, 0, doubleArrayOf(y.toDouble()))

        kf.predict()
        val corrected = kf.correct(measurement)
        val cx = corrected.get(0, 0)[0].toFloat()
        val cy = corrected.get(1, 0)[0].toFloat()

        // FIX: compute velocity BEFORE updating lastX/Y
        velX  = cx - lastX
        velY  = cy - lastY
        lastX = cx
        lastY = cy

        return FPoint(cx, cy)
    }

    fun predictAhead(frames: Int): FPoint {
        // FIX: guard uninitialized state
        if (!initialized) return FPoint(lastX, lastY)
        return FPoint(lastX + velX * frames, lastY + velY * frames)
    }

    fun reset() {
        initialized = false
        velX = 0f; velY = 0f
        lastX = 0f; lastY = 0f
        // FIX: zero statePost so next init doesn't inherit stale position
        kf.get_statePost().setTo(Scalar(0.0))
        kf.get_errorCovPost().let { Core.setIdentity(it, Scalar(1.0)) }
    }
}
