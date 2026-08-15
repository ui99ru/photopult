package ru.ui99.photopult.ui.role

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.ui99.photopult.BuildConfig
import ru.ui99.photopult.R
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.ui.common.BigOptionCard
import ru.ui99.photopult.ui.common.ScreenScaffold
import ru.ui99.photopult.ui.theme.PhotopultTheme

/**
 * First screen: pick whether this phone is the camera or the remote. Two big cards, a friendly
 * title, no jargon — the emoji stand in for the "one picture explains it" diagram from the brief
 * (a richer illustration can replace them later).
 *
 * The version label at the bottom opens the hidden debug screen on a long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoleSelectionScreen(
    onRoleChosen: (Role) -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(modifier = modifier) {
        Text(
            text = stringResource(R.string.role_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.role_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
        )

        BigOptionCard(
            title = stringResource(R.string.role_camera_title),
            description = stringResource(R.string.role_camera_desc),
            onClick = { onRoleChosen(Role.CAMERA) },
        )
        Spacer(Modifier.height(16.dp))
        BigOptionCard(
            title = stringResource(R.string.role_remote_title),
            description = stringResource(R.string.role_remote_desc),
            onClick = { onRoleChosen(Role.REMOTE) },
        )

        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .combinedClickable(onClick = {}, onLongClick = onOpenDebug)
                .padding(8.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RoleSelectionPreview() {
    PhotopultTheme {
        RoleSelectionScreen(onRoleChosen = {}, onOpenDebug = {})
    }
}
