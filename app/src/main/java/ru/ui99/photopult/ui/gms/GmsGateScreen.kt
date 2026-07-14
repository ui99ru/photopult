package ru.ui99.photopult.ui.gms

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.ui99.photopult.R
import ru.ui99.photopult.ui.common.ScreenScaffold
import ru.ui99.photopult.util.GmsStatus

/**
 * Shown when Google Play Services is missing or out of date. Honest, plain-language explanation
 * instead of a silent crash — the brief's RuStore edge case (some devices have no GMS).
 */
@Composable
fun GmsGateScreen(
    status: GmsStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val updatable = status == GmsStatus.UPDATE_REQUIRED
    val title = if (updatable) R.string.gms_update_title else R.string.gms_missing_title
    val body = if (updatable) R.string.gms_update_body else R.string.gms_missing_body

    ScreenScaffold(modifier = modifier) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (updatable) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.perm_retry))
            }
        }
    }
}
