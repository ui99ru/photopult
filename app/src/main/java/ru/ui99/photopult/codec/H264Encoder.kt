package ru.ui99.photopult.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import ru.ui99.photopult.util.PhotopultLog

/**
 * Hardware H.264 encoder with a Surface input, tuned for low latency (CBR, short GOP, no B-frames,
 * low-latency mode on API 30+). CameraX renders preview frames into [inputSurface]; encoded access
 * units are delivered to [onOutput] as they come out.
 *
 * Bitrate can be changed on the fly for the adaptive ladder (Stage 4); a keyframe can be forced on
 * reconnect so a new decoder can start immediately.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    initialBitrate: Int,
    private val fps: Int,
    private val iFrameIntervalSec: Int,
    private val onOutput: (data: ByteArray, ptsUs: Long, keyframe: Boolean, config: Boolean) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null

    /** Valid between [start] and [stop]; CameraX draws frames here. */
    var inputSurface: Surface? = null
        private set

    private var bitrate = initialBitrate

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
            // No B-frames — they add latency and reordering.
            setInteger("max-bframes", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            }
        }

        val handlerThread = HandlerThread("photopult-encoder").also { it.start() }
        val handler = Handler(handlerThread.looper)
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec.setCallback(callback, handler)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()

        codec = mediaCodec
        thread = handlerThread
        PhotopultLog.i("H264Encoder started ${width}x$height @${fps}fps ${bitrate}bps")
    }

    /** Adaptive ladder: change target bitrate without restarting the codec. */
    fun setBitrate(bps: Int) {
        bitrate = bps
        runCatching {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            })
        }.onFailure { PhotopultLog.w("setBitrate failed: ${it.message}") }
    }

    /** Force the next frame to be a keyframe (e.g. a fresh remote just connected). */
    fun requestKeyframe() {
        runCatching {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { PhotopultLog.w("requestKeyframe failed: ${it.message}") }
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { thread?.quitSafely() }
        codec = null
        inputSurface = null
        thread = null
        PhotopultLog.i("H264Encoder stopped")
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input — nothing to feed manually.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                    onOutput(data, info.presentationTimeUs, keyframe, config)
                }
            } catch (e: Exception) {
                PhotopultLog.e("encoder output error", e)
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            PhotopultLog.e("encoder error transient=${e.isTransient} recoverable=${e.isRecoverable}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            PhotopultLog.i("encoder output format: $format")
        }
    }
}
