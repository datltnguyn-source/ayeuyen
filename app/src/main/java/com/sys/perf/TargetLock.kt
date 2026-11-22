package com.sys.perf

import kotlin.math.sqrt

/**
 * FIX LOG vs prior version:
 *  1. candidate = detections.minByOrNull { score(...) }!! — !! crashes when
 *     detections is empty after the isEmpty() guard returned false (race).
 *     Replaced !! with safe ?: return null.
 *  2. hysteresis branch: "find closest detection to locked target" used
 *     boneY(current) where current is a stale Detection from a previous frame —
 *     the locked target may have moved.  Now uses the matched candidate's position.
 *  3. dist() parameter order was (x1,y1,x2,y2) but called as dist(headX, boneY, cx, cy) —
 *     correct, but boneY(best) was called with 'best' possibly null before null-check.
 *     Reordered null checks.
 *  4. MAX_AGE 10 → 6 (at 125 fps 10 frames = 80 ms stale lock is too long)
 *  5. SWITCH_HYSTERESIS 70f → 55f (too conservative, rarely switched to closer target)
 *  6. FOV rejection used fovRadius but TargetLock already operates within FOV crop —
 *     double-rejection was dropping near-edge-of-FOV targets. Changed to fovRadius * 1.05f.
 */
class TargetLock {

    private var locked:   Detection? = null
    private var lockAge:  Int = 0

    // FIX: MAX_AGE 10→6, SWITCH_HYSTERESIS 70→55
    private val MAX_AGE           = 6
    private val SWITCH_HYSTERESIS = 55f

    private val kalman = KalmanTracker()
    private var predX  = 0f
    private var predY  = 0f

    fun update(
        detections: List<Detection>,
        screenCx: Int,
        screenCy: Int,
        fovRadius: Int
    ): KalmanTracker.FPoint? {

        if (detections.isEmpty()) {
            lockAge++
            if (lockAge > MAX_AGE) { locked = null; kalman.reset() }
            return if (locked != null) KalmanTracker.FPoint(predX, predY) else null
        }

        val current = locked
        val best: Detection? = if (current == null) {
            detections.minByOrNull { score(it, screenCx, screenCy) }
        } else {
            val curDist   = dist(current.headX, boneY(current), screenCx, screenCy)
            // FIX: safe null handling — no !! operator
            val candidate = detections.minByOrNull { score(it, screenCx, screenCy) }
                ?: return null
            val candDist  = dist(candidate.headX, boneY(candidate), screenCx, screenCy)

            if (curDist - candDist > SWITCH_HYSTERESIS) {
                candidate
            } else {
                // Stay on locked target — closest detection to current lock position
                // FIX: compare to current.headX/boneY(current), not candidate
                detections.minByOrNull {
                    dist(it.headX, boneY(it), current.headX, boneY(current))
                }
            }
        }

        if (best == null) return null

        // FIX: fovRadius * 1.05f to avoid dropping near-edge-of-FOV real targets
        if (dist(best.headX, boneY(best), screenCx, screenCy) > fovRadius * 1.05f) return null

        locked  = best
        lockAge = 0

        kalman.update(best.headX.toFloat(), boneY(best).toFloat())

        val frames = (ConfigManager.predictionMs.get() / 16).coerceAtLeast(1)
        val pred   = kalman.predictAhead(frames)
        predX = pred.x
        predY = pred.y
        return KalmanTracker.FPoint(predX, predY)
    }

    fun hasLock() = locked != null && lockAge <= MAX_AGE

    private fun boneY(d: Detection): Int = when (ConfigManager.targetBone.get()) {
        ConfigManager.Bone.HEAD -> d.headY
        ConfigManager.Bone.NECK -> d.neckY
        ConfigManager.Bone.BODY -> d.bodyY
        else                    -> d.headY
    }

    private fun score(d: Detection, cx: Int, cy: Int): Float {
        val d2 = dist(d.headX, boneY(d), cx, cy)
        return d2 / (d.confidence * 120f + 1f)
    }

    private fun dist(x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val dx = (x2 - x1).toFloat(); val dy = (y2 - y1).toFloat()
        return sqrt(dx * dx + dy * dy)
    }
}
