package ru.ui99.photopult.codec

import android.view.Surface
import java.io.InputStream
import ru.ui99.photopult.net.nearby.NearbyConnectionManager
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.util.PhotopultLog

/**
 * Remote-role coordinator: reads the incoming preview STREAM and decodes it to the preview surface.
 *
 * The decoder starts once the [Surface], the [CameraEvent.StreamConfig] (size) and the STREAM are
 * all present (any order). If the camera changes resolution (adaptive ladder), a new StreamConfig
 * with different dimensions restarts the decoder while the reader keeps consuming the same stream;
 * frames resync at the next keyframe (the camera forces one after a resolution change).
 */
class PreviewReceiver(
    private val manager: NearbyConnectionManager,
) {
    @Volatile private var decoder: H264Decoder? = null
    private var readerThread: Thread? = null

    @Volatile private var surface: Surface? = null
    @Volatile private var config: CameraEvent.StreamConfig? = null
    @Volatile private var stream: InputStream? = null

    fun start() {
        manager.streamListener = { input ->
            PhotopultLog.i("PreviewReceiver got STREAM")
            stream = input
            ensureReaderAndDecoder()
        }
    }

    fun setSurface(newSurface: Surface?) {
        surface = newSurface
        if (newSurface != null) ensureReaderAndDecoder()
    }

    fun setConfig(newConfig: CameraEvent.StreamConfig) {
        val old = config
        config = newConfig
        if (old != null && (old.width != newConfig.width || old.height != newConfig.height)) {
            restartDecoder(newConfig)
        } else {
            ensureReaderAndDecoder()
        }
    }

    val framesRendered: Long get() = decoder?.framesRendered ?: 0
    val framesDropped: Long get() = decoder?.framesDropped ?: 0
    val lastRenderAtMs: Long get() = decoder?.lastRenderAtMs ?: 0

    @Synchronized
    private fun ensureReaderAndDecoder() {
        val s = surface ?: return
        val c = config ?: return
        val input = stream ?: return
        if (decoder == null) {
            PhotopultLog.i("PreviewReceiver starting decoder ${c.width}x${c.height}")
            decoder = H264Decoder(c.width, c.height, s).also { it.start() }
        }
        if (readerThread == null) {
            readerThread = Thread({ readLoop(input) }, "photopult-stream-reader").also { it.start() }
        }
    }

    @Synchronized
    private fun restartDecoder(newConfig: CameraEvent.StreamConfig) {
        val s = surface ?: return
        PhotopultLog.i("PreviewReceiver reconfigure decoder -> ${newConfig.width}x${newConfig.height}")
        decoder?.stop()
        decoder = H264Decoder(newConfig.width, newConfig.height, s).also { it.start() }
    }

    private fun readLoop(input: InputStream) {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val frame = StreamFraming.readFrame(input) ?: break
                decoder?.submit(frame)
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
    }
}
