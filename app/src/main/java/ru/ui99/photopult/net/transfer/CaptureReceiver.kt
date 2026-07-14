package ru.ui99.photopult.net.transfer

import android.content.Context
import android.net.Uri
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.ui99.photopult.net.nearby.NearbyConnectionManager
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.util.PhotoStorage
import ru.ui99.photopult.util.PhotopultLog

/**
 * Remote side of the capture flow: shows a thumbnail the instant a photo is taken, then saves the
 * full-quality file to the gallery once its background FILE transfer completes. Correlates the
 * thumbnail, the [CameraEvent.PhotoIncoming] announcement and the FILE payload by ids.
 */
class CaptureReceiver(
    private val context: Context,
    private val manager: NearbyConnectionManager,
) {
    data class ReceivedPhoto(
        val photoId: String,
        val thumbnailBase64: String? = null,
        val savedUri: Uri? = null,
        val delivered: Boolean = false,
    )

    private val order = ArrayList<String>()
    private val byId = HashMap<String, ReceivedPhoto>()
    private val payloadToPhoto = HashMap<Long, String>()
    private val filePayloads = HashMap<Long, Payload>()

    private val _photos = MutableStateFlow<List<ReceivedPhoto>>(emptyList())
    val photos: StateFlow<List<ReceivedPhoto>> = _photos.asStateFlow()

    /** Emits on each successful capture — the UI vibrates and flashes "Снято!". */
    private val _snapEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val snapEvents: SharedFlow<Unit> = _snapEvents.asSharedFlow()

    fun start() {
        manager.captureEventListener = { onCaptureEvent(it) }
        manager.fileListener = { onFilePayload(it) }
        manager.transferUpdateListener = { onTransferUpdate(it) }
    }

    private fun onCaptureEvent(event: CameraEvent) {
        when (event) {
            is CameraEvent.Thumbnail -> upsert(event.photoId) { it.copy(thumbnailBase64 = event.jpegBase64) }
            is CameraEvent.CaptureDone -> {
                if (event.ok) {
                    upsert(event.photoId) { it }
                    _snapEvents.tryEmit(Unit)
                } else {
                    PhotopultLog.w("remote: captureDone not ok: ${event.userMessage}")
                }
            }
            is CameraEvent.PhotoIncoming -> {
                payloadToPhoto[event.payloadId] = event.photoId
                tryComplete(event.payloadId)
            }
            else -> Unit
        }
    }

    private fun onFilePayload(payload: Payload) {
        filePayloads[payload.id] = payload
    }

    private fun onTransferUpdate(update: PayloadTransferUpdate) {
        if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
            tryComplete(update.payloadId)
        }
    }

    /** When both the announcement and the completed file are present, save to the gallery. */
    private fun tryComplete(payloadId: Long) {
        val photoId = payloadToPhoto[payloadId] ?: return
        val payload = filePayloads[payloadId] ?: return
        val uri = payload.asFile()?.asUri() ?: runCatching {
            payload.asFile()?.asJavaFile()?.let { Uri.fromFile(it) }
        }.getOrNull() ?: return
        val saved = PhotoStorage.copyUriToGallery(context, uri, "Photopult_$photoId.jpg")
        PhotopultLog.i("remote: saved photo $photoId -> $saved")
        upsert(photoId) { it.copy(savedUri = saved, delivered = true) }
        payloadToPhoto.remove(payloadId)
        filePayloads.remove(payloadId)
    }

    @Synchronized
    private fun upsert(photoId: String, transform: (ReceivedPhoto) -> ReceivedPhoto) {
        val existing = byId[photoId]
        if (existing == null) {
            order.add(photoId)
            byId[photoId] = transform(ReceivedPhoto(photoId))
        } else {
            byId[photoId] = transform(existing)
        }
        _photos.value = order.mapNotNull { byId[it] }
    }

    fun stop() {
        manager.captureEventListener = null
        manager.fileListener = null
        manager.transferUpdateListener = null
    }
}
