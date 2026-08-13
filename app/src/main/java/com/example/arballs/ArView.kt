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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The world frame is fixed to the room: x and y horizontal, z straight up.
 *
 * Without markers the phone is assumed to sit at the origin and only its
 * orientation is known, corrected by matching the camera image frame to frame.
 *
 * With two tapped markers the geometry closes: the two image points plus the
 * known direction of gravity give the camera's heading and its position
 * relative to the markers, every frame, with no drift. The one thing that
 * cannot come out of a single camera is scale, so the distance between the
 * markers is an adjustable number rather than a measured one - Scale - / +
 * stretches or shrinks the whole room until the ball looks right.
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

    /** raw sensor orientation, device -> world */
    private val rotSensor = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)

    /** world frame correction, applied on the left of rotSensor */
    private val corr = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** camera position in the world frame */
    private val camPos = floatArrayOf(0f, 0f, 0f)

    // render side copies
    private val rotEff = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
    private val camRender = floatArrayOf(0f, 0f, 0f)

    // analysis side copies
    private val rotNow = FloatArray(9)
    private val camNow = FloatArray(3)

    private val rvBuf4 = FloatArray(4)
    private val rvBuf3 = FloatArray(3)
    private var haveSensor = false

    private val balls = ArrayList<Ball>()
    private val order = ArrayList<Ball>()

    @Volatile private var floorZ = -0.35f
    @Volatile var material = Materials.NORMAL

    private val palette = intArrayOf(
        0xFFFF7043.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(),
        0xFFFFCA28.toInt(), 0xFFAB47BC.toInt(), 0xFF26C6DA.toInt()
    )
    private var colorIndex = 0

    // ---- camera model -------------------------------------------------------

    @Volatile private var focalNorm = 0f
    private var focal = 1500f
    private var cx = 540f
    private var cy = 1000f

    @Volatile private var uprightW = 0
    @Volatile private var uprightH = 0
    @Volatile private var viewScale = 1f
    @Volatile private var offX = 0f
    @Volatile private var offY = 0f
    @Volatile private var frameRotation = 0
    @Volatile private var frameStep = 1
    @Volatile private var frameSrcW = 0
    @Volatile private var frameSrcH = 0

    // ---- markers ------------------------------------------------------------

    private val anchors = arrayOf(Anchor(), Anchor())

    /** assumed distance between the two markers; scale of the whole world */
    @Volatile private var markerGap = 0.20f

    @Volatile var markMode = 0            // 0 idle, 1 waiting for first tap, 2 for second
    private val pendingTap = booleanArrayOf(false, false)
    private val pendingTapX = floatArrayOf(0f, 0f)
    private val pendingTapY = floatArrayOf(0f, 0f)

    @Volatile private var poseValid = false
    private var headingFilt = 0f
    private var headingReady = false
    @Volatile private var poseState = "no markers"
    @Volatile private var camHeightCm = 0
    @Volatile private var pendingRecenter = false

    // ---- image stabilisation (used when markers are not tracking) ------------

    @Volatile var flowEnabled = true
    private var prevY: IntArray? = null
    private var prevValid = false
    private val rotPrev = FloatArray(9)
    private val camPrev = FloatArray(3)
    private var havePrevRot = false
    @Volatile private var flowState = "starting"

    // ---- obstacle map -------------------------------------------------------

    private val conf = FloatArray(GRID * GRID)
    private val blocked = BooleanArray(GRID * GRID)
    @Volatile private var blockedCount = 0
    @Volatile var sensitivity = 2
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
    private val ray1 = FloatArray(3)
    private val ray2 = FloatArray(3)
    private val vecA = FloatArray(3)
    private val vecB = FloatArray(3)
    private val matA = FloatArray(9)
    private val matB = FloatArray(9)
    private val matC = FloatArray(9)
    private val anchorWorld = FloatArray(3)

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
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(230, 90, 255, 120)
    }
    private val markLost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(230, 255, 90, 90)
    }
    private val markCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(200, 255, 235, 120)
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
    var onHint: ((String) -> Unit)? = null

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
            focal = (vh / 2f) / tan(Math.toRadians(29.0)).toFloat()
            viewScale = 1f
            offX = 0f
            offY = 0f
            return
        }
        val sx = vw / uw
        val sy = vh / uh
        val s = if (sx > sy) sx else sy
        viewScale = s
        offX = (vw - uw * s) / 2f
        offY = (vh - uh * s) / 2f
        focal = focalNorm * frameSrcW * s
    }

    // =========================================================== geometry

    private fun projectR(
        r: FloatArray, cam: FloatArray,
        px: Float, py: Float, pz: Float, out: FloatArray
    ): Boolean {
        val ax = px - cam[0]
        val ay = py - cam[1]
        val az = pz - cam[2]
        val dx = r[0] * ax + r[3] * ay + r[6] * az
        val dy = r[1] * ax + r[4] * ay + r[7] * az
        val dz = r[2] * ax + r[5] * ay + r[8] * az
        val depth = -dz
        if (depth < 0.03f) return false
        out[0] = cx + focal * dx / depth
        out[1] = cy - focal * dy / depth
        out[2] = depth
        return true
    }

    private fun project(px: Float, py: Float, pz: Float): Boolean =
        projectR(rotEff, camRender, px, py, pz, proj)

    private fun depthOf(b: Ball): Float {
        val ax = b.x - camRender[0]
        val ay = b.y - camRender[1]
        val az = b.z - camRender[2]
        return -(rotEff[2] * ax + rotEff[5] * ay + rotEff[8] * az)
    }

    private fun unprojectR(
        r: FloatArray, cam: FloatArray,
        sx: Float, sy: Float, depth: Float, out: FloatArray
    ) {
        val dx = (sx - cx) * depth / focal
        val dy = -(sy - cy) * depth / focal
        val dz = -depth
        out[0] = cam[0] + r[0] * dx + r[1] * dy + r[2] * dz
        out[1] = cam[1] + r[3] * dx + r[4] * dy + r[5] * dz
        out[2] = cam[2] + r[6] * dx + r[7] * dy + r[8] * dz
    }

    private fun unproject(sx: Float, sy: Float, depth: Float) =
        unprojectR(rotEff, camRender, sx, sy, depth, world)

    /** unit ray direction in world coordinates for a view pixel */
    private fun rayWorld(r: FloatArray, sx: Float, sy: Float, out: FloatArray) {
        val dx = (sx - cx) / focal
        val dy = -(sy - cy) / focal
        val dz = -1f
        val wx = r[0] * dx + r[1] * dy + r[2] * dz
        val wy = r[3] * dx + r[4] * dy + r[5] * dz
        val wz = r[6] * dx + r[7] * dy + r[8] * dz
        val len = sqrt(wx * wx + wy * wy + wz * wz)
        if (len < 1e-6f) {
            out[0] = 0f; out[1] = 0f; out[2] = -1f
            return
        }
        out[0] = wx / len; out[1] = wy / len; out[2] = wz / len
    }

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
        if (balls.isEmpty()) {
            addBall()
            return
        }
        placeInFront()
    }

    private fun placeInFront() {
        var k = 0
        for (b in balls) {
            dropPoint(cx + (k % 3 - 1) * 60f, cy * 0.8f, b.r, vecA)
            b.x = vecA[0]
            b.y = vecA[1]
            b.z = vecA[2] + k * 0.06f
            b.vx = 0f; b.vy = 0f; b.vz = 0f
            b.grabbed = false
            k++
        }
        dragBall = null
    }

    /** where a ball dropped through this pixel should start */
    private fun dropPoint(sx: Float, sy: Float, r: Float, out: FloatArray) {
        rayWorld(rotEff, sx, sy, vecB)
        val fz = floorZ
        var placed = false
        if (vecB[2] < -0.05f) {
            val t = (fz - camRender[2]) / vecB[2]
            if (t > 0.08f && t < 6f) {
                out[0] = camRender[0] + vecB[0] * t
                out[1] = camRender[1] + vecB[1] * t
                out[2] = fz + r + 0.12f
                placed = true
            }
        }
        if (!placed) {
            unproject(sx, sy, 0.7f)
            out[0] = world[0]
            out[1] = world[1]
            var z = world[2]
            val minZ = fz + r + 0.25f
            if (z < minZ) z = minZ
            out[2] = z
        }
    }

    fun nudgeFloor(delta: Float) {
        var v = floorZ + delta
        if (v < -1.5f) v = -1.5f
        if (v > 1.0f) v = 1.0f
        floorZ = v
        clearMap()
    }

    fun scaleWorld(factor: Float) {
        var g = markerGap * factor
        if (g < 0.03f) g = 0.03f
        if (g > 1.5f) g = 1.5f
        markerGap = g
        clearMap()
    }

    fun startMarking() {
        synchronized(lock) {
            anchors[0].clear()
            anchors[1].clear()
            poseValid = false
            headingReady = false
            markMode = 1
            pendingTap[0] = false
            pendingTap[1] = false
        }
        onHint?.invoke("Tap the first marker on the table")
    }

    /** back to the plain, marker free state */
    fun resetAll() {
        synchronized(lock) {
            anchors[0].clear()
            anchors[1].clear()
            poseValid = false
            headingReady = false
            markMode = 0
            pendingTap[0] = false
            pendingTap[1] = false
            corr[0] = 1f; corr[1] = 0f; corr[2] = 0f
            corr[3] = 0f; corr[4] = 1f; corr[5] = 0f
            corr[6] = 0f; corr[7] = 0f; corr[8] = 1f
            camPos[0] = 0f; camPos[1] = 0f; camPos[2] = 0f
        }
        floorZ = -0.35f
        poseState = "no markers"
        clearMap()
        scanRequested = true
        pendingRecenter = true
        onHint?.invoke("Reset. Tap the table to drop a ball, or press Mark to register the surface.")
    }

    private fun clearMap() {
        java.util.Arrays.fill(conf, 0f)
        java.util.Arrays.fill(blocked, false)
        blockedCount = 0
    }

    fun rescanTable() {
        scanRequested = true
        clearMap()
    }

    fun sensitivityLabel(): String = when (sensitivity) {
        0 -> "Obj: off"
        1 -> "Obj: low"
        2 -> "Obj: med"
        else -> "Obj: high"
    }

    fun cycleSensitivity(): String {
        sensitivity = (sensitivity + 1) % 4
        if (sensitivity == 0) clearMap()
        return sensitivityLabel()
    }

    fun materialLabel(): String = "Ball: " + Materials.names[material]

    fun cycleMaterial(): String {
        material = (material + 1) % 3
        return materialLabel()
    }

    // =========================================================== frame analysis

    fun submitFrame(ds: Downsampled) {
        if (width <= 0 || height <= 0) return

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
            updateCameraModel()
            post { updateCameraModel() }
        }

        var prev = prevY
        if (prev == null || prev.size != ds.y.size) {
            prev = IntArray(ds.y.size)
            prevY = prev
            prevValid = false
        }

        synchronized(lock) {
            mul3(corr, rotSensor, rotNow)
            camNow[0] = camPos[0]; camNow[1] = camPos[1]; camNow[2] = camPos[2]
        }

        // taps waiting to become anchors
        var pt = 0
        while (pt < 2) {
            if (pendingTap[pt]) {
                pendingTap[pt] = false
                captureAnchor(ds, anchors[pt], pendingTapX[pt], pendingTapY[pt])
            }
            pt++
        }

        var trackedCount = 0
        if (anchors[0].ready || anchors[1].ready) {
            var i = 0
            while (i < 2) {
                val a = anchors[i]
                if (a.ready) {
                    trackAnchor(ds, a, i)
                    if (a.tracked) trackedCount++
                }
                i++
            }
        }

        if (trackedCount == 2) {
            solveMarkerPose()
        } else if (trackedCount == 1) {
            val a = if (anchors[0].tracked) anchors[0] else anchors[1]
            val which = if (anchors[0].tracked) 0 else 1
            correctToAnchor(a, which)
            poseState = "1 marker"
        } else {
            if (anchors[0].ready || anchors[1].ready) poseState = "markers lost"
            if (flowEnabled && prevValid && havePrevRot) {
                trackImage(ds, prev)
            } else {
                flowState = if (!flowEnabled) "off" else "warming"
            }
        }

        if (scanRequested) {
            learnTable(ds)
            scanRequested = false
        }
        if (sensitivity > 0 && tableLearned) {
            updateObstacles(ds, rotNow, camNow)
        }

        System.arraycopy(ds.y, 0, prev, 0, ds.y.size)
        prevValid = true
        System.arraycopy(rotNow, 0, rotPrev, 0, 9)
        System.arraycopy(camNow, 0, camPrev, 0, 3)
        havePrevRot = true
    }

    // ---- coordinate plumbing ------------------------------------------------

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

    /** view pixel -> downsampled sensor pixel, written into [out]; false if outside */
    private fun screenToDsXY(ds: Downsampled, sx: Float, sy: Float, out: FloatArray): Boolean {
        val s = viewScale
        if (s <= 0f) return false
        val ux = (sx - offX) / s
        val uy = (sy - offY) / s
        if (ux < 0f || uy < 0f || ux >= uprightW || uy >= uprightH) return false
        val fx: Float
        val fy: Float
        when (frameRotation) {
            90 -> { fx = uy; fy = ds.srcH - 1f - ux }
            180 -> { fx = ds.srcW - 1f - ux; fy = ds.srcH - 1f - uy }
            270 -> { fx = ds.srcW - 1f - uy; fy = ux }
            else -> { fx = ux; fy = uy }
        }
        out[0] = fx / ds.step
        out[1] = fy / ds.step
        return true
    }

    private fun screenToDs(ds: Downsampled, sx: Float, sy: Float): Int {
        if (!screenToDsXY(ds, sx, sy, vecA)) return -1
        val i = vecA[0].toInt()
        val j = vecA[1].toInt()
        if (i < 0 || j < 0 || i >= ds.dw || j >= ds.dh) return -1
        return j * ds.dw + i
    }

    /** downsampled sensor pixel -> view pixel */
    private fun dsToScreen(ds: Downsampled, dsx: Float, dsy: Float, out: FloatArray) {
        val fx = (dsx + 0.5f) * ds.step
        val fy = (dsy + 0.5f) * ds.step
        val ux: Float
        val uy: Float
        when (frameRotation) {
            90 -> { ux = ds.srcH - 1f - fy; uy = fx }
            180 -> { ux = ds.srcW - 1f - fx; uy = ds.srcH - 1f - fy }
            270 -> { ux = fy; uy = ds.srcW - 1f - fx }
            else -> { ux = fx; uy = fy }
        }
        out[0] = offX + ux * viewScale
        out[1] = offY + uy * viewScale
    }

    // ---- anchors ------------------------------------------------------------

    private fun anchorWorldPos(index: Int, out: FloatArray) {
        out[0] = if (index == 0) 0f else markerGap
        out[1] = 0f
        out[2] = 0f
    }

    private fun captureAnchor(ds: Downsampled, a: Anchor, sx: Float, sy: Float) {
        if (!screenToDsXY(ds, sx, sy, vecA)) {
            onHint?.invoke("That tap was outside the camera picture - try again")
            return
        }
        val ci = vecA[0].toInt()
        val cj = vecA[1].toInt()
        val h = Anchor.HALF
        if (ci - h < 0 || cj - h < 0 || ci + h >= ds.dw || cj + h >= ds.dh) {
            onHint?.invoke("Too close to the edge of the picture - tap nearer the middle")
            return
        }
        var sum = 0f
        var k = 0
        var j = -h
        while (j <= h) {
            var i = -h
            while (i <= h) {
                val v = ds.y[(cj + j) * ds.dw + ci + i]
                a.template[k] = v
                sum += v
                k++
                i++
            }
            j++
        }
        a.templateMean = sum / (Anchor.SIZE * Anchor.SIZE)
        a.dsX = ci.toFloat()
        a.dsY = cj.toFloat()
        a.ready = true
        a.tracked = true
        a.lostFrames = 0
        a.score = 0f
        dsToScreen(ds, a.dsX, a.dsY, vecB)
        a.screenX = vecB[0]
        a.screenY = vecB[1]
    }

    private fun trackAnchor(ds: Downsampled, a: Anchor, index: Int) {
        val h = Anchor.HALF
        var centreX = a.dsX
        var centreY = a.dsY
        if (poseValid) {
            anchorWorldPos(index, anchorWorld)
            if (projectR(rotNow, camNow, anchorWorld[0], anchorWorld[1], anchorWorld[2], p0)) {
                if (screenToDsXY(ds, p0[0], p0[1], vecA)) {
                    centreX = vecA[0]
                    centreY = vecA[1]
                }
            }
        }
        val win = if (a.tracked) 8 else 18
        var bestScore = Float.MAX_VALUE
        var bestI = -1
        var bestJ = -1
        val ci = centreX.toInt()
        val cj = centreY.toInt()
        val size = Anchor.SIZE

        var oy = -win
        while (oy <= win) {
            val j0 = cj + oy
            if (j0 - h >= 0 && j0 + h < ds.dh) {
                var ox = -win
                while (ox <= win) {
                    val i0 = ci + ox
                    if (i0 - h >= 0 && i0 + h < ds.dw) {
                        // window mean
                        var sum = 0f
                        var jj = -h
                        while (jj <= h) {
                            val row = (j0 + jj) * ds.dw + i0
                            var ii = -h
                            while (ii <= h) {
                                sum += ds.y[row + ii]
                                ii++
                            }
                            jj++
                        }
                        val mean = sum / (size * size)
                        var diff = 0f
                        var k = 0
                        jj = -h
                        while (jj <= h) {
                            val row = (j0 + jj) * ds.dw + i0
                            var ii = -h
                            while (ii <= h) {
                                val d = (ds.y[row + ii] - mean) - (a.template[k] - a.templateMean)
                                diff += abs(d)
                                k++
                                ii++
                            }
                            jj++
                        }
                        val score = diff / (size * size)
                        if (score < bestScore) {
                            bestScore = score
                            bestI = i0
                            bestJ = j0
                        }
                    }
                    ox++
                }
            }
            oy++
        }

        a.score = bestScore
        if (bestI < 0 || bestScore > 20f) {
            a.tracked = false
            a.lostFrames++
            return
        }
        a.tracked = true
        a.lostFrames = 0
        a.dsX = bestI.toFloat()
        a.dsY = bestJ.toFloat()
        dsToScreen(ds, a.dsX, a.dsY, vecB)
        a.screenX = vecB[0]
        a.screenY = vecB[1]
    }

    /**
     * Two markers on a horizontal surface, plus the direction of gravity from
     * the sensor, fix the camera's heading and position. Scale comes from the
     * assumed gap between the markers, which is the one thing a single camera
     * cannot measure.
     */
    private fun solveMarkerPose() {
        val rs = FloatArray(9)
        synchronized(lock) { System.arraycopy(rotSensor, 0, rs, 0, 9) }

        rayWorld(rs, anchors[0].screenX, anchors[0].screenY, ray1)
        rayWorld(rs, anchors[1].screenX, anchors[1].screenY, ray2)
        // both markers must be below the horizon for the surface to be in front
        if (ray1[2] > -0.05f || ray2[2] > -0.05f) {
            poseState = "hold the phone over the surface"
            return
        }
        val k = ray1[2] / ray2[2]
        val wx = ray1[0] - k * ray2[0]
        val wy = ray1[1] - k * ray2[1]
        val wz = ray1[2] - k * ray2[2]
        val wlen = sqrt(wx * wx + wy * wy + wz * wz)
        if (wlen < 1e-4f) {
            poseState = "markers too close together"
            return
        }
        val t1 = markerGap / wlen
        val t2 = t1 * k
        if (t1 < 0.05f || t1 > 8f || t2 < 0.05f || t2 > 8f) {
            poseState = "marker geometry out of range"
            return
        }

        // marker positions relative to the camera, in gravity aligned axes
        vecA[0] = ray1[0] * t1; vecA[1] = ray1[1] * t1; vecA[2] = ray1[2] * t1
        vecB[0] = ray2[0] * t2; vecB[1] = ray2[1] * t2; vecB[2] = ray2[2] * t2

        val ux = vecB[0] - vecA[0]
        val uy = vecB[1] - vecA[1]
        if (abs(ux) < 1e-5f && abs(uy) < 1e-5f) return
        val theta = -atan2(uy, ux)

        if (!headingReady) {
            headingFilt = theta
            headingReady = true
        } else {
            var d = theta - headingFilt
            while (d > PI.toFloat()) d -= (2.0 * PI).toFloat()
            while (d < -PI.toFloat()) d += (2.0 * PI).toFloat()
            headingFilt += 0.35f * d
        }

        val c = cos(headingFilt)
        val s = sin(headingFilt)
        // rotation about world z by headingFilt
        matA[0] = c; matA[1] = -s; matA[2] = 0f
        matA[3] = s; matA[4] = c; matA[5] = 0f
        matA[6] = 0f; matA[7] = 0f; matA[8] = 1f

        // camera position = -Rz * (marker A relative to camera)
        val px = -(matA[0] * vecA[0] + matA[1] * vecA[1] + matA[2] * vecA[2])
        val py = -(matA[3] * vecA[0] + matA[4] * vecA[1] + matA[5] * vecA[2])
        val pz = -(matA[6] * vecA[0] + matA[7] * vecA[1] + matA[8] * vecA[2])

        val first = !poseValid
        synchronized(lock) {
            System.arraycopy(matA, 0, corr, 0, 9)
            if (first) {
                camPos[0] = px; camPos[1] = py; camPos[2] = pz
            } else {
                camPos[0] += 0.4f * (px - camPos[0])
                camPos[1] += 0.4f * (py - camPos[1])
                camPos[2] += 0.4f * (pz - camPos[2])
            }
            mul3(corr, rotSensor, rotNow)
            camNow[0] = camPos[0]; camNow[1] = camPos[1]; camNow[2] = camPos[2]
        }
        if (first) {
            floorZ = 0f
            clearMap()
            scanRequested = true
            pendingRecenter = true
            poseValid = true
            post { onHint?.invoke("Surface registered. Scale - / + until the ball looks right.") }
        }
        camHeightCm = (camPos[2] * 100f).toInt()
        poseState = "2 markers"
    }

    /**
     * One marker only: position and scale are not observable, but the marker is
     * still a fixed reference, so it can hold the orientation steady.
     */
    private fun correctToAnchor(a: Anchor, index: Int) {
        anchorWorldPos(index, anchorWorld)
        if (!projectR(rotNow, camNow, anchorWorld[0], anchorWorld[1], anchorWorld[2], p0)) return
        var rx = a.screenX - p0[0]
        var ry = a.screenY - p0[1]
        if (abs(rx) > 400f || abs(ry) > 400f) return
        if (abs(rx) < 0.5f) rx = 0f
        if (abs(ry) < 0.5f) ry = 0f
        if (rx == 0f && ry == 0f) return
        applyRotationCorrection(rx, ry, anchorWorld[0], anchorWorld[1], anchorWorld[2], 0.5f, 0.06f)
    }

    /**
     * Nudges the world orientation so that a world point lands [rx],[ry] pixels
     * further along on screen. The 2x2 Jacobian is measured numerically, which
     * keeps every sign in the projection from having to be reasoned about.
     */
    private fun applyRotationCorrection(
        rx: Float, ry: Float,
        wx: Float, wy: Float, wz: Float,
        gain: Float, limit: Float
    ) {
        if (!projectR(rotNow, camNow, wx, wy, wz, p0)) return
        val ux0 = rotNow[0]; val ux1 = rotNow[3]; val ux2 = rotNow[6]
        val uy0 = rotNow[1]; val uy1 = rotNow[4]; val uy2 = rotNow[7]

        val eps = 0.004f
        axisRotation(ux0, ux1, ux2, eps, matA)
        mul3(matA, rotNow, matB)
        if (!projectR(matB, camNow, wx, wy, wz, p1)) return
        axisRotation(uy0, uy1, uy2, eps, matA)
        mul3(matA, rotNow, matB)
        if (!projectR(matB, camNow, wx, wy, wz, p2)) return

        val j00 = (p1[0] - p0[0]) / eps
        val j10 = (p1[1] - p0[1]) / eps
        val j01 = (p2[0] - p0[0]) / eps
        val j11 = (p2[1] - p0[1]) / eps
        val det = j00 * j11 - j01 * j10
        if (abs(det) < 1e-2f) return

        val tx = gain * rx
        val ty = gain * ry
        var ax = (j11 * tx - j01 * ty) / det
        var ay = (-j10 * tx + j00 * ty) / det
        if (ax > limit) ax = limit
        if (ax < -limit) ax = -limit
        if (ay > limit) ay = limit
        if (ay < -limit) ay = -limit

        synchronized(lock) {
            axisRotation(ux0, ux1, ux2, ax, matA)
            mul3(matA, corr, matC)
            axisRotation(uy0, uy1, uy2, ay, matA)
            mul3(matA, matC, corr)
            orthonormalize(corr)
            mul3(corr, rotSensor, rotNow)
        }
    }

    /** whole image match, used when there are no markers to lean on */
    private fun trackImage(ds: Downsampled, prev: IntArray) {
        unprojectR(rotPrev, camPrev, cx, cy, 2f, refPt)
        if (!projectR(rotNow, camNow, refPt[0], refPt[1], refPt[2], p0)) {
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
                        sum += abs(ds.y[rowCur + xx] - prev[rowPrev + xx - shx]).toFloat()
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
        if (abs(rx) < 0.6f) rx = 0f
        if (abs(ry) < 0.6f) ry = 0f
        if (rx == 0f && ry == 0f) return

        applyRotationCorrection(rx, ry, refPt[0], refPt[1], refPt[2], 0.3f, 0.02f)
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

    private fun updateObstacles(ds: Downsampled, r: FloatArray, cam: FloatArray) {
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
                if (projectR(r, cam, wx, wy, fz, p1)) {
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

        synchronized(lock) {
            mul3(corr, rotSensor, rotEff)
            camRender[0] = camPos[0]; camRender[1] = camPos[1]; camRender[2] = camPos[2]
        }

        if (pendingRecenter) {
            pendingRecenter = false
            if (balls.isNotEmpty()) placeInFront()
        }

        step(dt)
        if (showMap) {
            drawGrid(canvas)
            drawObstacles(canvas)
        }
        drawBalls(canvas)
        drawMarkers(canvas)
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
                                val ccx = proj[0]; val ccy = proj[1]
                                if (project(x0, y0 + CELL, top)) {
                                    objPath.moveTo(ax, ay)
                                    objPath.lineTo(bx, by)
                                    objPath.lineTo(ccx, ccy)
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

    private fun drawMarkers(canvas: Canvas) {
        var i = 0
        while (i < 2) {
            val a = anchors[i]
            if (a.ready) {
                val paint = if (a.tracked) markPaint else markLost
                canvas.drawCircle(a.screenX, a.screenY, 26f, paint)
                canvas.drawCircle(a.screenX, a.screenY, 3f, paint)
                // where the registered surface says this marker should be
                anchorWorldPos(i, anchorWorld)
                if (poseValid && project(anchorWorld[0], anchorWorld[1], anchorWorld[2])) {
                    canvas.drawLine(proj[0] - 16f, proj[1], proj[0] + 16f, proj[1], markCross)
                    canvas.drawLine(proj[0], proj[1] - 16f, proj[0], proj[1] + 16f, markCross)
                }
            }
            i++
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
        sb.append(balls.size).append(" balls   ")
        sb.append(poseState)
        if (poseValid) {
            sb.append("   gap ").append((markerGap * 100f).toInt()).append(" cm")
            sb.append("   cam ").append(camHeightCm).append(" cm up")
        } else {
            sb.append("   surface ").append((-floorZ * 100f).toInt()).append(" cm")
            sb.append("   lock ").append(flowState)
        }
        sb.append("   obj ").append(blockedCount)
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
                        if (-b.vz < 0.3f) b.vz = 0f else b.vz = -b.vz * b.restitution
                        b.vx *= 0.92f
                        b.vy *= 0.92f
                    }
                    b.vx -= b.vx * b.rolling * h
                    b.vy -= b.vy * b.rolling * h
                }
                if (sensitivity > 0) hitObstacles(b)
                b.vx -= b.vx * b.drag * h
                b.vy -= b.vy * b.drag * h
                b.vz -= b.vz * b.drag * h
            }
            collide()
            s++
        }
        var i = balls.size - 1
        while (i >= 0) {
            val b = balls[i]
            val ddx = b.x - camRender[0]
            val ddy = b.y - camRender[1]
            val ddz = b.z - camRender[2]
            if (!b.grabbed && ddx * ddx + ddy * ddy + ddz * ddz > 64f) balls.removeAt(i)
            i--
        }
    }

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
                            val e = 1f + b.restitution
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
                    val ima = if (a.grabbed) 0f else 1f / a.mass
                    val imb = if (b.grabbed) 0f else 1f / b.mass
                    val imSum = ima + imb
                    if (imSum > 0f) {
                        val overlap = minD - d
                        val pa = overlap * (ima / imSum)
                        val pb = overlap * (imb / imSum)
                        a.x -= nx * pa; a.y -= ny * pa; a.z -= nz * pa
                        b.x += nx * pb; b.y += ny * pb; b.z += nz * pb
                        val rel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny + (b.vz - a.vz) * nz
                        if (rel < 0f) {
                            val e = if (a.restitution < b.restitution) a.restitution else b.restitution
                            val imp = -(1f + e) * rel / imSum
                            a.vx -= nx * imp * ima
                            a.vy -= ny * imp * ima
                            a.vz -= nz * imp * ima
                            b.vx += nx * imp * imb
                            b.vy += ny * imp * imb
                            b.vz += nz * imp * imb
                        }
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
        val m = material
        val c = Materials.colorFor(m, palette[colorIndex])
        colorIndex = (colorIndex + 1) % palette.size
        dropPoint(sx, sy, Materials.radius[m], vecA)
        balls.add(Ball(vecA[0], vecA[1], vecA[2], m, c))
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
                if (markMode > 0) {
                    val index = markMode - 1
                    pendingTapX[index] = event.x
                    pendingTapY[index] = event.y
                    pendingTap[index] = true
                    if (markMode == 1) {
                        markMode = 2
                        onHint?.invoke("Now tap the second marker")
                    } else {
                        markMode = 0
                        onHint?.invoke("Registering...")
                    }
                    return true
                }
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
