package ru.ui99.photopult.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ru.ui99.photopult.ui.gms.GmsGateScreen
import ru.ui99.photopult.ui.theme.PhotopultTheme
import ru.ui99.photopult.util.GmsAvailability
import ru.ui99.photopult.util.GmsStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PhotopultTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhotopultRoot()
                }
            }
        }
    }
}

/**
 * Gates the whole app on Google Play Services (Nearby depends on it). Only when GMS is available
 * do we show the normal flow — otherwise the user gets an honest, non-crashing explanation.
 */
@Composable
private fun PhotopultRoot() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(GmsAvailability.check(context)) }

    when (status) {
        GmsStatus.AVAILABLE -> AppNavigation()
        GmsStatus.UPDATE_REQUIRED,
        GmsStatus.UNSUPPORTED,
        -> GmsGateScreen(
            status = status,
            onRetry = { status = GmsAvailability.check(context) },
        )
    }
}
