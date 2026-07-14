package ru.ui99.photopult.ui.role

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.ui99.photopult.R
import ru.ui99.photopult.ui.common.ScreenScaffold

/**
 * Placeholders shown after a role is chosen and permissions are granted. The real camera and
 * remote experiences (Nearby connection, preview, capture) arrive in Stages 2–5.
 */
@Composable
fun CameraRolePlaceholderScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    RolePlaceholder(
        message = stringResource(R.string.camera_role_placeholder),
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun RemoteRolePlaceholderScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    RolePlaceholder(
        message = stringResource(R.string.remote_role_placeholder),
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun RolePlaceholder(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenScaffold(modifier = modifier) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }
    }
}
