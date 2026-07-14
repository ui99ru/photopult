package ru.ui99.photopult.util

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Result of checking Google Play Services availability.
 *
 * Nearby Connections needs Google Play Services. On devices without it (some Huawei models on
 * RuStore, for example) we must tell the user honestly *before* they waste time — see the brief's
 * edge cases.
 */
enum class GmsStatus {
    /** Available — the app can work. */
    AVAILABLE,

    /** Present but needs an update; the user can fix this. */
    UPDATE_REQUIRED,

    /** Missing or disabled with no user-resolvable path — the app cannot work here. */
    UNSUPPORTED,
}

object GmsAvailability {

    fun check(context: Context): GmsStatus {
        val availability = GoogleApiAvailability.getInstance()
        return when (val code = availability.isGooglePlayServicesAvailable(context)) {
            ConnectionResult.SUCCESS -> GmsStatus.AVAILABLE
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
            ConnectionResult.SERVICE_UPDATING,
            ConnectionResult.SERVICE_DISABLED,
            -> if (availability.isUserResolvableError(code)) {
                GmsStatus.UPDATE_REQUIRED
            } else {
                GmsStatus.UNSUPPORTED
            }
            else -> if (availability.isUserResolvableError(code)) {
                GmsStatus.UPDATE_REQUIRED
            } else {
                GmsStatus.UNSUPPORTED
            }
        }
    }
}
