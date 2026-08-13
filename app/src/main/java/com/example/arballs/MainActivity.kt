package com.example.arballs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val permissionRequest = 4711

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var arView: ArView
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var objButton: Button
    private lateinit var lockButton: Button
    private lateinit var mapButton: Button
    private var analysisExecutor: ExecutorService? = null
    private var cameraStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        previewView = PreviewView(this)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        arView = ArView(this)
        root.addView(
            arView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.VERTICAL
        topBar.setBackgroundColor(Color.argb(120, 0, 0, 0))
        topBar.setPadding(24, 36, 24, 12)

        statusView = TextView(this)
        statusView.setTextColor(Color.WHITE)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        statusView.text = "starting camera..."
        topBar.addView(statusView)

        messageView = TextView(this)
        messageView.setTextColor(Color.rgb(255, 170, 120))
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        messageView.visibility = View.GONE
        topBar.addView(messageView)

        val topParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        topParams.gravity = Gravity.TOP
        root.addView(topBar, topParams)

        arView.onStatus = { text -> statusView.text = text }

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(Color.argb(140, 0, 0, 0))
        bottom.setPadding(10, 10, 10, 22)

        val hint = TextView(this)
        hint.setTextColor(Color.argb(220, 230, 230, 230))
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        hint.setPadding(10, 0, 10, 8)
        hint.text = "Point at an empty part of the table and press Scan. " +
                "Red squares are what the app thinks are real objects - the ball bounces off those."
        bottom.addView(hint)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(makeButton("Ball") { arView.addBall() })
        row1.addView(makeButton("Center") { arView.recenter() })
        row1.addView(makeButton("Clear") { arView.clearBalls() })
        row1.addView(makeButton("Scan") { arView.rescanTable() })
        lockButton = makeButton("Lock on") {
            arView.flowEnabled = !arView.flowEnabled
            lockButton.text = if (arView.flowEnabled) "Lock on" else "Lock off"
        }
        row1.addView(lockButton)
        bottom.addView(
            row1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(makeButton("Table -") { arView.nudgeFloor(-0.05f) })
        row2.addView(makeButton("Table +") { arView.nudgeFloor(0.05f) })
        objButton = makeButton(arView.sensitivityLabel()) {
            objButton.text = arView.cycleSensitivity()
        }
        row2.addView(objButton)
        mapButton = makeButton("Map on") {
            arView.showMap = !arView.showMap
            mapButton.text = if (arView.showMap) "Map on" else "Map off"
        }
        row2.addView(mapButton)
        bottom.addView(
            row2,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val bottomParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bottomParams.gravity = Gravity.BOTTOM
        root.addView(bottom, bottomParams)

        setContentView(root)

        showCrashIfAny()

        val fNorm = readFocalNorm()
        if (fNorm > 0f) {
            arView.setFocalNorm(fNorm)
        } else {
            showMessage("Could not read the lens data, using a default field of view.")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            showMessage("waiting for camera permission")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), permissionRequest)
        }
    }

    override fun onDestroy() {
        analysisExecutor?.shutdown()
        analysisExecutor = null
        super.onDestroy()
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        b.setPadding(2, 2, 2, 2)
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(3, 0, 3, 0)
        b.layoutParams = lp
        return b
    }

    /**
     * Focal length expressed in image widths, straight from the lens data.
     * This replaces the guessed field of view and is what makes the ball move
     * with the picture instead of sliding across it.
     */
    private fun readFocalNorm(): Float {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val physical = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focals == null || physical == null) continue
                if (focals.isEmpty() || physical.width <= 0f) continue
                val f = focals[0] / physical.width
                if (f > 0.4f && f < 3f) return f
            }
        } catch (e: Exception) {
            return 0f
        }
        return 0f
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                hideMessage()
                startCamera()
            } else {
                showMessage(
                    "Camera permission denied - the balls still work, the background is just " +
                            "black and nothing can be detected. Grant it in " +
                            "Settings > Apps > ARBalls > Permissions."
                )
            }
        }
    }

    private fun showMessage(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        messageView.visibility = View.GONE
    }

    private fun startCamera() {
        if (cameraStarted) return
        val executor = analysisExecutor ?: Executors.newSingleThreadExecutor()
        analysisExecutor = executor
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, FrameGrabber { ds -> arView.submitFrame(ds) })

                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                cameraStarted = true
                hideMessage()
            } catch (e: Exception) {
                showMessage(
                    "Camera failed to start: " + e.javaClass.simpleName + " " + (e.message ?: "")
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------ crash reporting

    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last-crash.txt")
                    .writeText(android.util.Log.getStackTraceString(error))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showCrashIfAny() {
        val file = File(filesDir, "last-crash.txt")
        if (!file.exists()) return
        val text = try {
            file.readText()
        } catch (e: Exception) {
            "crash file could not be read: " + e
        }

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.argb(240, 10, 10, 10))
        panel.setPadding(28, 60, 28, 28)

        val title = TextView(this)
        title.text = "The app crashed last time. Stack trace:"
        title.setTextColor(Color.rgb(255, 120, 120))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        panel.addView(title)

        val body = TextView(this)
        body.text = text
        body.setTextColor(Color.rgb(230, 230, 230))
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        body.setTextIsSelectable(true)
        val scroll = ScrollView(this)
        scroll.addView(body)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val dismiss = Button(this)
        dismiss.text = "Dismiss"
        dismiss.isAllCaps = false
        panel.addView(dismiss)

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        dismiss.setOnClickListener {
            runCatching { file.delete() }
            root.removeView(panel)
        }
    }
}
