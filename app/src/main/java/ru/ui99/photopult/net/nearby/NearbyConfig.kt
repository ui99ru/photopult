package ru.ui99.photopult.net.nearby

import com.google.android.gms.nearby.connection.Strategy

/** Shared Nearby Connections configuration. Both devices must agree on these. */
object NearbyConfig {
    /** Unique per app; both ends advertise/discover the same id. */
    const val SERVICE_ID = "ru.ui99.photopult.v1"

    /**
     * P2P_POINT_TO_POINT: exactly one connection between two devices, and the highest-bandwidth
     * link (Wi-Fi Direct / hotspot) — required for the low-latency H.264 preview later.
     */
    val STRATEGY: Strategy = Strategy.P2P_POINT_TO_POINT
}
