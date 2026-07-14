package ru.ui99.photopult.util

import android.Manifest
import android.os.Build

/**
 * The runtime permissions Photopult needs, computed for the running Android version.
 *
 * The set differs a lot across API levels: the Bluetooth runtime permissions arrived in API 31,
 * NEARBY_WIFI_DEVICES in API 33, and older versions instead require fine location for discovery.
 * Keeping this in one place lets the onboarding screen request exactly what the device needs and
 * nothing more.
 */
object Permissions {

    /** Permissions required regardless of the chosen role. */
    fun required(): List<String> = buildList {
        add(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.BLUETOOTH)
            @Suppress("DEPRECATION")
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // Nearby discovery needs fine location on API <= 32.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** True when this permission is only requested on older devices (helps explain "why"). */
    fun isLegacyLocation(permission: String): Boolean =
        permission == Manifest.permission.ACCESS_FINE_LOCATION
}
