package com.example.arballs

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private val permissionRequest = 4711

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var arView: ArView
    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var hintView: TextView
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
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        statusView.text = "starting camera..."
        topBar.addView(statusView)

        messageView = TextView(this)
        messageView.setTextColor(Color.rgb(255, 170, 120))
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        messageView.visibility = View.GONE
        topBar.addView(messageView)

        val statusParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        statusParams.gravity = Gravity.TOP
        root.addView(topBar, statusParams)

        arView.onStatus = { text -> statusView.text = text }

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(Color.argb(140, 0, 0, 0))
        bottom.setPadding(12, 12, 12, 24)

        hintView = TextView(this)
        hintView.setTextColor(Color.argb(220, 230, 230, 230))
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        hintView.setPadding(12, 0, 12, 10)
        hintView.text = "Tap empty space to drop a ball. Drag a ball and let go to throw it. " +
                "Table -/+ moves the surface it lands on."
        bottom.addView(hintView)

        val buttonRow = LinearLayout(this)
        buttonRow.orientation = LinearLayout.HORIZONTAL
        buttonRow.addView(makeButton("Ball") { arView.addBall() })
        buttonRow.addView(makeButton("Center") { arView.recenter() })
        buttonRow.addView(makeButton("Table -") { arView.nudgeFloor(-0.05f) })
        buttonRow.addView(makeButton("Table +") { arView.nudgeFloor(0.05f) })
        buttonRow.addView(makeButton("Clear") { arView.clearBalls() })
        bottom.addView(
            buttonRow,
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            showMessage("waiting for camera permission")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), permissionRequest)
        }
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        b.setPadding(4, 4, 4, 4)
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(4, 0, 4, 0)
        b.layoutParams = lp
        return b
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
                    "Camera permission denied - the balls still work, the background is just black. " +
                            "Grant it in Settings > Apps > ARBalls > Permissions."
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
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
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

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(panel, lp)

        dismiss.setOnClickListener {
            runCatching { file.delete() }
            root.removeView(panel)
        }
        panel.visibility = View.VISIBLE
    }
}
