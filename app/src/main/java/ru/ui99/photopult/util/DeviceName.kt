package ru.ui99.photopult.util

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * A human-readable name for this phone — the same name the user sees as their Bluetooth device
 * name (Settings.Global "device_name", e.g. "Redmi Note 13 Pro"). Falls back to [Build.MODEL] only
 * when that is empty (which can be an internal code like "2312FPCA6G"). The brief requires cards
 * and every mention of the other device to show a friendly name, not a model code.
 */
object DeviceName {
    fun of(context: Context): String {
        val fromSettings = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        val name = fromSettings?.trim()?.takeIf { it.isNotEmpty() }
        return (name ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Phone").take(40)
    }
}
