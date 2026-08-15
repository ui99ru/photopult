package ru.ui99.photopult.util

import android.content.Context
import java.util.UUID
import ru.ui99.photopult.net.protocol.Role

/**
 * Remembers the paired device (and the chosen role) so the next launch reconnects with zero taps.
 *
 * Identity is a stable per-install UUID advertised alongside the device name — robust to duplicate
 * device names and to a user renaming their phone. Backed by SharedPreferences.
 */
class PairingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("photopult_pairing", Context.MODE_PRIVATE)

    /** Stable id for THIS install; generated once. */
    fun installId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    var rememberedRole: Role?
        get() = prefs.getString(KEY_ROLE, null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_ROLE) else putString(KEY_ROLE, value.name)
        }.apply()

    val peerId: String? get() = prefs.getString(KEY_PEER_ID, null)
    val peerName: String? get() = prefs.getString(KEY_PEER_NAME, null)

    fun rememberPeer(id: String, name: String) {
        prefs.edit().putString(KEY_PEER_ID, id).putString(KEY_PEER_NAME, name).apply()
    }

    /** Forget the paired device and the chosen role (back to first-run behaviour). */
    fun forget() {
        prefs.edit().remove(KEY_PEER_ID).remove(KEY_PEER_NAME).remove(KEY_ROLE).apply()
    }

    private companion object {
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_ROLE = "role"
        const val KEY_PEER_ID = "peer_id"
        const val KEY_PEER_NAME = "peer_name"
    }
}
