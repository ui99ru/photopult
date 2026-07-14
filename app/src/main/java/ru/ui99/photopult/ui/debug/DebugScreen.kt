package ru.ui99.photopult.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ui99.photopult.R
import ru.ui99.photopult.net.nearby.ConnectionState
import ru.ui99.photopult.ui.connect.NearbyViewModel
import ru.ui99.photopult.util.LogEntry
import ru.ui99.photopult.util.PhotopultLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hidden diagnostics screen (reached via a long-press on the version label). Shows the live
 * connection state and the recent event log for when adb isn't at hand.
 */
@Composable
fun DebugScreen(
    viewModel: NearbyViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val entries by PhotopultLog.entries.collectAsState()
    val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            TextButton(onClick = { PhotopultLog.clear() }) {
                Text(stringResource(R.string.debug_clear))
            }
        }

        Text(
            text = stringResource(R.string.debug_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.debug_state, stateLabel(state)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Live preview-stream metrics (refresh a few times a second).
        val streamConfig by viewModel.streamConfig.collectAsState()
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(500)
                tick++
            }
        }
        streamConfig?.let { config ->
            tick.let { } // recompose dependency so the counters below re-read
            Text(
                text = stringResource(R.string.debug_stream, config.width, config.height, config.rotationDegrees),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(
                    R.string.debug_frames,
                    viewModel.previewFramesRendered(),
                    viewModel.previewFramesDropped(),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Text(
            text = stringResource(R.string.debug_events, entries.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.debug_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            )
        } else {
            // Newest first.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries.asReversed()) { entry ->
                    LogRow(entry = entry, time = formatter.format(Date(entry.timeMillis)))
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, time: String) {
    Text(
        text = "$time ${entry.level}  ${entry.message}",
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = when (entry.level) {
            'E' -> MaterialTheme.colorScheme.error
            'W' -> Color(0xFFE0A030)
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun stateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> stringResource(R.string.debug_state_idle)
    is ConnectionState.Advertising -> stringResource(R.string.debug_state_advertising, state.localName)
    is ConnectionState.Discovering ->
        stringResource(R.string.debug_state_discovering, state.endpoints.size)
    is ConnectionState.Confirming ->
        stringResource(R.string.debug_state_confirming, state.peerName, state.code)
    is ConnectionState.Connected -> stringResource(R.string.debug_state_connected, state.peerName)
    is ConnectionState.Reconnecting -> stringResource(R.string.debug_state_reconnecting, state.peerName)
    is ConnectionState.Failed -> stringResource(R.string.debug_state_failed, state.userMessage)
}
