package ru.ui99.photopult.codec

import android.view.Surface
import ru.ui99.photopult.util.PhotopultLog

/**
 * On-device preview for the **camera** phone. CameraX renders straight into the encoder's input
 * Surface (lowest-latency streaming path), which leaves no CameraX Preview surface for a local
 * display. So instead of a second camera pipeline, we decode the very frames we're already encoding
 * and render them to the camera screen — reusing the same [H264Decoder] that the remote uses and
 * has been proven on-device.
 *
 * Cost: the local view shares the stream's encode quality and adds ~1–2 frames of decode latency.
 * That is fine for a tripod self-monitor and keeps the pipeline simple and single-source-of-truth.
 */
class LocalPreview {

    @Volatile private var decoder: H264Decoder? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0

    @Synchronized
    fun setSurface(newSurface: Surface?) {
        surface = newSurface
        if (newSurface == null) {
            decoder?.stop()
            decoder = null
        } else {
            ensureDecoder()
        }
    }

    /** Called when the encoder (re)starts at a given resolution; restarts the decoder to match. */
    @Synchronized
    fun setResolution(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        decoder?.stop()
        decoder = null
        ensureDecoder()
    }

    /** Feed one encoded access unit (the same one going to the remote). */
    fun submit(frame: StreamFraming.Frame) {
        decoder?.submit(frame)
    }

    private fun ensureDecoder() {
        val s = surface ?: return
        if (width == 0 || height == 0) return
        if (decoder == null) {
            PhotopultLog.i("LocalPreview decoder ${width}x$height")
            decoder = H264Decoder(width, height, s).also { it.start() }
        }
    }

    @Synchronized
    fun stop() {
        decoder?.stop()
        decoder = null
        surface = null
    }
}
