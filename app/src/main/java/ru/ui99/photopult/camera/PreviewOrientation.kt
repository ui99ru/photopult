package ru.ui99.photopult.camera

/**
 * Preview mirroring helper. The rotation needed to display the stream upright is taken directly from
 * CameraX (the preview SurfaceRequest's TransformationInfo, driven by an OrientationEventListener),
 * which is device-authoritative and tracks the phone rotating — so no hand-rolled rotation math
 * lives here anymore. Only the front-lens mirror decision remains, which is a display choice.
 */
object PreviewOrientation {

    /** Front lens frames are horizontally mirrored for a natural selfie view. */
    fun isMirrored(front: Boolean): Boolean = front
}
