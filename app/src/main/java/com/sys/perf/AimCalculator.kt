package com.sys.perf

import kotlin.math.*
import kotlin.random.Random

/**
 * Smooth humanized Bezier gesture path from crosshair to target.
 *
 * FIX LOG vs prior version:
 *  1. Bezier control point math was wrong: used (fx + dx*0.5 + perpX*arcMag) where
 *     perpX = -dy and dy was already the scaled delta. The control point was not
 *     on the correct perpendicular bisector. Fixed: perp computed from unit vector.
 *  2. End point of bezier was (fx + dx, fy + dy) — correct. But dx/dy already included
 *     sens scaling so the target coordinate was scaled twice vs crosshair coords.
 *     Fixed: sens applied only once at step interpolation, not at delta calculation.
 *  3. steps floor was 3 — produces visible stutter for sub-14px moves. Floor now 2.
 *  4. steps ceil 24 — too few for 300+ px moves at humanize=3. Ceil now 32.
 *  5. baseMs 6 + rand%4 → 5 + rand%3 to tighten timing for OB55 input latency budget.
 *  6. Random seed per-call via nanoTime was creating a new Random object per call in
 *     the original. It was a val at object level so only one Random exists — OK. No change.
 *  7. shouldAim threshold comparison: used lockThreshold as Int for Float pixel distance — OK.
 */
object AimCalculator {

    private val rng = Random(System.nanoTime())

    private val noiseTable  = floatArrayOf(0f, 1.0f, 2.4f, 4.5f)
    private val timingTable = longArrayOf(  0L,  2L,   4L,   8L )
    private val arcTable    = floatArrayOf(0f, 0.06f, 0.14f, 0.26f)

    data class StepPoint(val x: Float, val y: Float, val durationMs: Long)
    data class AimPath(val steps: List<StepPoint>, val totalDist: Float)

    fun buildPath(
        fx: Float, fy: Float,
        tx: Float, ty: Float,
        sens: Float
    ): AimPath {
        val hL    = ConfigManager.humanizeLevel.get().coerceIn(0, 3)
        val noise = noiseTable[hL]
        val timing= timingTable[hL]
        val arc   = arcTable[hL]

        // FIX: raw delta without sens — sens applied per-step below
        val rawDx = tx - fx
        val rawDy = ty - fy
        val dist  = sqrt(rawDx * rawDx + rawDy * rawDy)
        if (dist < 0.5f) return AimPath(emptyList(), 0f)

        // FIX: steps floor 3→2, ceil 24→32
        val steps = (dist / 12f).roundToInt().coerceIn(2, 32)

        // FIX: correct perpendicular unit vector (not reusing scaled perp)
        val unitX = rawDx / dist
        val unitY = rawDy / dist
        val perpX = -unitY
        val perpY =  unitX

        val midX   = fx + rawDx * 0.5f
        val midY   = fy + rawDy * 0.5f
        val arcMag = arc * dist * (rng.nextFloat() * 2f - 1f)
        val cpX    = midX + perpX * arcMag
        val cpY    = midY + perpY * arcMag

        val endX = tx
        val endY = ty

        val pts = ArrayList<StepPoint>(steps)
        for (i in 1..steps) {
            val t  = i.toFloat() / steps
            val mt = 1f - t
            // Quadratic Bezier
            val bx = mt * mt * fx + 2f * mt * t * cpX + t * t * endX
            val by = mt * mt * fy + 2f * mt * t * cpY + t * t * endY

            // Apply sens scaling to displacement from start, add noise
            val dispX = (bx - fx) * sens + fx
            val dispY = (by - fy) * sens + fy
            val nx    = if (noise > 0f) gauss(noise) else 0f
            val ny    = if (noise > 0f) gauss(noise * 0.6f) else 0f

            // FIX: baseMs 6→5, jitter range tightened
            val baseMs  = 5L + (abs(rng.nextLong()) % 3L)
            val jitter  = if (timing > 0L) abs(rng.nextLong()) % timing else 0L
            pts.add(StepPoint(dispX + nx, dispY + ny, baseMs + jitter))
        }

        return AimPath(pts, dist)
    }

    fun shouldAim(cx: Float, cy: Float, tx: Float, ty: Float): Boolean {
        val dx = tx - cx; val dy = ty - cy
        return sqrt(dx * dx + dy * dy) > ConfigManager.lockThreshold.get()
    }

    private fun gauss(sigma: Float): Float {
        val u1 = rng.nextDouble().coerceAtLeast(1e-10)
        val u2 = rng.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2) * sigma).toFloat()
    }
}
