package ru.ui99.photopult.camera

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service (type `camera`) that will keep the capture session alive across incoming
 * calls, notifications and app backgrounding — the top reliability complaint about SayCheese.
 *
 * Stub for now: the manifest declaration (with `foregroundServiceType="camera"`) is added in
 * Stage 1 so we never have to revisit the manifest later; the actual session-keeping logic and
 * `startForeground(...)` notification land in Stage 6.
 */
class CaptureForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
