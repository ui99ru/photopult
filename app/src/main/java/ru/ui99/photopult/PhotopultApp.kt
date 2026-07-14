package ru.ui99.photopult

import android.app.Application

/**
 * Application entry point. Kept intentionally thin at this stage; process-wide singletons
 * (Nearby transport, session state) are introduced in later stages.
 */
class PhotopultApp : Application()
