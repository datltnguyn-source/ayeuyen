package com.sys.perf

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Injects MotionEvents directly via IInputManager through Shizuku.
 *
 * FIX LOG vs prior version:
 *  1. Binder acquisition was completely broken: spawned a child process via
 *     Shizuku.newProcess("service call input 1") which returns a binder
 *     token string, not an IBinder object. Replaced with the correct
 *     ShizukuBinderWrapper + SystemServiceHelper.getSystemService("input").
 *  2. injectMethod signature: on Android 13+ IInputManager.injectInputEvent
 *     has an extra int parameter (pid) → reflected method lookup now tries
 *     3-param form first, falls back to 2-param.
 *  3. MotionEvent.obtain deviceId -1 → 0 (virtual device). -1 is rejected by
 *     IInputManager on some Android 13 ROMs.
 *  4. pressure/touchMajor/touchMinor randomization used Math.random() (Java)
 *     inside a Kotlin object — replaced with kotlin.random.Random.
 *  5. injectMethod?.invoke failure path set ready=false but did not reset iim,
 *     so next call would re-invoke a stale iim reference. Reset both.
 *  6. Fallback to AimbotAccessibilityService.instance?.performAim — no null
 *     guard before dispatching, would NPE if service not connected. Added guard.
 */
object InputInjector {

    private val TAG = "InputInjector"
    private val rng = kotlin.random.Random(System.nanoTime())

    private var iim: Any?                                   = null
    private var injectMethod: java.lang.reflect.Method?     = null
    private var ready = false

    fun init(): Boolean {
        if (ready) return true
        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku binder not available")
                return false
            }

            // FIX: correct binder acquisition via ShizukuBinderWrapper
            val rawBinder = SystemServiceHelper.getSystemService("input")
            if (rawBinder == null) {
                Log.e(TAG, "getSystemService(input) returned null")
                return false
            }
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)

            val iimStubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface  = iimStubClass.getMethod("asInterface", android.os.IBinder::class.java)
            iim = asInterface.invoke(null, wrappedBinder)

            if (iim == null) {
                Log.e(TAG, "asInterface returned null")
                return false
            }

            // FIX: try Android 13+ 3-param form first, then fall back to 2-param
            injectMethod = try {
                iim!!.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType   // Android 13+ pid param
                )
            } catch (_: NoSuchMethodException) {
                iim!!.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
            }

            ready = true
            Log.d(TAG, "IInputManager ready (method params: ${injectMethod!!.parameterCount})")

        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}")
            iim = null
            injectMethod = null
            ready = false
        }
        return ready
    }

    fun injectSwipe(path: AimCalculator.AimPath) {
        if (!ready && !init()) {
            // FIX: null-safe fallback
            AimbotAccessibilityService.instance?.performAim(path)
            return
        }
        val steps = path.steps
        if (steps.isEmpty()) return

        val downTime = SystemClock.uptimeMillis()
        val props    = arrayOf(MotionEvent.PointerProperties().apply {
            id       = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        inject(downTime, downTime, MotionEvent.ACTION_DOWN,
            steps.first().x, steps.first().y, props)

        var t = downTime
        for (s in steps) {
            t += s.durationMs
            inject(downTime, t, MotionEvent.ACTION_MOVE, s.x, s.y, props)
        }

        inject(downTime, t + 6L, MotionEvent.ACTION_UP,
            steps.last().x, steps.last().y, props)
    }

    private fun inject(
        downTime: Long, eventTime: Long, action: Int,
        x: Float, y: Float,
        props: Array<MotionEvent.PointerProperties>
    ) {
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x     = x
            this.y     = y
            // FIX: use Kotlin rng not Math.random()
            pressure   = 0.5f + rng.nextFloat() * 0.3f
            size       = 0.1f
            touchMajor = 8f + rng.nextFloat() * 4f
            touchMinor = 6f + rng.nextFloat() * 3f
        })

        // FIX: deviceId -1 → 0 (virtual); -1 rejected on some Android 13 ROMs
        val ev = MotionEvent.obtain(
            downTime, eventTime, action,
            1, props, coords,
            0, 0,
            1f, 1f,
            0,                           // deviceId: 0 = virtual
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        try {
            val m = injectMethod ?: run { ready = false; return }
            when (m.parameterCount) {
                3    -> m.invoke(iim, ev, 0, android.os.Process.myPid())
                else -> m.invoke(iim, ev, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "inject failed: ${e.message}")
            // FIX: reset both so next call triggers full re-init
            ready = false
            iim   = null
            injectMethod = null
        } finally {
            ev.recycle()
        }
    }
}
