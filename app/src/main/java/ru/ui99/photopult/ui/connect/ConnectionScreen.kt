package ru.ui99.photopult.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.ui99.photopult.R
import ru.ui99.photopult.net.nearby.ConnectionState
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.ui.common.ScreenScaffold

/**
 * Role-aware connection screen driven entirely by [NearbyViewModel.state]:
 * camera advertises, remote discovers and connects, and both confirm a matching code.
 */
@Composable
fun ConnectionScreen(
    role: Role,
    viewModel: NearbyViewModel,
    onBack: () -> Unit,
    onOpenDebug: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val peerState by viewModel.peerState.collectAsState()

    LaunchedEffect(role) { viewModel.start(role) }

    val current = state
    if (current is ConnectionState.Connected) {
        // Full-screen role-specific experience (preview stream).
        when (role) {
            Role.CAMERA -> CameraConnectedScreen(
                viewModel = viewModel,
                onBack = onBack,
                onForget = onForget,
                onOpenDebug = onOpenDebug,
                modifier = modifier,
            )
            Role.REMOTE -> RemoteConnectedScreen(
                viewModel = viewModel,
                peerName = current.peerName,
                peerState = peerState,
                onBack = onBack,
                onForget = onForget,
                onOpenDebug = onOpenDebug,
                modifier = modifier,
            )
        }
        return
    }

    ScreenScaffold(modifier = modifier) {
        when (val s = current) {
            is ConnectionState.Confirming -> ConfirmContent(
                state = s,
                onConfirm = viewModel::confirm,
                onReject = viewModel::reject,
            )

            is ConnectionState.Reconnecting -> SearchingLabel(
                title = stringResource(R.string.connect_reconnecting, s.peerName),
            )

            is ConnectionState.Failed -> FailedContent(
                message = s.userMessage,
                onRetry = viewModel::retry,
            )

            is ConnectionState.Discovering -> DiscoveringContent(
                endpoints = s.endpoints,
                connectingEndpointId = s.connectingEndpointId,
                onConnect = viewModel::connectTo,
            )

            is ConnectionState.Advertising -> AdvertisingContent(localName = s.localName)

            ConnectionState.Idle -> SearchingLabel(
                title = stringResource(
                    if (role == Role.CAMERA) R.string.connect_camera_searching
                    else R.string.connect_remote_searching,
                ),
            )

            is ConnectionState.Connected -> Unit // handled above
        }

        Spacer(Modifier.height(24.dp))
        if (current !is ConnectionState.Confirming) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        DebugLink(onOpenDebug)
    }
}

@Composable
private fun AdvertisingContent(localName: String) {
    SearchingLabel(title = stringResource(R.string.connect_camera_searching))
    Text(
        text = stringResource(R.string.connect_camera_name, localName),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = stringResource(R.string.connect_camera_hint),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DiscoveringContent(
    endpoints: List<ru.ui99.photopult.net.nearby.DiscoveredEndpoint>,
    connectingEndpointId: String?,
    onConnect: (String) -> Unit,
) {
    if (endpoints.isEmpty()) {
        SearchingLabel(title = stringResource(R.string.connect_remote_searching))
        return
    }
    Text(
        text = stringResource(R.string.connect_remote_found_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 16.dp),
    )
    val busy = connectingEndpointId != null
    endpoints.forEach { endpoint ->
        val isConnecting = connectingEndpointId == endpoint.endpointId
        Card(
            onClick = { onConnect(endpoint.endpointId) },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = "📷  ${endpoint.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isConnecting) {
                    Text(
                        text = stringResource(R.string.connect_connecting),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmContent(
    state: ConnectionState.Confirming,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    Text(
        text = stringResource(R.string.confirm_title),
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = stringResource(R.string.confirm_body),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
    )
    Text(
        text = state.emojis,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = state.code,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
    )
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.confirm_yes))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.confirm_no))
    }
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit) {
    Text(
        text = stringResource(R.string.connect_failed_title),
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
    )
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.connect_retry))
    }
}

@Composable
private fun SearchingLabel(title: String) {
    CircularProgressIndicator()
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 20.dp),
    )
}

@Composable
private fun DebugLink(onOpenDebug: () -> Unit) {
    TextButton(onClick = onOpenDebug) {
        Text(
            text = stringResource(R.string.debug_open),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
