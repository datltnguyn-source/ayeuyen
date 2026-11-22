package com.sys.perf

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FIX LOG vs prior version:
 *  1. gPath.moveTo(first) then loop does lineTo for ALL steps including steps.first()
 *     — the first segment was drawn twice (moveTo + lineTo to same point), creating a
 *     zero-length segment that some gesture recognizers reject. Loop now starts at index 1.
 *  2. If steps has exactly 1 element: gPath had only moveTo, no lineTo — GestureDescription
 *     rejects a path with no segments. Added a minimal lineTo offset for single-step paths.
 *  3. dispatchGesture() may throw if accessibility service is not fully connected
 *     (onServiceConnected not yet called). Added isConnected guard.
 *  4. dispatching flag not cleared if dispatchGesture throws — added try/finally.
 */
class AimbotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AimbotAccessibilityService? = null
    }

    private val dispatching  = AtomicBoolean(false)
    private val uiHandler    = Handler(Looper.getMainLooper())
    private var isConnected  = false

    override fun onServiceConnected() { instance = this; isConnected = true }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; isConnected = false; super.onDestroy() }

    fun performAim(aimPath: AimCalculator.AimPath) {
        if (!isConnected) return
        if (!dispatching.compareAndSet(false, true)) return

        val steps = aimPath.steps
        if (steps.isEmpty()) { dispatching.set(false); return }

        try {
            val gPath = Path()
            gPath.moveTo(steps.first().x, steps.first().y)

            var totalMs = steps.first().durationMs

            if (steps.size == 1) {
                // FIX: single step — add a minimal 1px lineTo so path has a segment
                gPath.lineTo(steps.first().x + 0.5f, steps.first().y)
            } else {
                // FIX: start at index 1, not 0 — avoids duplicate segment from moveTo
                for (i in 1 until steps.size) {
                    gPath.lineTo(steps[i].x, steps[i].y)
                    totalMs += steps[i].durationMs
                }
            }

            val duration = totalMs.coerceAtLeast(16L)
            val stroke   = GestureDescription.StrokeDescription(gPath, 0L, duration, false)
            val gesture  = GestureDescription.Builder().addStroke(stroke).build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { dispatching.set(false) }
                override fun onCancelled(g: GestureDescription?) { dispatching.set(false) }
            }, uiHandler)

        } catch (e: Exception) {
            android.util.Log.w("AccSvc", "dispatchGesture failed: ${e.message}")
            dispatching.set(false)
        }
    }
}
