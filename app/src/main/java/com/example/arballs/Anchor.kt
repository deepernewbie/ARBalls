package com.example.arballs

/**
 * A patch of the camera image that is assumed to sit on the table surface.
 * The user taps a physical marker; the patch around it is remembered and
 * matched in every later frame, which is what pins the virtual surface to a
 * real place instead of to a drifting gyro.
 */
class Anchor {
    companion object {
        const val HALF = 5              // patch is (2*HALF+1) square, in downsampled pixels
        const val SIZE = HALF * 2 + 1
    }

    val template = IntArray(SIZE * SIZE)
    var templateMean = 0f
    var ready = false

    /** last measured position in downsampled sensor pixels */
    var dsX = 0f
    var dsY = 0f

    /** measured position mapped back to view pixels, for drawing */
    var screenX = 0f
    var screenY = 0f

    var tracked = false
    var score = 999f
    var lostFrames = 0

    fun clear() {
        ready = false
        tracked = false
        lostFrames = 0
        score = 999f
    }
}
