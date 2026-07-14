package ru.ui99.photopult.codec

import android.view.Surface
import java.io.InputStream
import ru.ui99.photopult.net.nearby.NearbyConnectionManager
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.util.PhotopultLog

/**
 * Remote-role coordinator: reads the incoming preview STREAM and decodes it to the preview surface.
 *
 * The decoder can only start once all three are known — the [Surface] (from the preview view), the
 * [CameraEvent.StreamConfig] (size), and the STREAM itself — which may arrive in any order, so each
 * setter re-checks. Frames read before then are dropped; the [KeyframeGate] resyncs at the next
 * keyframe.
 */
class PreviewReceiver(
    private val manager: NearbyConnectionManager,
) {
    private var decoder: H264Decoder? = null
    private var readerThread: Thread? = null

    @Volatile private var surface: Surface? = null
    @Volatile private var config: CameraEvent.StreamConfig? = null
    @Volatile private var stream: InputStream? = null
    @Volatile private var started = false

    fun start() {
        manager.streamListener = { input ->
            PhotopultLog.i("PreviewReceiver got STREAM")
            stream = input
            maybeStartDecoder()
        }
    }

    fun setSurface(newSurface: Surface?) {
        surface = newSurface
        if (newSurface != null) maybeStartDecoder()
    }

    fun setConfig(newConfig: CameraEvent.StreamConfig) {
        config = newConfig
        maybeStartDecoder()
    }

    val framesRendered: Long get() = decoder?.framesRendered ?: 0
    val framesDropped: Long get() = decoder?.framesDropped ?: 0
    val lastRenderAtMs: Long get() = decoder?.lastRenderAtMs ?: 0

    @Synchronized
    private fun maybeStartDecoder() {
        if (started) return
        val s = surface ?: return
        val c = config ?: return
        val input = stream ?: return
        started = true
        PhotopultLog.i("PreviewReceiver starting decoder ${c.width}x${c.height}")
        val dec = H264Decoder(c.width, c.height, s)
        dec.start()
        decoder = dec
        readerThread = Thread({ readLoop(input, dec) }, "photopult-stream-reader").also { it.start() }
    }

    private fun readLoop(input: InputStream, dec: H264Decoder) {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val frame = StreamFraming.readFrame(input) ?: break
                dec.submit(frame)
            }
        } catch (e: Exception) {
            PhotopultLog.w("stream reader ended: ${e.message}")
        }
        PhotopultLog.i("stream reader finished")
    }

    fun stop() {
        PhotopultLog.i("PreviewReceiver.stop")
        manager.streamListener = null
        readerThread?.interrupt()
        readerThread = null
        decoder?.stop()
        decoder = null
        runCatching { stream?.close() }
        stream = null
        started = false
    }
}
