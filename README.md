# Free Fire OB55 Aimbot — Fixed Build

## Fix Log (phân tích toàn bộ lỗi)

### EnemyDetector.kt
- **Mat leak**: MatOfPoint contours không được `release()` → native heap leak per frame → OOM sau vài phút. Fixed: `try/finally { c.release() }` trong loop.
- **HSV outline sai**: OB55 dùng cyan outline shader (H=78-105), code cũ dùng H=32-88 (green) → miss đại đa số targets. Fixed.
- **HSV red S-floor**: 130 → 80 → bắt được HP bar mờ ở xa.
- **kDil5 width**: 7→5 → không merge bar của 2 enemy đứng cạnh nhau.
- **Area ceiling**: 20000→28000 → không clip target gần.
- **Bar height threshold**: 26→30 cho hi-DPI OB55.
- **Body height estimator**: coefficient 2.8→2.4 → không over-estimate skeleton.
- **Edge margin**: 32→24 → không reject target gần viền FOV.
- **nzRatio denominator**: dùng `rect.area()` thay `sr.area()` (sr có thể bị clip).
- **nameAbove factor**: 2.5→1.8 → ít false positive hơn.
- **Confidence weights**: bar=0.40, blob=0.40, name=0.20 (cân bằng hơn).

### OnnxDetector.kt
- **Bitmap không recycle trên early-return** → leak. Fixed: finally block.
- **results.close() trước khi đọc value** → NPE / empty result. Fixed: đọc value trước, close sau.
- **Output shape giả định [1,100,6]**: một số YOLOv8 export ra [1,6,100] (transposed) → parse sai hoàn toàn. Fixed: runtime shape check + 2 parse path.
- **bitmapToChw stride**: dùng `INPUT_SIZE` làm stride → crash nếu bitmap stride khác. Fixed: `bmp.rowBytes / 4`.
- **NNAPI addNnapi() throw** trên nhiều device → crash init. Fixed: try/catch, fallback CPU.
- **env.close()** trong `close()` → đóng singleton ORT environment → crash re-init. Removed.
- **CONF_THRESH**: 0.35→0.30 cho OB55 model.
- **Scaled bitmap cache**: tái sử dụng thay vì allocate mới mỗi frame → giảm GC pressure.

### KalmanTracker.kt
- **Mat.put(row, col, Double)**: không phải API hợp lệ của OpenCV Android → `MethodNotFoundException` runtime. Fixed: `Mat.put(row, col, doubleArrayOf(value))` trên tất cả call.
- **reset() không zero statePost**: lock mới kế thừa vị trí cũ → aim lệch khi đổi target. Fixed.
- **Velocity tính sai**: `velX = cx - lastX` nhưng `lastX` đã được update trước → velX luôn = 0. Fixed ordering.
- **predictAhead(n) trước update**: trả về (0,0). Fixed: guard `!initialized`.
- **Process noise**: 4e-3→1e-2, measurement noise: 1e-2→5e-3 (target FF di chuyển nhanh).

### TargetLock.kt
- **`detections.minByOrNull { }!!`**: crash NullPointerException khi list empty sau isEmpty() guard (race). Fixed: safe `?: return null`.
- **MAX_AGE**: 10→6 (80ms stale lock ở 125fps quá lâu).
- **SWITCH_HYSTERESIS**: 70→55 (quá conservative, hiếm khi switch được sang target gần hơn).
- **FOV rejection**: dùng `fovRadius * 1.05f` thay vì exact `fovRadius` → không drop target ở viền FOV.

### AimCalculator.kt
- **Bezier control point sai**: perp vector dùng lại scaled delta thay vì unit vector → arc bị distorted. Fixed: perp từ normalized direction.
- **sens scaling double-applied**: dx/dy đã scale bởi sens, end point lại scale thêm lần nữa → aim vượt target. Fixed: sens apply một lần tại step interpolation.
- **steps floor/ceil**: 3→2 / 24→32.
- **baseMs**: 6→5ms, timing table tightened.

### InputInjector.kt
- **Binder acquisition hoàn toàn sai**: `Shizuku.newProcess("service call input 1")` spawn child process, không lấy được IBinder → `iim` luôn null, injection luôn fallback sang Accessibility. Fixed: `ShizukuBinderWrapper` + `SystemServiceHelper.getSystemService("input")`.
- **injectMethod signature Android 13+**: `IInputManager.injectInputEvent` có thêm param `pid` → `NoSuchMethodException`. Fixed: thử 3-param trước, fallback 2-param.
- **deviceId -1**: bị reject bởi IInputManager trên một số Android 13 ROM. Fixed: 0.
- **Math.random()**: thay bằng Kotlin Random.
- **reset sau fail**: chỉ reset `ready` không reset `iim` → invoke stale iim. Fixed: reset cả hai.

### FrameProcessor.kt
- **Loop khởi động trước ONNX init xong**: thêm `onnxReady` gate, loop chỉ post sau khi init resolve.
- **Bitmap recycle sau processFrame**: explicit recycle trong finally.
- **stopped flag**: tránh loop chạy thêm 1 lần sau `shutdown()`.

### ScreenCaptureService.kt
- **ImageReader stride**: `bmpW = sw + (rs - ps*sw)/ps` thay bằng `rs/ps` đúng.
- **VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR**: bỏ → không hiện casting indicator.
- **Teardown order Android 14+**: `vDisplay → reader → proc → projection.stop()`.
- **WindowManager.removeView crash**: wrapped try/catch.

### AimbotAccessibilityService.kt
- **Path duplicate first segment**: `moveTo(first)` rồi loop lineTo tất cả kể cả first → segment zero-length bị reject. Fixed: loop từ index 1.
- **Single-step path**: chỉ có moveTo, không có lineTo → gesture bị reject. Fixed: minimal lineTo offset.
- **dispatchGesture throw**: dispatching flag không được clear. Fixed: try/finally.

### ConfigManager.kt
- **lockThreshold không persist**: không có trong `init()` và `save()` → reset về default mỗi lần mở app.
- **predictionMs default**: 48→32ms.

### MainActivity.kt
- **onActivityResult deprecated** (API 29) → thay ActivityResultLauncher.
- **Shizuku listener leak**: thêm listener mỗi lần onCreate (rotation) → duplicate. Fixed: remove trước add.
- **txtFovValue không set lúc bind**: chỉ update khi scroll. Fixed: initial setText.

### AndroidManifest.xml
- **ACCESS_NETWORK_STATE**: thiếu, cần cho Shizuku IPC Android 12+.
- **INTERACT_ACROSS_USERS_FULL**: thêm uses-permission cho ShizukuProvider.

### app/build.gradle
- **activity-ktx thiếu**: cần cho `registerForActivityResult`.

### proguard-rules.pro
- **Data classes bị R8 strip**: Detection, KalmanTracker.FPoint, AimCalculator.AimPath, ConfigManager.Bone bị obfuscate → NPE runtime. Fixed: thêm keep rules.

### .github/workflows/build.yml
- **sdkmanager --licenses**: `yes |` bị block trên một số runner → `printf 'y\n'`.
- **NDK env var**: `ANDROID_NDK_ROOT` → `ANDROID_NDK_HOME` (tên Gradle nhận diện).
- **Gradle wrapper jar**: không verify jar tồn tại sau `gradle wrapper` → build fail silently.

## Build

Push lên GitHub → Actions → Build Aimbot APK → trigger manual hoặc push to main.

APK download từ Artifacts sau khi build xong (~5-8 phút).

## Setup trên device

1. Install APK
2. Grant **Overlay permission**
3. Bật **Shizuku** (adb: `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`) hoặc root
4. Mở app → grant Shizuku permission
5. Fallback: bật **Accessibility → Touch Enhancer** nếu không có Shizuku
6. **START CAPTURE** → grant screen capture
7. Vào game FF → bật switch AIMBOT ON
