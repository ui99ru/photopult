package ru.ui99.photopult.ui.connect

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.ui99.photopult.R
import ru.ui99.photopult.net.protocol.CameraEvent

/**
 * Camera role, connected: the operator frames via the remote, so this screen just shows status,
 * offers a battery-saving dim mode, and keeps the session alive. The CameraSession (CameraX +
 * encoder + STREAM) runs for as long as this composable is on screen.
 */
@Composable
fun CameraConnectedScreen(
    viewModel: NearbyViewModel,
    onBack: () -> Unit,
    onForget: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var dimmed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.startCameraSession(lifecycleOwner) { deviceRotationDegrees(context) }
        onDispose { viewModel.stopSessions() }
    }

    if (dimmed) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { dimmed = false }
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFFE53935), CircleShape),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📷", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = stringResource(R.string.camera_on_air_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.camera_on_air_hint),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )
        Button(onClick = { dimmed = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.camera_dim))
        }
        TextButton(onClick = onOpenDebug) { Text(stringResource(R.string.debug_open)) }
        TextButton(onClick = onForget) { Text(stringResource(R.string.pair_forget)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.camera_disconnect)) }
    }
}

/**
 * Remote role, connected: the live preview fills the screen (rotated/mirrored per StreamConfig),
 * with zoom, lens switch, and camera battery overlaid.
 */
@Composable
fun RemoteConnectedScreen(
    viewModel: NearbyViewModel,
    peerName: String,
    peerState: CameraEvent.State?,
    onBack: () -> Unit,
    onForget: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val streamConfig by viewModel.streamConfig.collectAsState()
    val linkLevel by viewModel.linkQuality.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startPreviewReceiver()
        onDispose { viewModel.stopSessions() }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            viewModel.setPreviewSurface(Surface(st))
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            viewModel.setPreviewSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            update = { view ->
                streamConfig?.let { config ->
                    view.rotation = config.rotationDegrees.toFloat()
                    view.scaleX = if (config.mirrored) -1f else 1f
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Waiting overlay until frames arrive.
        if (streamConfig == null) {
            Text(
                text = stringResource(R.string.remote_waiting_preview),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Link quality bars (top-end), no numbers.
        LinkQualityBars(
            level = linkLevel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(16.dp),
        )

        // Top overlay: camera battery + name.
        val batteryText = peerState?.takeIf { it.battery in 0..100 }
            ?.let { stringResource(R.string.state_battery, it.battery) }
        Text(
            text = buildString {
                append(peerName)
                if (batteryText != null) append("   ").append(batteryText)
            },
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(16.dp),
        )

        // Bottom controls: zoom slider + switch + leave.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val maxZoom = peerState?.maxZoom ?: 1f
            if (maxZoom > 1f) {
                val zoom = peerState?.zoomRatio ?: 1f
                Text(
                    text = stringResource(R.string.state_zoom, formatZoom(zoom)),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Slider(
                    value = zoom.coerceIn(1f, maxZoom),
                    onValueChange = { viewModel.sendZoom(it) },
                    valueRange = 1f..maxZoom,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = { viewModel.switchCamera() }) {
                    Text(stringResource(R.string.remote_switch_camera))
                }
            }
            Row(horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onOpenDebug) { Text(stringResource(R.string.debug_open)) }
                TextButton(onClick = onForget) { Text(stringResource(R.string.pair_forget)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        }
    }
}

/** Three signal bars; [level] 1..3 filled, the rest dimmed. No numbers, per the brief. */
@Composable
private fun LinkQualityBars(level: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        val heights = listOf(8.dp, 13.dp, 18.dp)
        heights.forEachIndexed { index, h ->
            val filled = index < level.coerceIn(0, 3)
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(width = 5.dp, height = h)
                    .background(if (filled) Color.White else Color(0x55FFFFFF)),
            )
        }
    }
}

private fun formatZoom(zoom: Float): String = String.format("%.1f", zoom)

/** The camera phone's display rotation in degrees, for the upright-preview math. */
private fun deviceRotationDegrees(context: Context): Int {
    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
    return when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
