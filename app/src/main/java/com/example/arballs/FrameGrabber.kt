package com.example.arballs

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Pulls the luma and chroma planes out of each analysis frame at about
 * 160 pixels wide and hands them to [sink] on the analysis thread.
 */
class FrameGrabber(private val sink: (Downsampled) -> Unit) : ImageAnalysis.Analyzer {

    private var buffer: Downsampled? = null

    override fun analyze(image: ImageProxy) {
        try {
            val w = image.width
            val h = image.height
            if (w < 32 || h < 32) return

            var step = w / 160
            if (step < 1) step = 1
            val dw = w / step
            val dh = h / step

            var d = buffer
            if (d == null || d.dw != dw || d.dh != dh) {
                d = Downsampled(dw, dh)
                buffer = d
            }
            d.srcW = w
            d.srcH = h
            d.step = step
            d.rotation = image.imageInfo.rotationDegrees

            val planes = image.planes
            if (planes.size < 3) return
            val yBuf = planes[0].buffer
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer
            val yRow = planes[0].rowStride
            val yPix = planes[0].pixelStride
            val uRow = planes[1].rowStride
            val uPix = planes[1].pixelStride
            val vRow = planes[2].rowStride
            val vPix = planes[2].pixelStride
            val yLim = yBuf.limit()
            val uLim = uBuf.limit()
            val vLim = vBuf.limit()

            var j = 0
            while (j < dh) {
                val sy = j * step
                val yBase = sy * yRow
                val uBase = (sy / 2) * uRow
                val vBase = (sy / 2) * vRow
                val out = j * dw
                var i = 0
                while (i < dw) {
                    val sx = i * step
                    val k = out + i
                    val yi = yBase + sx * yPix
                    val ui = uBase + (sx / 2) * uPix
                    val vi = vBase + (sx / 2) * vPix
                    d.y[k] = if (yi < yLim) (yBuf.get(yi).toInt() and 255) else 0
                    d.u[k] = if (ui < uLim) (uBuf.get(ui).toInt() and 255) else 128
                    d.v[k] = if (vi < vLim) (vBuf.get(vi).toInt() and 255) else 128
                    i++
                }
                j++
            }
            sink(d)
        } catch (e: Exception) {
            // A dropped frame is not worth crashing over.
        } finally {
            image.close()
        }
    }
}
