package dev.marck.jsyncdrive.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.kit.Gauge
import dev.marck.jsyncdrive.ui.kit.Panel
import dev.marck.jsyncdrive.ui.kit.PrimaryButton
import dev.marck.jsyncdrive.ui.kit.TextAction
import dev.marck.jsyncdrive.ui.theme.AppShapes
import dev.marck.jsyncdrive.update.UpdateState

/** Tells that a newer build is in the Drive and offers to install it; shows the progress while it does. */
@Composable
fun UpdateBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val u = vm.update
    if (u !is UpdateState.Available && u !is UpdateState.Downloading && u !is UpdateState.Installing) return
    Panel(
        modifier.fillMaxWidth(), shape = AppShapes.tile, color = cs.primaryContainer,
        contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SystemUpdate, null, tint = cs.onPrimaryContainer)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    when (u) {
                        is UpdateState.Available -> {
                            Text(stringResource(R.string.update_available, u.info.versionName), style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                            if (u.info.notes.isNotEmpty()) Text(u.info.notes, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer, maxLines = 2)
                        }
                        is UpdateState.Downloading -> Text(
                            stringResource(R.string.update_downloading, if (u.total > 0) (u.done * 100 / u.total).toInt() else 0),
                            style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer,
                        )
                        else -> Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    }
                }
                if (u is UpdateState.Available) PrimaryButton(onClick = { vm.installUpdate() }) { Text(stringResource(R.string.update_now)) }
            }
            when (u) {
                is UpdateState.Downloading -> Gauge(if (u.total > 0) u.done.toFloat() / u.total else null, Modifier.padding(end = 6.dp), stringResource(R.string.update_downloading, 0))
                is UpdateState.Installing -> Gauge(null, Modifier.padding(end = 6.dp))
                else -> {}
            }
        }
    }
}

/** The line and button of the "About" panel in Settings. */
@Composable
fun UpdateStatus(vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    val u = vm.update
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val line = when (u) {
            is UpdateState.Checking -> stringResource(R.string.update_checking)
            is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
            is UpdateState.NoneYet -> stringResource(R.string.update_none_yet)
            is UpdateState.Available -> stringResource(R.string.update_available, u.info.versionName)
            is UpdateState.Downloading -> stringResource(R.string.update_downloading, if (u.total > 0) (u.done * 100 / u.total).toInt() else 0)
            is UpdateState.Installing -> stringResource(R.string.update_installing)
            is UpdateState.Failed -> stringResource(R.string.update_failed, u.message)
            UpdateState.Idle -> ""
        }
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = if (u is UpdateState.Failed) cs.error else cs.onSurfaceVariant)
        if (u is UpdateState.Available) {
            PrimaryButton(onClick = { vm.installUpdate() }) { Text(stringResource(R.string.update_now)) }
        } else {
            TextAction(
                onClick = { vm.checkUpdate(manual = true) },
                enabled = u !is UpdateState.Checking && u !is UpdateState.Downloading && u !is UpdateState.Installing,
            ) { Text(stringResource(R.string.update_check)) }
        }
    }
}
