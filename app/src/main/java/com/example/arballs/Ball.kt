package com.example.arballs

import android.graphics.Color

/**
 * Materials differ in mass, bounce, air drag and rolling friction, so a
 * ping pong ball skitters and a steel ball thuds and keeps going.
 */
object Materials {
    const val LIGHT = 0
    const val NORMAL = 1
    const val HEAVY = 2

    val names = arrayOf("light", "normal", "heavy")
    val radius = floatArrayOf(0.020f, 0.035f, 0.030f)
    val mass = floatArrayOf(0.003f, 0.060f, 0.900f)
    val restitution = floatArrayOf(0.82f, 0.60f, 0.24f)
    val drag = floatArrayOf(0.90f, 0.25f, 0.05f)
    val rolling = floatArrayOf(1.7f, 1.0f, 0.45f)

    fun colorFor(material: Int, base: Int): Int = when (material) {
        LIGHT -> blend(base, Color.WHITE, 0.6f)
        HEAVY -> blend(base, Color.rgb(70, 76, 84), 0.78f)
        else -> base
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1f - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1f - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1f - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}

class Ball(var x: Float, var y: Float, var z: Float, val material: Int, var color: Int) {
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var grabbed = false
    var grabDepth = 0.7f

    val r = Materials.radius[material]
    val mass = Materials.mass[material]
    val restitution = Materials.restitution[material]
    val drag = Materials.drag[material]
    val rolling = Materials.rolling[material]
}
