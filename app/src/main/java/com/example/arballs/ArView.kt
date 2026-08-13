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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A ball lives in a world frame that is fixed to the room:
 *   x = right-ish, y = forward-ish (both horizontal), z = straight up.
 * The phone sits at the origin of that frame. The rotation sensor tells us
 * how the phone is turned inside it, so the balls stay put in the room while
 * the camera pans across them.
 */
class Ball(var x: Float, var y: Float, var z: Float, var r: Float, var color: Int) {
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var grabbed = false
    var grabDepth = 0.7f
}

class ArView(context: Context) : View(context), SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var rotationSensor: Sensor? = null

    /** device -> world rotation, row major 3x3. Default = phone held upright. */
    private val rot = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f
    )
    private val rvBuf4 = FloatArray(4)
    private val rvBuf3 = FloatArray(3)
    private var haveSensor = false

    private val balls = ArrayList<Ball>()
    private val order = ArrayList<Ball>()

    /** Height of the table / floor plane, in metres relative to the phone. */
    private var floorZ = -0.35f

    private val palette = intArrayOf(
        0xFFFF7043.toInt(), 0xFF42A5F5.toInt(), 0xFF66BB6A.toInt(),
        0xFFFFCA28.toInt(), 0xFFAB47BC.toInt(), 0xFF26C6DA.toInt()
    )
    private var colorIndex = 0

    // Pinhole camera. Vertical field of view is assumed; see README notes.
    private var focal = 1500f
    private var cx = 540f
    private var cy = 1000f

    private val proj = FloatArray(3)   // sx, sy, depth
    private val world = FloatArray(3)  // x, y, z

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(70, 120, 255, 210)
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

    // ---------------------------------------------------------------- lifecycle

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        // 58 degree vertical field of view is close to a typical phone main camera
        focal = (h / 2f) / tan(Math.toRadians(29.0)).toFloat()
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
        if (n >= 4) {
            var i = 0
            while (i < 4) { rvBuf4[i] = event.values[i]; i++ }
            SensorManager.getRotationMatrixFromVector(rot, rvBuf4)
            haveSensor = true
        } else if (n == 3) {
            var i = 0
            while (i < 3) { rvBuf3[i] = event.values[i]; i++ }
            SensorManager.getRotationMatrixFromVector(rot, rvBuf3)
            haveSensor = true
        }
    }

    // ---------------------------------------------------------------- geometry

    /** world point -> screen. Returns false if it is behind the camera. */
    private fun project(px: Float, py: Float, pz: Float): Boolean {
        val dx = rot[0] * px + rot[3] * py + rot[6] * pz
        val dy = rot[1] * px + rot[4] * py + rot[7] * pz
        val dz = rot[2] * px + rot[5] * py + rot[8] * pz
        val depth = -dz
        if (depth < 0.05f) return false
        proj[0] = cx + focal * dx / depth
        proj[1] = cy - focal * dy / depth
        proj[2] = depth
        return true
    }

    private fun depthOf(b: Ball): Float =
        -(rot[2] * b.x + rot[5] * b.y + rot[8] * b.z)

    /** screen point at a given depth -> world, written into [world]. */
    private fun unproject(sx: Float, sy: Float, depth: Float) {
        val dx = (sx - cx) * depth / focal
        val dy = -(sy - cy) * depth / focal
        val dz = -depth
        world[0] = rot[0] * dx + rot[1] * dy + rot[2] * dz
        world[1] = rot[3] * dx + rot[4] * dy + rot[5] * dz
        world[2] = rot[6] * dx + rot[7] * dy + rot[8] * dz
    }

    // ---------------------------------------------------------------- public API

    fun addBall() {
        spawnAt(cx, cy * 0.75f)
    }

    fun clearBalls() {
        balls.clear()
        dragBall = null
    }

    fun recenter() {
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
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        var dt = if (lastFrameNanos == 0L) 0.016f else (now - lastFrameNanos) / 1000000000f
        lastFrameNanos = now
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0.001f) dt = 0.001f
        smoothedFps = smoothedFps * 0.9f + (1f / dt) * 0.1f

        step(dt)
        drawGrid(canvas)
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
        val step = 0.25f
        var i = -6
        while (i <= 6) {
            val a = i * step
            var j = -6
            while (j < 6) {
                addSeg(a, j * step, a, (j + 1) * step)
                addSeg(j * step, a, (j + 1) * step, a)
                j++
            }
            i++
        }
        if (gridCount > 0) canvas.drawLines(gridPts, 0, gridCount, gridPaint)
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
        // far away first, so nearer balls paint over them
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
        val tracking = if (haveSensor) "tracking" else "no rotation sensor"
        val txt = balls.size.toString() + " ball(s)   surface " +
                (-floorZ * 100f).toInt() + " cm below phone   " +
                tracking + "   " + smoothedFps.toInt() + " fps"
        post { cb(txt) }
    }

    // ---------------------------------------------------------------- physics

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

    // ---------------------------------------------------------------- touch

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
