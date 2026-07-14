package ru.ui99.photopult.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 */
@Composable
fun AppNavigation() {
    var screen by rememberSaveable { mutableStateOf(Screen.ROLE_SELECTION) }
    var returnTo by rememberSaveable { mutableStateOf(Screen.ROLE_SELECTION) }
    var chosenRole by rememberSaveable { mutableStateOf<Role?>(null) }

    val nearbyViewModel: NearbyViewModel = viewModel()

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
                )
            }
        }

        Screen.DEBUG -> DebugScreen(
            viewModel = nearbyViewModel,
            onBack = { screen = returnTo },
        )
    }
}
