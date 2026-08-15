package ru.ui99.photopult.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import ru.ui99.photopult.util.PhotopultLog

/**
 * Hardware H.264 decoder rendering to a [Surface] (the remote's preview view).
 *
 * Frames are pushed in via [submit] from the stream reader. When no input buffer is free we don't
 * buffer — we drop and let [KeyframeGate] skip to the next keyframe, so the picture is always the
 * freshest decodable one (smoothness over sharpness, per the brief). Simple counters feed the
 * debug screen.
 */
class H264Decoder(
    private val width: Int,
    private val height: Int,
    private val surface: Surface,
) {
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private val gate = KeyframeGate()
    private val lock = Any()
    private val freeIndices = ArrayDeque<Int>()

    @Volatile var framesRendered: Long = 0; private set
    @Volatile var framesDropped: Long = 0; private set
    @Volatile var lastRenderAtMs: Long = 0; private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        val handlerThread = HandlerThread("photopult-decoder").also { it.start() }
        val handler = Handler(handlerThread.looper)
        val mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec.setCallback(callback, handler)
        mediaCodec.configure(format, surface, null, 0)
        mediaCodec.start()
        codec = mediaCodec
        thread = handlerThread
        PhotopultLog.i("H264Decoder started ${width}x$height")
    }

    /** Push one encoded frame. Safe to call from the stream reader thread. */
    fun submit(frame: StreamFraming.Frame) {
        val mediaCodec = codec ?: return
        if (!gate.accept(frame)) {
            framesDropped++
            return
        }
        val index = synchronized(lock) { if (freeIndices.isEmpty()) null else freeIndices.removeFirst() }
        if (index == null) {
            // Decoder is behind — drop this frame and resync at the next keyframe.
            gate.fallBehind()
            framesDropped++
            return
        }
        try {
            val buffer = mediaCodec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(frame.data)
            val flags = if (frame.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            mediaCodec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, flags)
        } catch (e: Exception) {
            PhotopultLog.e("decoder submit error", e)
        }
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { thread?.quitSafely() }
        synchronized(lock) { freeIndices.clear() }
        codec = null
        thread = null
        PhotopultLog.i("H264Decoder stopped (rendered=$framesRendered dropped=$framesDropped)")
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) { freeIndices.addLast(index) }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            // Render immediately to the surface.
            runCatching { codec.releaseOutputBuffer(index, true) }
            framesRendered++
            lastRenderAtMs = System.currentTimeMillis()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            PhotopultLog.e("decoder error transient=${e.isTransient} recoverable=${e.isRecoverable}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            PhotopultLog.i("decoder output format: $format")
        }
    }
}
