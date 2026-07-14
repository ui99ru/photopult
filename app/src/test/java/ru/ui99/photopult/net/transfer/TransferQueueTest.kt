package ru.ui99.photopult.net.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferQueueTest {

    @Test
    fun nextPending_marksSending_andReturnsOldestFirst() {
        val q = TransferQueue()
        q.add("a", "/a.jpg")
        q.add("b", "/b.jpg")
        val first = q.nextPending()!!
        assertEquals("a", first.photoId)
        assertEquals(TransferQueue.Status.SENDING, first.status)
        // 'a' is now SENDING, so nextPending returns 'b'
        assertEquals("b", q.nextPending()!!.photoId)
        assertNull(q.nextPending())
    }

    @Test
    fun markSent_removesFromPendingCount() {
        val q = TransferQueue()
        q.add("a", "/a.jpg")
        q.nextPending()
        assertEquals(1, q.pendingCount())
        q.markSent("a")
        assertEquals(0, q.pendingCount())
    }

    @Test
    fun requeueSending_reoffersInterruptedTransfers() {
        val q = TransferQueue()
        q.add("a", "/a.jpg")
        q.nextPending() // a -> SENDING
        q.requeueSending() // reconnect: a -> PENDING
        assertEquals("a", q.nextPending()!!.photoId)
    }

    @Test
    fun sentItems_areNotReoffered() {
        val q = TransferQueue()
        q.add("a", "/a.jpg")
        q.nextPending()
        q.markSent("a")
        q.requeueSending()
        assertNull(q.nextPending())
    }
}
