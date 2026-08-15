package ru.ui99.photopult.net.transfer

/**
 * Tracks full-quality photos waiting to reach the remote, so the queue survives a reconnect: a
 * transfer interrupted mid-flight is re-queued and re-sent. Pure and synchronized; the camera
 * session drives it.
 */
class TransferQueue {

    enum class Status { PENDING, SENDING, SENT }

    data class Item(val photoId: String, val path: String, var status: Status = Status.PENDING)

    private val items = LinkedHashMap<String, Item>()

    @Synchronized
    fun add(photoId: String, path: String) {
        items[photoId] = Item(photoId, path)
    }

    /** Next photo to send (oldest PENDING), atomically marked SENDING. Null if none. */
    @Synchronized
    fun nextPending(): Item? {
        val item = items.values.firstOrNull { it.status == Status.PENDING } ?: return null
        item.status = Status.SENDING
        return item
    }

    @Synchronized
    fun markSent(photoId: String) {
        items[photoId]?.status = Status.SENT
    }

    /** On reconnect, anything left SENDING is unfinished → re-queue it. */
    @Synchronized
    fun requeueSending() {
        items.values.forEach { if (it.status == Status.SENDING) it.status = Status.PENDING }
    }

    @Synchronized
    fun pendingCount(): Int = items.values.count { it.status != Status.SENT }

    @Synchronized
    fun snapshot(): List<Item> = items.values.map { it.copy() }
}
