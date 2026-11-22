package com.sys.perf

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * FIX LOG vs prior version:
 *  1. lockThreshold was AtomicInteger but AimCalculator.shouldAim compared it as Float
 *     (sqrt result). The comparison sqrt(...) > AtomicInteger.get() works via Int→Float
 *     widening so it was functionally correct — but default value 10 is in pixels.
 *     Changed default to 8 (tighter snap, matches OB55 lower frame latency).
 *  2. lockThreshold was never persisted in init() or save() — restarting the app reset
 *     it to default regardless of user setting. Added to both.
 *  3. smoothFactor was declared but never used anywhere in the codebase. Left as-is
 *     for future use; no functional bug.
 *  4. predictionMs default 48ms → 32ms. At 125 fps frame time is 8ms; 48ms ≈ 6 frames
 *     of prediction causes overshooting on close targets. 32ms ≈ 2 frames.
 */
object ConfigManager {
    private lateinit var prefs: SharedPreferences

    val isActive      = AtomicBoolean(false)
    val fovRadius     = AtomicInteger(220)
    val sensitivity   = AtomicReference(0.55f)
    // FIX: predictionMs default 48→32
    val predictionMs  = AtomicInteger(32)
    val humanizeLevel = AtomicInteger(2)
    // FIX: lockThreshold default 10→8
    val lockThreshold = AtomicInteger(8)
    val targetBone    = AtomicReference(Bone.HEAD)
    val smoothFactor  = AtomicReference(0.72f)

    enum class Bone { HEAD, NECK, BODY }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("cfg2", Context.MODE_PRIVATE)
        fovRadius.set(prefs.getInt("fov",     220))
        sensitivity.set(prefs.getFloat("sens", 0.55f))
        predictionMs.set(prefs.getInt("pred",  32))
        humanizeLevel.set(prefs.getInt("hum",  2))
        // FIX: load lockThreshold from prefs
        lockThreshold.set(prefs.getInt("lkth", 8))
        smoothFactor.set(prefs.getFloat("smooth", 0.72f))
        targetBone.set(Bone.valueOf(prefs.getString("bone", "HEAD") ?: "HEAD"))
    }

    fun save() {
        prefs.edit()
            .putInt("fov",    fovRadius.get())
            .putFloat("sens", sensitivity.get())
            .putInt("pred",   predictionMs.get())
            .putInt("hum",    humanizeLevel.get())
            // FIX: persist lockThreshold
            .putInt("lkth",   lockThreshold.get())
            .putFloat("smooth", smoothFactor.get())
            .putString("bone", targetBone.get().name)
            .apply()
    }
}
