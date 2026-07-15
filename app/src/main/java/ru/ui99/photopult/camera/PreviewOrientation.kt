package ru.ui99.photopult.camera

/**
 * Rotation math for the preview. Frames are fed to the encoder from a raw Surface, so they carry
 * the sensor's native orientation — the remote must rotate them to show the scene upright, and
 * mirror the front lens. Getting this right on any device rotation is the pain point the brief
 * calls out about SayCheese, so it lives here, pure and tested.
 */
object PreviewOrientation {

    /**
     * Degrees the remote must rotate the decoded frame to display it upright.
     *
     * @param sensorRotation camera sensor orientation (0/90/180/270)
     * @param deviceRotation the camera phone's display rotation in degrees (0/90/180/270)
     * @param front true for the front lens
     */
    fun rotationForUpright(sensorRotation: Int, deviceRotation: Int, front: Boolean): Int {
        val s = norm(sensorRotation)
        val d = norm(deviceRotation)
        val base = if (front) norm(s + d) else norm(s - d)
        // Observed on the test devices (Xiaomi/POCO, MIUI): frames from the encoder-input Surface
        // come out 180° rotated when the camera phone is in a portrait orientation, while landscape
        // is already upright. Add the half-turn for portrait device rotations (0°/180°) only, so the
        // known-good landscape path is untouched.
        val portraitFix = if (d == 0 || d == 180) 180 else 0
        return norm(base + portraitFix)
    }

    /** Front lens frames are horizontally mirrored. */
    fun isMirrored(front: Boolean): Boolean = front

    private fun norm(deg: Int): Int = ((deg % 360) + 360) % 360
}
