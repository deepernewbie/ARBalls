package com.example.arballs

/**
 * A small YUV copy of a camera frame, kept in the sensor's own orientation.
 * Reused between frames so the analysis thread does not allocate.
 */
class Downsampled(val dw: Int, val dh: Int) {
    val y = IntArray(dw * dh)
    val u = IntArray(dw * dh)
    val v = IntArray(dw * dh)

    /** full resolution of the analysis frame, sensor orientation */
    var srcW = 0
    var srcH = 0

    /** how many full-res pixels one downsampled pixel covers */
    var step = 1

    /** degrees the frame must be rotated clockwise to appear upright */
    var rotation = 0
}
