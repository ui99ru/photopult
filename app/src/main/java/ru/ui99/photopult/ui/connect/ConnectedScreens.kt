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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.roundToInt
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
    var gridOn by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }

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

        // Tap-to-focus: maps the tap back through the display rotation/mirror to the sensor frame.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(streamConfig) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val u = (offset.x / w).coerceIn(0f, 1f)
                        val v = (offset.y / h).coerceIn(0f, 1f)
                        val (fx, fy) = mapTapToBuffer(
                            u, v,
                            streamConfig?.rotationDegrees ?: 0,
                            streamConfig?.mirrored ?: false,
                        )
                        viewModel.focusAt(fx, fy)
                        focusPoint = offset
                        focusTick++
                    }
                },
        )

        // 3×3 framing grid.
        if (gridOn) {
            GridOverlay(modifier = Modifier.fillMaxSize())
        }

        // Transient focus ring at the tapped spot.
        FocusRing(point = focusPoint, tick = focusTick)

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

        // Top overlay: camera battery + name; battery turns red and warns below 15%.
        val battery = peerState?.battery?.takeIf { it in 0..100 }
        val lowBattery = battery != null && battery < LOW_BATTERY_PERCENT
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(16.dp),
        ) {
            Text(
                text = buildString {
                    append(peerName)
                    if (battery != null) append("   ").append(stringResource(R.string.state_battery, battery))
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (lowBattery) Color(0xFFE53935) else Color.White,
            )
            if (lowBattery) {
                Text(
                    text = stringResource(R.string.remote_battery_low),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFE53935),
                )
            }
        }

        // Exposure compensation: vertical drag on the right edge, up = brighter.
        val evMin = peerState?.evMin ?: 0
        val evMax = peerState?.evMax ?: 0
        if (evMax > evMin) {
            ExposureBar(
                index = peerState?.evIndex ?: 0,
                min = evMin,
                max = evMax,
                onChange = { viewModel.setExposure(it) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .safeDrawingPadding()
                    .padding(end = 12.dp),
            )
        }

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

            // Shutter stays centered; flash + lens on the left, grid on the right.
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashButton(
                        mode = peerState?.flash ?: "off",
                        onCycle = { viewModel.setFlash(nextFlashMode(peerState?.flash ?: "off")) },
                    )
                    Spacer(Modifier.width(8.dp))
                    IconToggle(
                        label = stringResource(R.string.remote_switch_camera_short),
                        active = false,
                        onClick = { viewModel.switchCamera() },
                    )
                }
                Box(modifier = Modifier.align(Alignment.Center)) {
                    ShutterButton(
                        onShoot = { viewModel.shutter(timerSec) },
                        onBurstStart = { viewModel.burstStart() },
                        onBurstStop = { viewModel.burstStop() },
                    )
                }
                Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                    IconToggle(
                        label = stringResource(R.string.remote_grid),
                        active = gridOn,
                        onClick = { gridOn = !gridOn },
                    )
                }
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

private const val LOW_BATTERY_PERCENT = 15

/** Cycle order for the flash button: off → auto → on → off. */
private fun nextFlashMode(current: String): String = when (current) {
    "off" -> "auto"
    "auto" -> "on"
    else -> "off"
}

/**
 * Maps a tap in the *displayed* (upright, possibly mirrored) preview back to normalized coordinates
 * in the encoder's buffer, so the camera can set a focus point in sensor space. Best-effort: assumes
 * the buffer fills the view; the final on-device pass calibrates any residual offset.
 */
internal fun mapTapToBuffer(u: Float, v: Float, rotationDegrees: Int, mirrored: Boolean): Pair<Float, Float> {
    val du = u - 0.5f
    val dv = v - 0.5f
    // Undo the clockwise display rotation (rotate the tap back by -rotation).
    val (mdx, mdy) = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> dv to -du
        180 -> -du to -dv
        270 -> -dv to du
        else -> du to dv
    }
    val bx = 0.5f + if (mirrored) -mdx else mdx
    val by = 0.5f + mdy
    return bx.coerceIn(0f, 1f) to by.coerceIn(0f, 1f)
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    val line = Color(0x66FFFFFF)
    Canvas(modifier = modifier) {
        val third = size.width / 3f
        val thirdH = size.height / 3f
        for (i in 1..2) {
            drawLine(line, Offset(third * i, 0f), Offset(third * i, size.height), strokeWidth = 2f)
            drawLine(line, Offset(0f, thirdH * i), Offset(size.width, thirdH * i), strokeWidth = 2f)
        }
    }
}

@Composable
private fun FocusRing(point: Offset?, tick: Int) {
    if (point == null) return
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        visible = true
        delay(900)
        visible = false
    }
    if (visible) {
        val ring = Color(0xFFFFFFFF)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(ring, radius = 44f, center = point, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}

@Composable
private fun FlashButton(mode: String, onCycle: () -> Unit) {
    val label = when (mode) {
        "on" -> stringResource(R.string.flash_on)
        "auto" -> stringResource(R.string.flash_auto)
        else -> stringResource(R.string.flash_off)
    }
    IconToggle(label = label, active = mode != "off", onClick = onCycle)
}

@Composable
private fun IconToggle(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) Color(0x33FFFFFF) else Color(0x22000000)
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * Vertical exposure strip on the right edge. Dragging up raises the EV index, down lowers it; the
 * accumulated drag maps across the sensor's [min, max] range.
 */
@Composable
private fun ExposureBar(
    index: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = (max - min).coerceAtLeast(1)
    val latestIndex by rememberUpdatedState(index)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.exposure_value, if (index > 0) "+$index" else index.toString()),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(width = 36.dp, height = 180.dp)
                .background(Color(0x33000000), RoundedCornerShape(18.dp))
                .pointerInput(min, max) {
                    var start = latestIndex
                    val stepPx = size.height.toFloat() / range
                    var accum = 0f
                    detectVerticalDragGestures(
                        onDragStart = { accum = 0f; start = latestIndex },
                    ) { _, dragAmount ->
                        accum -= dragAmount // up (negative dy) increases EV
                        val steps = (accum / stepPx).roundToInt()
                        val next = (start + steps).coerceIn(min, max)
                        if (next != latestIndex) onChange(next)
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Fill indicator: proportion of the range from min to current.
            val fraction = ((index - min).toFloat() / range).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(width = 28.dp, height = (172 * fraction).dp)
                    .background(Color(0x88FFFFFF), RoundedCornerShape(14.dp)),
            )
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
