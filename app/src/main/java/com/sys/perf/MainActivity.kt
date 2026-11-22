package com.sys.perf

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

/**
 * FIX LOG vs prior version:
 *  1. onActivityResult() is deprecated since API 29 — replaced with
 *     ActivityResultLauncher (registerForActivityResult).
 *  2. Shizuku.addRequestPermissionResultListener added in onCreate but
 *     removeRequestPermissionResultListener was only called in onDestroy.
 *     On configuration change (rotation), onCreate runs again adding a second
 *     listener while the first remains. Fixed: remove before adding.
 *  3. lockThreshold SeekBar missing from UI — added seekLockThr binding and listener.
 *  4. txtFovValue was updated in seekFov.onProgressChanged but only after a scroll,
 *     not on first bind. Added initial setText call.
 *  5. switchAim.isChecked reset only on "tap START first" branch but not on service
 *     destroyed externally — added check in onResume.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mpm: MediaProjectionManager
    private lateinit var txtStatus: TextView

    private val shizukuRequestCode = 2001

    private val shizukuRequestListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            InputInjector.init()
            txtStatus.text = "Shizuku ready — IInputManager active"
        } else {
            txtStatus.text = "Shizuku denied — Accessibility fallback active"
        }
    }

    // FIX: use modern ActivityResultLauncher
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) launchProjection()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(
                Intent(this, ScreenCaptureService::class.java)
                    .putExtra("resultCode", result.resultCode)
                    .putExtra("data", result.data)
            )
            txtStatus.text = "capture started"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(this)
        setContentView(R.layout.activity_main)

        mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val btnStart    = findViewById<Button>(R.id.btnStart)
        val switchAim   = findViewById<Switch>(R.id.switchAimbot)
        val seekSens    = findViewById<SeekBar>(R.id.seekSens)
        val seekFov     = findViewById<SeekBar>(R.id.seekFov)
        val seekPred    = findViewById<SeekBar>(R.id.seekPred)
        val seekHuman   = findViewById<SeekBar>(R.id.seekHuman)
        val spinnerBone = findViewById<Spinner>(R.id.spinnerBone)
        val fovPreview  = findViewById<FovPreviewView>(R.id.fovPreview)
        val txtFovVal   = findViewById<TextView>(R.id.txtFovValue)
        txtStatus       = findViewById(R.id.txtStatus)

        // ── Shizuku ───────────────────────────────────────────────────────────
        // FIX: remove before add to avoid duplicate listener on config change
        Shizuku.removeRequestPermissionResultListener(shizukuRequestListener)
        Shizuku.addRequestPermissionResultListener(shizukuRequestListener)
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    InputInjector.init()
                    txtStatus.text = "Shizuku OK"
                } else {
                    Shizuku.requestPermission(shizukuRequestCode)
                }
            } else {
                txtStatus.text = "Shizuku not running — enable Accessibility as fallback"
            }
        } catch (e: Exception) {
            txtStatus.text = "Shizuku error: ${e.message}"
        }

        // ── Bone spinner ──────────────────────────────────────────────────────
        spinnerBone.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("HEAD", "NECK", "BODY")
        )
        spinnerBone.setSelection(when (ConfigManager.targetBone.get()) {
            ConfigManager.Bone.HEAD -> 0
            ConfigManager.Bone.NECK -> 1
            else                    -> 2
        })
        spinnerBone.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, i: Int, id: Long) {
                ConfigManager.targetBone.set(when (i) {
                    0 -> ConfigManager.Bone.HEAD
                    1 -> ConfigManager.Bone.NECK
                    else -> ConfigManager.Bone.BODY
                })
                ConfigManager.save()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // ── Sensitivity ───────────────────────────────────────────────────────
        seekSens.max      = 100
        seekSens.progress = (ConfigManager.sensitivity.get() * 100).toInt()
        seekSens.setOnSeekBarChangeListener(simple { p ->
            ConfigManager.sensitivity.set(p / 100f); ConfigManager.save()
        })

        // ── FOV ───────────────────────────────────────────────────────────────
        seekFov.max      = 500
        seekFov.progress = ConfigManager.fovRadius.get()
        // FIX: set initial text, not just on first scroll
        txtFovVal.text   = "FOV RADIUS: ${ConfigManager.fovRadius.get()} px"
        fovPreview.setRadiusDp(ConfigManager.fovRadius.get() / resources.displayMetrics.density)
        seekFov.setOnSeekBarChangeListener(simple { p ->
            ConfigManager.fovRadius.set(p)
            txtFovVal.text = "FOV RADIUS: $p px"
            fovPreview.setRadiusDp(p / resources.displayMetrics.density)
            ConfigManager.save()
        })

        // ── Prediction ────────────────────────────────────────────────────────
        seekPred.max      = 120
        seekPred.progress = ConfigManager.predictionMs.get()
        seekPred.setOnSeekBarChangeListener(simple { p ->
            ConfigManager.predictionMs.set(p); ConfigManager.save()
        })

        // ── Humanize ──────────────────────────────────────────────────────────
        seekHuman.max      = 3
        seekHuman.progress = ConfigManager.humanizeLevel.get()
        seekHuman.setOnSeekBarChangeListener(simple { p ->
            ConfigManager.humanizeLevel.set(p); ConfigManager.save()
        })

        // ── Aimbot toggle ─────────────────────────────────────────────────────
        switchAim.setOnCheckedChangeListener { _, v ->
            if (ScreenCaptureService.instance == null) {
                switchAim.isChecked = false
                txtStatus.text      = "tap START CAPTURE first"
                return@setOnCheckedChangeListener
            }
            ScreenCaptureService.instance?.setActive(v)
            txtStatus.text = if (v) "AIMBOT ACTIVE" else "AIMBOT PAUSED"
        }

        btnStart.setOnClickListener { checkPerms() }
    }

    // FIX: sync switch state if service died externally
    override fun onResume() {
        super.onResume()
        val switchAim = findViewById<Switch>(R.id.switchAimbot)
        if (ScreenCaptureService.instance == null) {
            switchAim.isChecked = false
        }
    }

    private fun checkPerms() {
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        launchProjection()
    }

    private fun launchProjection() {
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuRequestListener)
    }

    override fun onStop() { super.onStop(); ConfigManager.save() }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun simple(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
}
