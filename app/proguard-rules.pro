# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Shizuku / Rikka
-keep class rikka.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.**
-dontwarn moe.shizuku.**

# App services and activities
-keep class com.sys.perf.AimbotAccessibilityService { *; }
-keep class com.sys.perf.ScreenCaptureService { *; }
-keep class com.sys.perf.MainActivity { *; }
-keep class com.sys.perf.InputInjector { *; }

# FIX: data classes and objects used via reflection / ONNX output must not be stripped
-keep class com.sys.perf.Detection { *; }
-keep class com.sys.perf.KalmanTracker { *; }
-keep class com.sys.perf.KalmanTracker$FPoint { *; }
-keep class com.sys.perf.AimCalculator { *; }
-keep class com.sys.perf.AimCalculator$StepPoint { *; }
-keep class com.sys.perf.AimCalculator$AimPath { *; }
-keep class com.sys.perf.ConfigManager { *; }
-keep class com.sys.perf.ConfigManager$Bone { *; }
-keep class com.sys.perf.EnemyDetector { *; }
-keep class com.sys.perf.OnnxDetector { *; }
-keep class com.sys.perf.TargetLock { *; }
-keep class com.sys.perf.FrameProcessor { *; }

# Kotlin coroutines / stdlib
-dontwarn kotlin.**
-keep class kotlin.** { *; }

# Keep Kotlin metadata for reflection
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes Signature
