package com.example.arballs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class Ball(var x: Float, var y: Float, var z: Float, var r: Float, var color: Int) {
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var grabbed = false
    var grabDepth = 0.7f
}

/**
 * Balls live in a room-fixed frame: x and y horizontal, z straight up, origin
 * at the phone. Orientation comes from the rotation sensor, then gets pulled
 * back into agreement with the camera image by measuring how the picture
 * itself moved. That second half is what stops the surface sliding around.
 */
class ArView(context: Context) : View(context), SensorEventListener {

    companion object {
        const val GRID = 48
        const val CELL = 0.05f
        const val HALF = GRID * CELL / 2f
        const val OBJ_H = 0.08f
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var rotationSensor: Sensor? = null

    private val lock = Any()

    /** raw sensor orientation, device -> world, row major */
    private val rotSensor = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f
    )

    /** slow correction in world axes, applied on the left of rotSensor */
    private val corr = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** what rendering actually uses: corr * rotSensor */
    private val rotEff = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    private val rvBuf4 = FloatArray(4)
    private val rvBuf3 = FloatArray(3)
    private var haveSensor = false

    private val balls = ArrayList<Ball>()
    private val order = ArrayList<Ball>()

    @Volatile
    private var floorZ = -0.35f

    private val palette = intArrayOf(
        0xFFFF7043.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(),
        0xFFFFCA28.toInt(), 0xFFAB47BC.toInt(), 0xFF26C6DA.toInt()
    )
    private var colorIndex = 0

    // ---- camera model -------------------------------------------------------

    /** focal length expressed in image widths; 0 until the real value arrives */
    @Volatile
    private var focalNorm = 0f

    private var focal = 1500f
    private var cx = 540f
    private var cy = 1000f

    /** upright image size and the scale that maps it onto the view */
    @Volatile private var uprightW = 0
    @Volatile private var uprightH = 0
    @Volatile private var viewScale = 1f
    @Volatile private var offX = 0f
    @Volatile private var offY = 0f
    @Volatile private var frameRotation = 0
    @Volatile private var frameStep = 1
    @Volatile private var frameSrcW = 0
    @Volatile private var frameSrcH = 0

    // ---- image based stabilisation -----------------------------------------

    @Volatile var flowEnabled = true
    private var prevY: IntArray? = null
    private var prevValid = false
    private val rotPrev = FloatArray(9)
    private var havePrevRot = false
    @Volatile private var flowState = "starting"

    // ---- obstacle map -------------------------------------------------------

    private val conf = FloatArray(GRID * GRID)
    private val blocked = BooleanArray(GRID * GRID)
    @Volatile private var blockedCount = 0
    @Volatile var sensitivity = 2          // 0 off, 1 low, 2 medium, 3 high
    @Volatile var showMap = true
    private val sensThresholds = floatArrayOf(0f, 42f, 27f, 17f)

    private var tableLearned = false
    private var tableY = 0f
    private var tableU = 128f
    private var tableV = 128f
    @Volatile private var scanRequested = true

    // ---- scratch ------------------------------------------------------------

    private val proj = FloatArray(3)
    private val world = FloatArray(3)
    private val p0 = FloatArray(3)
    private val p1 = FloatArray(3)
    private val p2 = FloatArray(3)
    private val refPt = FloatArray(3)
    private val matA = FloatArray(9)
    private val matB = FloatArray(9)
    private val matC = FloatArray(9)
    private val rotNow = FloatArray(9)

    // ---- paints -------------------------------------------------------------

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(60, 120, 255, 210)
    }
    private val objFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 255, 90, 60)
    }
    private val objEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(150, 255, 140, 90)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(110, 0, 0, 0)
    }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
    }

    private val gridPts = FloatArray(4096)
    private var gridCount = 0
    private val shadowPath = Path()
    private val objPath = Path()

    private var lastFrameNanos = 0L
    private var smoothedFps = 60f
    private var lastStatusMs = 0L
    var onStatus: ((String) -> Unit)? = null

    private var dragBall: Ball? = null
    private var dragVx = 0f
    private var dragVy = 0f
    private var dragVz = 0f
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var lastDragZ = 0f
    private var lastDragNanos = 0L

    // =========================================================== lifecycle

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        updateCameraModel()
        if (balls.isEmpty()) addBall()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val s = rotationSensor
        if (s != null) sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        lastFrameNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        sensorManager.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val n = event.values.size
        synchronized(lock) {
            if (n >= 4) {
                var i = 0
                while (i < 4) { rvBuf4[i] = event.values[i]; i++ }
                SensorManager.getRotationMatrixFromVector(rotSensor, rvBuf4)
                haveSensor = true
            } else if (n == 3) {
                var i = 0
                while (i < 3) { rvBuf3[i] = event.values[i]; i++ }
                SensorManager.getRotationMatrixFromVector(rotSensor, rvBuf3)
                haveSensor = true
            }
        }
    }

    // =========================================================== camera model

    /** focal length in image widths, from the camera's own characteristics */
    fun setFocalNorm(f: Float) {
        if (f > 0.4f && f < 3f) {
            focalNorm = f
            updateCameraModel()
        }
    }

    private fun updateCameraModel() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val uw = uprightW
        val uh = uprightH
        if (uw <= 0 || uh <= 0 || focalNorm <= 0f) {
            // fall back to a 58 degree vertical field of view
            focal = (vh / 2f) / tan(Math.toRadians(29.0)).toFloat()
            viewScale = 1f
            offX = 0f
            offY = 0f
            return
        }
        val sx = vw / uw
        val sy = vh / uh
        val s = if (sx > sy) sx else sy      // PreviewView FILL_CENTER
        viewScale = s
        offX = (vw - uw * s) / 2f
        offY = (vh - uh * s) / 2f
        // square pixels: focal in image pixels depends on the sensor width only
        focal = focalNorm * frameSrcW * s
    }

    // =========================================================== geometry

    private fun projectR(r: FloatArray, px: Float, py: Float, pz: Float, out: FloatArray): Boolean {
        val dx = r[0] * px + r[3] * py + r[6] * pz
        val dy = r[1] * px + r[4] * py + r[7] * pz
        val dz = r[2] * px + r[5] * py + r[8] * pz
        val depth = -dz
        if (depth < 0.05f) return false
        out[0] = cx + focal * dx / depth
        out[1] = cy - focal * dy / depth
        out[2] = depth
        return true
    }

    private fun project(px: Float, py: Float, pz: Float): Boolean =
        projectR(rotEff, px, py, pz, proj)

    private fun depthOf(b: Ball): Float =
        -(rotEff[2] * b.x + rotEff[5] * b.y + rotEff[8] * b.z)

    private fun unprojectR(r: FloatArray, sx: Float, sy: Float, depth: Float, out: FloatArray) {
        val dx = (sx - cx) * depth / focal
        val dy = -(sy - cy) * depth / focal
        val dz = -depth
        out[0] = r[0] * dx + r[1] * dy + r[2] * dz
        out[1] = r[3] * dx + r[4] * dy + r[5] * dz
        out[2] = r[6] * dx + r[7] * dy + r[8] * dz
    }

    private fun unproject(sx: Float, sy: Float, depth: Float) =
        unprojectR(rotEff, sx, sy, depth, world)

    private fun mul3(a: FloatArray, b: FloatArray, out: FloatArray) {
        var i = 0
        while (i < 3) {
            var j = 0
            while (j < 3) {
                out[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
                j++
            }
            i++
        }
    }

    private fun axisRotation(ax: Float, ay: Float, az: Float, angle: Float, out: FloatArray) {
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-6f) {
            out[0] = 1f; out[1] = 0f; out[2] = 0f
            out[3] = 0f; out[4] = 1f; out[5] = 0f
            out[6] = 0f; out[7] = 0f; out[8] = 1f
            return
        }
        val x = ax / len
        val y = ay / len
        val z = az / len
        val c = cos(angle)
        val s = sin(angle)
        val t = 1f - c
        out[0] = t * x * x + c
        out[1] = t * x * y - s * z
        out[2] = t * x * z + s * y
        out[3] = t * x * y + s * z
        out[4] = t * y * y + c
        out[5] = t * y * z - s * x
        out[6] = t * x * z - s * y
        out[7] = t * y * z + s * x
        out[8] = t * z * z + c
    }

    private fun orthonormalize(m: FloatArray) {
        var n0 = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        if (n0 < 1e-6f) return
        m[0] /= n0; m[1] /= n0; m[2] /= n0
        val d = m[3] * m[0] + m[4] * m[1] + m[5] * m[2]
        m[3] -= d * m[0]; m[4] -= d * m[1]; m[5] -= d * m[2]
        n0 = sqrt(m[3] * m[3] + m[4] * m[4] + m[5] * m[5])
        if (n0 < 1e-6f) return
        m[3] /= n0; m[4] /= n0; m[5] /= n0
        m[6] = m[1] * m[5] - m[2] * m[4]
        m[7] = m[2] * m[3] - m[0] * m[5]
        m[8] = m[0] * m[4] - m[1] * m[3]
    }

    // =========================================================== public api

    fun addBall() = spawnAt(cx, cy * 0.75f)

    fun clearBalls() {
        balls.clear()
        dragBall = null
    }

    fun recenter() {
        synchronized(lock) {
            corr[0] = 1f; corr[1] = 0f; corr[2] = 0f
            corr[3] = 0f; corr[4] = 1f; corr[5] = 0f
            corr[6] = 0f; corr[7] = 0f; corr[8] = 1f
        }
        mul3(corr, rotSensor, rotEff)
        if (balls.isEmpty()) {
            addBall()
            return
        }
        unproject(cx, cy * 0.75f, 0.7f)
        val bx = world[0]
        val by = world[1]
        val bz = world[2]
        var k = 0
        for (b in balls) {
            b.x = bx + (k % 3 - 1) * 0.09f
            b.y = by
            var z = bz
            val minZ = floorZ + b.r + 0.25f
            if (z < minZ) z = minZ
            b.z = z + k * 0.12f
            b.vx = 0f; b.vy = 0f; b.vz = 0f
            b.grabbed = false
            k++
        }
        dragBall = null
    }

    fun nudgeFloor(delta: Float) {
        var v = floorZ + delta
        if (v < -1.5f) v = -1.5f
        if (v > -0.08f) v = -0.08f
        floorZ = v
        java.util.Arrays.fill(conf, 0f)
        java.util.Arrays.fill(blocked, false)
        blockedCount = 0
    }

    fun rescanTable() {
        scanRequested = true
        java.util.Arrays.fill(conf, 0f)
        java.util.Arrays.fill(blocked, false)
        blockedCount = 0
    }

    fun sensitivityLabel(): String = when (sensitivity) {
        0 -> "Obj: off"
        1 -> "Obj: low"
        2 -> "Obj: med"
        else -> "Obj: high"
    }

    fun cycleSensitivity(): String {
        sensitivity = (sensitivity + 1) % 4
        if (sensitivity == 0) {
            java.util.Arrays.fill(blocked, false)
            blockedCount = 0
        }
        return sensitivityLabel()
    }

    // =========================================================== frame analysis

    /** Called on the camera analysis thread. */
    fun submitFrame(ds: Downsampled) {
        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return

        val uw: Int
        val uh: Int
        if (ds.rotation == 90 || ds.rotation == 270) {
            uw = ds.srcH; uh = ds.srcW
        } else {
            uw = ds.srcW; uh = ds.srcH
        }
        if (uw != uprightW || uh != uprightH || ds.rotation != frameRotation ||
            ds.srcW != frameSrcW || ds.step != frameStep
        ) {
            uprightW = uw
            uprightH = uh
            frameRotation = ds.rotation
            frameSrcW = ds.srcW
            frameSrcH = ds.srcH
            frameStep = ds.step
            prevValid = false
            post { updateCameraModel() }
            updateCameraModel()
        }

        var prev = prevY
        if (prev == null || prev.size != ds.y.size) {
            prev = IntArray(ds.y.size)
            prevY = prev
            prevValid = false
        }

        synchronized(lock) { mul3(corr, rotSensor, rotNow) }

        if (flowEnabled && prevValid && havePrevRot) {
            trackImage(ds, prev)
        } else {
            flowState = if (!flowEnabled) "off" else "warming"
        }

        if (scanRequested) {
            learnTable(ds)
            scanRequested = false
        }
        if (sensitivity > 0 && tableLearned) {
            updateObstacles(ds, rotNow)
        }

        System.arraycopy(ds.y, 0, prev, 0, ds.y.size)
        prevValid = true
        System.arraycopy(rotNow, 0, rotPrev, 0, 9)
        havePrevRot = true
    }

    /** upright image delta -> downsampled sensor delta */
    private fun uprightToSensorDx(dux: Float, duy: Float): Float = when (frameRotation) {
        90 -> duy
        180 -> -dux
        270 -> -duy
        else -> dux
    }

    private fun uprightToSensorDy(dux: Float, duy: Float): Float = when (frameRotation) {
        90 -> -dux
        180 -> -duy
        270 -> dux
        else -> duy
    }

    /** downsampled sensor delta -> upright image delta */
    private fun sensorToUprightDx(dsx: Float, dsy: Float): Float = when (frameRotation) {
        90 -> -dsy
        180 -> -dsx
        270 -> dsy
        else -> dsx
    }

    private fun sensorToUprightDy(dsx: Float, dsy: Float): Float = when (frameRotation) {
        90 -> dsx
        180 -> -dsy
        270 -> -dsx
        else -> dsy
    }

    /** screen point -> index into the downsampled frame, or -1 */
    private fun screenToDs(ds: Downsampled, sx: Float, sy: Float): Int {
        val s = viewScale
        if (s <= 0f) return -1
        val ux = (sx - offX) / s
        val uy = (sy - offY) / s
        if (ux < 0f || uy < 0f || ux >= uprightW || uy >= uprightH) return -1
        val sxi: Int
        val syi: Int
        when (frameRotation) {
            90 -> { sxi = uy.toInt(); syi = ds.srcH - 1 - ux.toInt() }
            180 -> { sxi = ds.srcW - 1 - ux.toInt(); syi = ds.srcH - 1 - uy.toInt() }
            270 -> { sxi = ds.srcW - 1 - uy.toInt(); syi = ux.toInt() }
            else -> { sxi = ux.toInt(); syi = uy.toInt() }
        }
        val i = sxi / ds.step
        val j = syi / ds.step
        if (i < 0 || j < 0 || i >= ds.dw || j >= ds.dh) return -1
        return j * ds.dw + i
    }

    /**
     * Measures how far the picture moved since the last frame and nudges the
     * world orientation until the virtual content moves the same way.
     */
    private fun trackImage(ds: Downsampled, prev: IntArray) {
        // where the gyro alone says a fixed point should have moved on screen
        unprojectR(rotPrev, cx, cy, 2f, refPt)
        if (!projectR(rotNow, refPt[0], refPt[1], refPt[2], p0)) {
            flowState = "weak"
            return
        }
        val predSx = p0[0] - cx
        val predSy = p0[1] - cy

        val s = viewScale
        if (s <= 0f) return
        val pux = predSx / s
        val puy = predSy / s
        var predDsX = (uprightToSensorDx(pux, puy) / ds.step).toInt()
        var predDsY = (uprightToSensorDy(pux, puy) / ds.step).toInt()
        val maxPred = ds.dw / 5
        if (predDsX > maxPred) predDsX = maxPred
        if (predDsX < -maxPred) predDsX = -maxPred
        if (predDsY > maxPred) predDsY = maxPred
        if (predDsY < -maxPred) predDsY = -maxPred

        val win = 4
        var margin = win + 2
        margin += if (abs(predDsX) > abs(predDsY)) abs(predDsX) else abs(predDsY)
        if (ds.dw < 3 * margin + 6 || ds.dh < 3 * margin + 6) {
            flowState = "weak"
            return
        }

        var bestScore = Float.MAX_VALUE
        var bestX = 0
        var bestY = 0
        var total = 0f
        var tries = 0
        val w = ds.dw

        var oy = -win
        while (oy <= win) {
            var ox = -win
            while (ox <= win) {
                val shx = predDsX + ox
                val shy = predDsY + oy
                var sum = 0f
                var count = 0
                var yy = margin
                while (yy < ds.dh - margin) {
                    val rowCur = yy * w
                    val rowPrev = (yy - shy) * w
                    var xx = margin
                    while (xx < w - margin) {
                        val a = ds.y[rowCur + xx]
                        val b = prev[rowPrev + xx - shx]
                        sum += abs(a - b).toFloat()
                        count++
                        xx += 3
                    }
                    yy += 3
                }
                if (count > 0) {
                    val score = sum / count
                    total += score
                    tries++
                    if (score < bestScore) {
                        bestScore = score
                        bestX = shx
                        bestY = shy
                    }
                }
                ox++
            }
            oy++
        }

        if (tries < 4 || bestScore == Float.MAX_VALUE) {
            flowState = "weak"
            return
        }
        val mean = total / tries
        // a flat scene or a moving subject gives no usable peak
        if (bestScore > mean * 0.88f || mean < 1.5f) {
            flowState = "weak"
            return
        }
        flowState = "locked"

        val fullX = bestX.toFloat() * ds.step
        val fullY = bestY.toFloat() * ds.step
        val measSx = sensorToUprightDx(fullX, fullY) * s
        val measSy = sensorToUprightDy(fullX, fullY) * s

        var rx = measSx - predSx
        var ry = measSy - predSy
        if (abs(rx) > 250f || abs(ry) > 250f) return
        // ignore sub pixel noise
        if (abs(rx) < 0.6f) rx = 0f
        if (abs(ry) < 0.6f) ry = 0f
        if (rx == 0f && ry == 0f) return

        // world axes that currently point right and up on screen
        val ux0 = rotNow[0]; val ux1 = rotNow[3]; val ux2 = rotNow[6]
        val uy0 = rotNow[1]; val uy1 = rotNow[4]; val uy2 = rotNow[7]

        val eps = 0.004f
        axisRotation(ux0, ux1, ux2, eps, matA)
        mul3(matA, rotNow, matB)
        if (!projectR(matB, refPt[0], refPt[1], refPt[2], p1)) return
        axisRotation(uy0, uy1, uy2, eps, matA)
        mul3(matA, rotNow, matB)
        if (!projectR(matB, refPt[0], refPt[1], refPt[2], p2)) return

        val j00 = (p1[0] - p0[0]) / eps
        val j10 = (p1[1] - p0[1]) / eps
        val j01 = (p2[0] - p0[0]) / eps
        val j11 = (p2[1] - p0[1]) / eps
        val det = j00 * j11 - j01 * j10
        if (abs(det) < 1e-2f) return

        val gain = 0.3f
        val tx = gain * rx
        val ty = gain * ry
        var ax = (j11 * tx - j01 * ty) / det
        var ay = (-j10 * tx + j00 * ty) / det
        val lim = 0.02f
        if (ax > lim) ax = lim
        if (ax < -lim) ax = -lim
        if (ay > lim) ay = lim
        if (ay < -lim) ay = -lim

        synchronized(lock) {
            axisRotation(ux0, ux1, ux2, ax, matA)
            mul3(matA, corr, matC)
            axisRotation(uy0, uy1, uy2, ay, matA)
            mul3(matA, matC, corr)
            orthonormalize(corr)
            mul3(corr, rotSensor, rotNow)
        }
    }

    private fun learnTable(ds: Downsampled) {
        var sy = 0f
        var su = 0f
        var sv = 0f
        var n = 0
        val x0 = ds.dw / 5
        val x1 = ds.dw - x0
        val y0 = ds.dh / 5
        val y1 = ds.dh - y0
        var j = y0
        while (j < y1) {
            val row = j * ds.dw
            var i = x0
            while (i < x1) {
                sy += ds.y[row + i]
                su += ds.u[row + i]
                sv += ds.v[row + i]
                n++
                i++
            }
            j++
        }
        if (n < 16) return
        tableY = sy / n
        tableU = su / n
        tableV = sv / n
        tableLearned = true
    }

    private fun updateObstacles(ds: Downsampled, r: FloatArray) {
        val threshold = sensThresholds[sensitivity]
        val fz = floorZ
        var count = 0
        var j = 0
        while (j < GRID) {
            val wy = -HALF + (j + 0.5f) * CELL
            val base = j * GRID
            var i = 0
            while (i < GRID) {
                val idx = base + i
                val wx = -HALF + (i + 0.5f) * CELL
                if (projectR(r, wx, wy, fz, p1)) {
                    val k = screenToDs(ds, p1[0], p1[1])
                    if (k >= 0) {
                        val dy = ds.y[k] - tableY
                        val du = ds.u[k] - tableU
                        val dv = ds.v[k] - tableV
                        val diff = abs(dy) * 0.35f + abs(du) + abs(dv)
                        val obs = if (diff > threshold) 1f else 0f
                        conf[idx] = conf[idx] * 0.72f + obs * 0.28f
                        blocked[idx] = conf[idx] > 0.55f
                    }
                }
                if (blocked[idx]) count++
                i++
            }
            j++
        }
        blockedCount = count
    }

    // =========================================================== drawing

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        var dt = if (lastFrameNanos == 0L) 0.016f else (now - lastFrameNanos) / 1000000000f
        lastFrameNanos = now
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0.001f) dt = 0.001f
        smoothedFps = smoothedFps * 0.9f + (1f / dt) * 0.1f

        synchronized(lock) { mul3(corr, rotSensor, rotEff) }

        step(dt)
        if (showMap) {
            drawGrid(canvas)
            drawObstacles(canvas)
        }
        drawBalls(canvas)
        publishStatus()
        postInvalidateOnAnimation()
    }

    private fun addSeg(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (gridCount + 4 > gridPts.size) return
        if (!project(x0, y0, floorZ)) return
        val sx0 = proj[0]
        val sy0 = proj[1]
        if (!project(x1, y1, floorZ)) return
        gridPts[gridCount++] = sx0
        gridPts[gridCount++] = sy0
        gridPts[gridCount++] = proj[0]
        gridPts[gridCount++] = proj[1]
    }

    private fun drawGrid(canvas: Canvas) {
        gridCount = 0
        val gs = 0.2f
        var i = -6
        while (i <= 6) {
            val a = i * gs
            var j = -6
            while (j < 6) {
                addSeg(a, j * gs, a, (j + 1) * gs)
                addSeg(j * gs, a, (j + 1) * gs, a)
                j++
            }
            i++
        }
        if (gridCount > 0) canvas.drawLines(gridPts, 0, gridCount, gridPaint)
    }

    private fun drawObstacles(canvas: Canvas) {
        if (blockedCount <= 0) return
        objPath.reset()
        val top = floorZ + OBJ_H
        var drawn = 0
        var j = 0
        while (j < GRID && drawn < 500) {
            val y0 = -HALF + j * CELL
            val base = j * GRID
            var i = 0
            while (i < GRID && drawn < 500) {
                if (blocked[base + i]) {
                    val x0 = -HALF + i * CELL
                    if (project(x0, y0, top)) {
                        val ax = proj[0]; val ay = proj[1]
                        if (project(x0 + CELL, y0, top)) {
                            val bx = proj[0]; val by = proj[1]
                            if (project(x0 + CELL, y0 + CELL, top)) {
                                val cxx = proj[0]; val cyy = proj[1]
                                if (project(x0, y0 + CELL, top)) {
                                    objPath.moveTo(ax, ay)
                                    objPath.lineTo(bx, by)
                                    objPath.lineTo(cxx, cyy)
                                    objPath.lineTo(proj[0], proj[1])
                                    objPath.close()
                                    drawn++
                                }
                            }
                        }
                    }
                }
                i++
            }
            j++
        }
        if (drawn > 0) {
            canvas.drawPath(objPath, objFill)
            canvas.drawPath(objPath, objEdge)
        }
    }

    private fun drawShadow(canvas: Canvas, b: Ball) {
        val h = (b.z - b.r) - floorZ
        var k = 1f - h / 0.7f
        if (k > 1f) k = 1f
        if (k < 0.05f) return
        val rr = b.r * (1f + h * 0.9f)
        shadowPath.reset()
        var started = false
        var i = 0
        while (i < 14) {
            val a = (i * 2.0 * PI / 14.0).toFloat()
            if (project(b.x + cos(a) * rr, b.y + sin(a) * rr, floorZ)) {
                if (!started) {
                    shadowPath.moveTo(proj[0], proj[1])
                    started = true
                } else {
                    shadowPath.lineTo(proj[0], proj[1])
                }
            }
            i++
        }
        if (!started) return
        shadowPath.close()
        shadowPaint.alpha = (120 * k).toInt()
        canvas.drawPath(shadowPath, shadowPaint)
    }

    private fun drawBalls(canvas: Canvas) {
        order.clear()
        order.addAll(balls)
        order.sortByDescending { depthOf(it) }
        for (b in order) {
            drawShadow(canvas, b)
            if (!project(b.x, b.y, b.z)) continue
            val sx = proj[0]
            val sy = proj[1]
            val rad = focal * b.r / proj[2]
            if (rad < 1.5f) continue
            ballPaint.shader = RadialGradient(
                sx - rad * 0.35f, sy - rad * 0.4f, rad * 1.6f,
                intArrayOf(
                    mix(b.color, Color.WHITE, 0.55f),
                    b.color,
                    mix(b.color, Color.BLACK, 0.6f)
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(sx, sy, rad, ballPaint)
            ballPaint.shader = null
            canvas.drawCircle(sx, sy, rad, rimPaint)
            canvas.drawCircle(sx - rad * 0.38f, sy - rad * 0.42f, rad * 0.16f, glintPaint)
        }
    }

    private fun mix(c: Int, other: Int, t: Float): Int {
        val r = (Color.red(c) * (1f - t) + Color.red(other) * t).toInt()
        val g = (Color.green(c) * (1f - t) + Color.green(other) * t).toInt()
        val b = (Color.blue(c) * (1f - t) + Color.blue(other) * t).toInt()
        return Color.rgb(r, g, b)
    }

    private fun publishStatus() {
        val t = System.currentTimeMillis()
        if (t - lastStatusMs < 250) return
        lastStatusMs = t
        val cb = onStatus ?: return
        val sb = StringBuilder()
        sb.append(balls.size).append(" balls   surface ")
        sb.append((-floorZ * 100f).toInt()).append(" cm   objects ")
        sb.append(blockedCount).append("   lock ").append(flowState)
        sb.append("   ").append(smoothedFps.toInt()).append(" fps")
        if (!haveSensor) sb.append("   NO ROTATION SENSOR")
        val txt = sb.toString()
        post { cb(txt) }
    }

    // =========================================================== physics

    private fun step(dt: Float) {
        val sub = 4
        val h = dt / sub
        var s = 0
        while (s < sub) {
            for (b in balls) {
                if (b.grabbed) continue
                b.vz -= 9.81f * h
                b.x += b.vx * h
                b.y += b.vy * h
                b.z += b.vz * h
                if (b.z - b.r < floorZ) {
                    b.z = floorZ + b.r
                    if (b.vz < 0f) {
                        if (-b.vz < 0.35f) b.vz = 0f else b.vz = -b.vz * 0.62f
                        b.vx *= 0.9f
                        b.vy *= 0.9f
                    }
                    b.vx -= b.vx * 1.4f * h
                    b.vy -= b.vy * 1.4f * h
                }
                if (sensitivity > 0) hitObstacles(b)
                b.vx -= b.vx * 0.12f * h
                b.vy -= b.vy * 0.12f * h
                b.vz -= b.vz * 0.12f * h
            }
            collide()
            s++
        }
        var i = balls.size - 1
        while (i >= 0) {
            val b = balls[i]
            if (!b.grabbed && b.x * b.x + b.y * b.y + b.z * b.z > 64f) balls.removeAt(i)
            i--
        }
    }

    /** sphere against the boxes standing on the blocked cells */
    private fun hitObstacles(b: Ball) {
        if (blockedCount <= 0) return
        val fz = floorZ
        val top = fz + OBJ_H
        if (b.z - b.r > top) return
        var i0 = ((b.x - b.r + HALF) / CELL).toInt()
        var i1 = ((b.x + b.r + HALF) / CELL).toInt()
        var j0 = ((b.y - b.r + HALF) / CELL).toInt()
        var j1 = ((b.y + b.r + HALF) / CELL).toInt()
        if (i1 < 0 || j1 < 0 || i0 >= GRID || j0 >= GRID) return
        if (i0 < 0) i0 = 0
        if (j0 < 0) j0 = 0
        if (i1 >= GRID) i1 = GRID - 1
        if (j1 >= GRID) j1 = GRID - 1

        var j = j0
        while (j <= j1) {
            val base = j * GRID
            var i = i0
            while (i <= i1) {
                if (blocked[base + i]) {
                    val bx0 = -HALF + i * CELL
                    val by0 = -HALF + j * CELL
                    var qx = b.x
                    if (qx < bx0) qx = bx0
                    if (qx > bx0 + CELL) qx = bx0 + CELL
                    var qy = b.y
                    if (qy < by0) qy = by0
                    if (qy > by0 + CELL) qy = by0 + CELL
                    var qz = b.z
                    if (qz < fz) qz = fz
                    if (qz > top) qz = top
                    val nx0 = b.x - qx
                    val ny0 = b.y - qy
                    val nz0 = b.z - qz
                    val d = sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0)
                    if (d < 1e-5f) {
                        // centre inside the box: lift it out through the top
                        b.z = top + b.r
                        if (b.vz < 0f) b.vz = -b.vz * 0.4f
                    } else if (d < b.r) {
                        val nx = nx0 / d
                        val ny = ny0 / d
                        val nz = nz0 / d
                        val push = b.r - d
                        b.x += nx * push
                        b.y += ny * push
                        b.z += nz * push
                        val vn = b.vx * nx + b.vy * ny + b.vz * nz
                        if (vn < 0f) {
                            val e = 1.5f
                            b.vx -= e * vn * nx
                            b.vy -= e * vn * ny
                            b.vz -= e * vn * nz
                            b.vx *= 0.92f
                            b.vy *= 0.92f
                        }
                    }
                }
                i++
            }
            j++
        }
    }

    private fun collide() {
        val n = balls.size
        var i = 0
        while (i < n) {
            var j = i + 1
            while (j < n) {
                val a = balls[i]
                val b = balls[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val dz = b.z - a.z
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                val minD = a.r + b.r
                if (d > 0.0001f && d < minD) {
                    val nx = dx / d
                    val ny = dy / d
                    val nz = dz / d
                    val push = (minD - d) * 0.5f
                    if (!a.grabbed) { a.x -= nx * push; a.y -= ny * push; a.z -= nz * push }
                    if (!b.grabbed) { b.x += nx * push; b.y += ny * push; b.z += nz * push }
                    val rel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny + (b.vz - a.vz) * nz
                    if (rel < 0f) {
                        val imp = -1.6f * rel * 0.5f
                        if (!a.grabbed) { a.vx -= nx * imp; a.vy -= ny * imp; a.vz -= nz * imp }
                        if (!b.grabbed) { b.vx += nx * imp; b.vy += ny * imp; b.vz += nz * imp }
                    }
                }
                j++
            }
            i++
        }
    }

    // =========================================================== touch

    private fun spawnAt(sx: Float, sy: Float) {
        if (balls.size >= 12) balls.removeAt(0)
        val r = 0.045f
        unproject(sx, sy, 0.7f)
        var z = world[2]
        val minZ = floorZ + r + 0.25f
        if (z < minZ) z = minZ
        val c = palette[colorIndex]
        colorIndex = (colorIndex + 1) % palette.size
        balls.add(Ball(world[0], world[1], z, r, c))
    }

    private fun pick(sx: Float, sy: Float): Ball? {
        var best: Ball? = null
        var bestDepth = Float.MAX_VALUE
        for (b in balls) {
            if (!project(b.x, b.y, b.z)) continue
            val rad = focal * b.r / proj[2]
            val dx = sx - proj[0]
            val dy = sy - proj[1]
            if (sqrt(dx * dx + dy * dy) <= rad + 45f && proj[2] < bestDepth) {
                best = b
                bestDepth = proj[2]
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = pick(event.x, event.y)
                if (hit == null) {
                    spawnAt(event.x, event.y)
                } else {
                    var d = depthOf(hit)
                    if (d < 0.15f) d = 0.15f
                    if (d > 4f) d = 4f
                    hit.grabbed = true
                    hit.grabDepth = d
                    dragBall = hit
                    dragVx = 0f; dragVy = 0f; dragVz = 0f
                    lastDragX = hit.x; lastDragY = hit.y; lastDragZ = hit.z
                    lastDragNanos = System.nanoTime()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val b = dragBall ?: return true
                unproject(event.x, event.y, b.grabDepth)
                val now = System.nanoTime()
                var dt = (now - lastDragNanos) / 1000000000f
                if (dt < 0.004f) dt = 0.004f
                dragVx = dragVx * 0.6f + ((world[0] - lastDragX) / dt) * 0.4f
                dragVy = dragVy * 0.6f + ((world[1] - lastDragY) / dt) * 0.4f
                dragVz = dragVz * 0.6f + ((world[2] - lastDragZ) / dt) * 0.4f
                b.x = world[0]; b.y = world[1]; b.z = world[2]
                lastDragX = b.x; lastDragY = b.y; lastDragZ = b.z
                lastDragNanos = now
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val b = dragBall ?: return true
                b.grabbed = false
                b.vx = clampSpeed(dragVx)
                b.vy = clampSpeed(dragVy)
                b.vz = clampSpeed(dragVz)
                dragBall = null
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun clampSpeed(v: Float): Float {
        if (v > 6f) return 6f
        if (v < -6f) return -6f
        return v
    }
}
