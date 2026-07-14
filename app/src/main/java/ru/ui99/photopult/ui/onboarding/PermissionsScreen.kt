package ru.ui99.photopult.ui.onboarding

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.ui99.photopult.R
import ru.ui99.photopult.ui.common.ScreenScaffold
import ru.ui99.photopult.util.Permissions

/**
 * Explains, in plain language, why the app needs each permission, then requests them. On denial it
 * doesn't dead-end: it offers a button straight to system settings and a retry — per the brief's
 * edge case for refused permissions.
 */
@Composable
fun PermissionsScreen(
    onAllGranted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val required = remember { Permissions.required() }

    fun allGranted(): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var showDeniedHelp by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            onAllGranted()
        } else {
            showDeniedHelp = true
        }
    }

    // If the user already granted everything (e.g. returning from settings), move on.
    LaunchedEffect(Unit) {
        if (allGranted()) onAllGranted()
    }

    ScreenScaffold(modifier = modifier, verticalArrangement = Arrangement.Center) {
        if (showDeniedHelp) {
            DeniedContent(
                onOpenSettings = { context.openAppSettings() },
                onRetry = {
                    showDeniedHelp = false
                    if (allGranted()) onAllGranted() else launcher.launch(required.toTypedArray())
                },
                onBack = onBack,
            )
        } else {
            ExplanationContent(
                onGrant = {
                    if (allGranted()) onAllGranted() else launcher.launch(required.toTypedArray())
                },
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun ExplanationContent(onGrant: () -> Unit, onBack: () -> Unit) {
    Text(
        text = stringResource(R.string.perm_title),
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = stringResource(R.string.perm_intro),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
    )
    PermissionReason(stringResource(R.string.perm_camera))
    PermissionReason(stringResource(R.string.perm_nearby))
    PermissionReason(stringResource(R.string.perm_location))

    Spacer(Modifier.height(28.dp))
    Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.perm_grant))
    }
    TextButton(onClick = onBack) {
        Text(stringResource(R.string.action_back))
    }
}

@Composable
private fun DeniedContent(
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        text = stringResource(R.string.perm_denied_title),
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = stringResource(R.string.perm_denied_body),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
    )
    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.perm_open_settings))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        TextButton(onClick = onRetry) { Text(stringResource(R.string.perm_retry)) }
    }
}

@Composable
private fun PermissionReason(text: String) {
    Text(
        text = "•  $text",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

private fun android.content.Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
