package ru.ui99.photopult.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.ui.connect.ConnectionScreen
import ru.ui99.photopult.ui.connect.NearbyViewModel
import ru.ui99.photopult.ui.debug.DebugScreen
import ru.ui99.photopult.ui.onboarding.PermissionsScreen
import ru.ui99.photopult.ui.role.RoleSelectionScreen

/** The distinct places the app can be. */
private enum class Screen {
    ROLE_SELECTION,
    PERMISSIONS,
    CONNECT,
    DEBUG,
}

/**
 * State-driven navigation: role selection → permissions → connection (camera or remote). The
 * hidden debug screen is reachable from the role and connection screens and returns to wherever
 * it was opened from. The Nearby ViewModel is activity-scoped so the link survives navigation.
 *
 * If a previous pairing is remembered (role + peer) and permissions are already granted, the app
 * jumps straight to the connection screen on launch and reconnects with zero taps.
 */
@Composable
fun AppNavigation() {
    val nearbyViewModel: NearbyViewModel = viewModel()

    val rememberedRole = remember { nearbyViewModel.rememberedRole }
    val autoConnect = remember {
        rememberedRole != null &&
            nearbyViewModel.hasRememberedPeer() &&
            nearbyViewModel.permissionsGranted()
    }

    var screen by rememberSaveable {
        mutableStateOf(if (autoConnect) Screen.CONNECT else Screen.ROLE_SELECTION)
    }
    var returnTo by rememberSaveable { mutableStateOf(Screen.ROLE_SELECTION) }
    var chosenRole by rememberSaveable { mutableStateOf(if (autoConnect) rememberedRole else null) }

    when (screen) {
        Screen.ROLE_SELECTION -> RoleSelectionScreen(
            onRoleChosen = { role ->
                chosenRole = role
                screen = Screen.PERMISSIONS
            },
            onOpenDebug = {
                returnTo = Screen.ROLE_SELECTION
                screen = Screen.DEBUG
            },
        )

        Screen.PERMISSIONS -> PermissionsScreen(
            onAllGranted = { screen = Screen.CONNECT },
            onBack = { screen = Screen.ROLE_SELECTION },
        )

        Screen.CONNECT -> {
            val role = chosenRole
            if (role == null) {
                screen = Screen.ROLE_SELECTION
            } else {
                ConnectionScreen(
                    role = role,
                    viewModel = nearbyViewModel,
                    onBack = {
                        nearbyViewModel.reset()
                        screen = Screen.ROLE_SELECTION
                    },
                    onOpenDebug = {
                        returnTo = Screen.CONNECT
                        screen = Screen.DEBUG
                    },
                    onForget = {
                        nearbyViewModel.forgetPairing()
                        chosenRole = null
                        screen = Screen.ROLE_SELECTION
                    },
                )
            }
        }

        Screen.DEBUG -> DebugScreen(
            viewModel = nearbyViewModel,
            onBack = { screen = returnTo },
        )
    }
}
