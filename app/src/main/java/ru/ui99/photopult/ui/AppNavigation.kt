package ru.ui99.photopult.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.ui.onboarding.PermissionsScreen
import ru.ui99.photopult.ui.role.CameraRolePlaceholderScreen
import ru.ui99.photopult.ui.role.RemoteRolePlaceholderScreen
import ru.ui99.photopult.ui.role.RoleSelectionScreen

/** The distinct places the Stage-1 skeleton can be. */
private enum class Screen {
    ROLE_SELECTION,
    PERMISSIONS,
    CAMERA_PLACEHOLDER,
    REMOTE_PLACEHOLDER,
}

/**
 * Minimal state-driven navigation for the skeleton: role selection → permissions → a placeholder
 * for the chosen role. A real NavHost/back stack is introduced once there are more destinations.
 */
@Composable
fun AppNavigation() {
    var screen by rememberSaveable { mutableStateOf(Screen.ROLE_SELECTION) }
    var chosenRole by rememberSaveable { mutableStateOf<Role?>(null) }

    when (screen) {
        Screen.ROLE_SELECTION -> RoleSelectionScreen(
            onRoleChosen = { role ->
                chosenRole = role
                screen = Screen.PERMISSIONS
            },
        )

        Screen.PERMISSIONS -> PermissionsScreen(
            onAllGranted = {
                screen = when (chosenRole) {
                    Role.CAMERA -> Screen.CAMERA_PLACEHOLDER
                    Role.REMOTE -> Screen.REMOTE_PLACEHOLDER
                    null -> Screen.ROLE_SELECTION
                }
            },
            onBack = { screen = Screen.ROLE_SELECTION },
        )

        Screen.CAMERA_PLACEHOLDER -> CameraRolePlaceholderScreen(
            onBack = { screen = Screen.ROLE_SELECTION },
        )

        Screen.REMOTE_PLACEHOLDER -> RemoteRolePlaceholderScreen(
            onBack = { screen = Screen.ROLE_SELECTION },
        )
    }
}
