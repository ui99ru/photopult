package ru.ui99.photopult.ui.connect

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import ru.ui99.photopult.R
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.net.transfer.CaptureReceiver

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
    val countdown by viewModel.cameraCountdown.collectAsState()
    val snapFlash by viewModel.snapFlash.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startCameraSession(lifecycleOwner) { deviceRotationDegrees(context) }
        onDispose { viewModel.stopSessions() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (dimmed) {
            Box(
                modifier = Modifier
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
        } else {
            Column(
                modifier = Modifier
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

        // Big countdown for the people being photographed, and the "Снято!" flash.
        CountdownOverlay(countdown, Modifier.align(Alignment.Center))
        SnapFlash(snapFlash, Modifier.align(Alignment.Center))
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
    val context = LocalContext.current
    val streamConfig by viewModel.streamConfig.collectAsState()
    val linkLevel by viewModel.linkQuality.collectAsState()
    val countdown by viewModel.remoteCountdown.collectAsState()
    val photos by viewModel.receivedPhotos.collectAsState()
    var timerSec by remember { mutableIntStateOf(0) }
    var snapTick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        viewModel.startPreviewReceiver()
        onDispose { viewModel.stopSessions() }
    }

    LaunchedEffect(Unit) {
        viewModel.remoteSnap.collect {
            snapTick++
            vibrateShort(context)
        }
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

        // Bottom controls: thumbnails + zoom + shutter/timer/switch + leave.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ThumbnailStrip(photos)

            val maxZoom = peerState?.maxZoom ?: 1f
            if (maxZoom > 1f) {
                val zoom = peerState?.zoomRatio ?: 1f
                Slider(
                    value = zoom.coerceIn(1f, maxZoom),
                    onValueChange = { viewModel.sendZoom(it) },
                    valueRange = 1f..maxZoom,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TimerSelector(selected = timerSec, onSelect = { timerSec = it })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { viewModel.switchCamera() }) {
                    Text(stringResource(R.string.remote_switch_camera))
                }
                ShutterButton(
                    onShoot = { viewModel.shutter(timerSec) },
                    onBurstStart = { viewModel.burstStart() },
                    onBurstStop = { viewModel.burstStop() },
                )
                Spacer(Modifier.width(72.dp))
            }

            Row(horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onOpenDebug) { Text(stringResource(R.string.debug_open)) }
                TextButton(onClick = onForget) { Text(stringResource(R.string.pair_forget)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        }

        // Countdown for the shot and the "Снято!" flash.
        CountdownOverlay(countdown, Modifier.align(Alignment.Center))
        SnapFlash(snapTick, Modifier.align(Alignment.Center))
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

@Composable
private fun CountdownOverlay(secondsLeft: Int?, modifier: Modifier = Modifier) {
    if (secondsLeft != null && secondsLeft > 0) {
        Text(
            text = secondsLeft.toString(),
            modifier = modifier,
            color = Color.White,
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SnapFlash(tick: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick > 0) {
            visible = true
            delay(900)
            visible = false
        }
    }
    if (visible) {
        Box(
            modifier = modifier
                .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                .padding(horizontal = 32.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.snapped),
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TimerSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.Center) {
        listOf(0, 3, 10).forEach { sec ->
            val label = if (sec == 0) stringResource(R.string.timer_now) else stringResource(R.string.timer_sec, sec)
            TextButton(onClick = { onSelect(sec) }) {
                Text(
                    text = label,
                    color = if (sec == selected) MaterialTheme.colorScheme.primary else Color.White,
                    fontWeight = if (sec == selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(
    onShoot: () -> Unit,
    onBurstStart: () -> Unit,
    onBurstStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(Color.White, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onShoot() },
                    onLongPress = { onBurstStart() },
                    onPress = {
                        tryAwaitRelease()
                        onBurstStop()
                    },
                )
            },
    )
}

@Composable
private fun ThumbnailStrip(photos: List<CaptureReceiver.ReceivedPhoto>) {
    if (photos.isEmpty()) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        items(photos.asReversed()) { photo ->
            val bitmap = remember(photo.thumbnailBase64) {
                photo.thumbnailBase64?.let { decodeBase64Jpeg(it) }
            }
            Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    Box(modifier = Modifier.size(56.dp).background(Color(0x33FFFFFF)))
                }
                if (photo.delivered) {
                    Text(
                        text = "✓",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                    )
                }
            }
        }
    }
}

private fun decodeBase64Jpeg(base64: String): android.graphics.Bitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

private fun vibrateShort(context: Context) {
    runCatching {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
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
