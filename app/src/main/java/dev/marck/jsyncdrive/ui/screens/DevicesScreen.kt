package dev.marck.jsyncdrive.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.marck.jsyncdrive.node.ReceivedFile
import dev.marck.jsyncdrive.ui.Thumbnails
import dev.marck.jsyncdrive.ui.kit.FileTypeIcon
import dev.marck.jsyncdrive.ui.theme.AppShapes
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.Device
import dev.marck.jsyncdrive.node.Transfer
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.kit.PrimaryButton
import dev.marck.jsyncdrive.ui.kit.CellState
import dev.marck.jsyncdrive.ui.kit.EmptyIllustration
import dev.marck.jsyncdrive.ui.kit.Gauge
import dev.marck.jsyncdrive.ui.kit.TextAction
import dev.marck.jsyncdrive.ui.kit.Panel
import dev.marck.jsyncdrive.ui.kit.SectionHeading
import dev.marck.jsyncdrive.ui.kit.StateCell
import dev.marck.jsyncdrive.ui.kit.SecondaryButton
import dev.marck.jsyncdrive.ui.kit.ScreenTitle
import kotlinx.coroutines.delay

/** The account's other devices with a Send button each, and the transfers in flight. */
@Composable
fun DevicesScreen(vm: AppViewModel) {
    val transfers by vm.p2pTransfers.collectAsState()
    val received by vm.received.collectAsState()
    var target by remember { mutableStateOf<Device?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val d = target
        if (d != null && uris.isNotEmpty()) vm.sendTo(d, uris)
    }
    LaunchedEffect(Unit) {
        while (true) {
            vm.loadDevices()
            delay(8000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.nav_devices))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { NotificationsHint() }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.devices_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            vm.devicesError?.let { e ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StateCell(CellState.Struck, stringResource(R.string.error_generic))
                        Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (vm.devices.isEmpty() && vm.devicesError == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        EmptyIllustration(88.dp)
                        Text(stringResource(R.string.devices_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.devices_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            items(vm.devices, key = { it.id }) { d ->
                val isTrusted = d.id in vm.trusted
                Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val state = stringResource(if (d.online) R.string.device_online else R.string.device_offline)
                            StateCell(if (d.online) CellState.Solid else CellState.Dashed, state)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(d.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            PrimaryButton(onClick = { target = d; picker.launch(arrayOf("*/*")) }, enabled = d.online) { Text(stringResource(R.string.send)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.device_trust), Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                isTrusted, { vm.setTrusted(d.id, it) },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest, uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            )
                        }
                    }
                }
            }
            if (transfers.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading(stringResource(R.string.transfers)) }
                items(transfers.reversed(), key = { "t:" + it.id }) { t -> TransferPlate(vm, t) }
            }
            if (received.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeading(stringResource(R.string.tr_received_title)) }
                items(received.take(20), key = { "r:" + it.id }) { f -> ReceivedRow(vm, f) }
            }
        }
    }
}

@Composable
private fun TransferPlate(vm: AppViewModel, t: Transfer) {
    val ctx = LocalContext.current
    val size = Formatter.formatShortFileSize(ctx, t.size)
    val cell = when (t.status) {
        "done" -> CellState.Solid
        "active", "waiting", "connecting", "incoming" -> CellState.Hollow
        "declined" -> CellState.Dashed
        else -> CellState.Struck
    }
    val cellLabel = when (t.status) {
        "done" -> stringResource(R.string.transfer_done)
        "incoming" -> stringResource(R.string.transfer_incoming_short)
        "active", "waiting", "connecting" -> stringResource(R.string.transfer_working)
        "declined" -> stringResource(R.string.transfer_declined)
        else -> stringResource(R.string.transfer_failed)
    }
    Panel(Modifier.fillMaxWidth(), outlined = true, onClick = { vm.showTransfer(t.id) }, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateCell(cell, cellLabel)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(if (t.send) R.string.transfer_to else R.string.transfer_from, t.peerName),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (t.status) {
                "incoming" -> {
                    Text(stringResource(R.string.transfer_incoming, size), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(onClick = { vm.p2p.accept(t.id) }) { Text(stringResource(R.string.accept)) }
                        SecondaryButton(onClick = { vm.p2p.accept(t.id, trust = true) }) { Text(stringResource(R.string.accept_always)) }
                        TextAction(onClick = { vm.p2p.decline(t.id) }, danger = true) { Text(stringResource(R.string.decline)) }
                    }
                }
                "active" -> {
                    Gauge(if (t.size > 0) (t.bytes.toFloat() / t.size).coerceIn(0f, 1f) else null, description = cellLabel)
                    val speed = if (t.speed > 0) "  ·  " + Formatter.formatShortFileSize(ctx, t.speed.toLong()) + "/s" else ""
                    Text(
                        stringResource(R.string.transfer_progress, Formatter.formatShortFileSize(ctx, t.bytes), size) + speed + (if (t.route.isNotEmpty()) "  ·  ${t.route}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextAction(onClick = { vm.p2p.cancel(t.id) }) { Text(stringResource(R.string.cancel)) }
                }
                "waiting", "connecting" -> {
                    Gauge(null, description = cellLabel)
                    Text(t.note.ifEmpty { stringResource(R.string.transfer_connecting) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextAction(onClick = { vm.p2p.cancel(t.id) }) { Text(stringResource(R.string.cancel)) }
                }
                "done" -> {
                    Text(
                        if (t.send) stringResource(R.string.transfer_sent, size) else stringResource(R.string.transfer_received, size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextAction(onClick = { vm.p2p.dismiss(t.id) }) { Text(stringResource(R.string.clear)) }
                }
                else -> {
                    Text(
                        when (t.status) {
                            "declined" -> stringResource(R.string.transfer_declined)
                            "cancelled" -> t.note.ifEmpty { stringResource(R.string.transfer_cancelled) }
                            else -> t.error.ifEmpty { stringResource(R.string.transfer_failed) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (t.status == "declined" || t.status == "cancelled") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    TextAction(onClick = { vm.p2p.dismiss(t.id) }) { Text(stringResource(R.string.clear)) }
                }
            }
        }
    }
}

/** A file that came from another device: a small preview, its name and who sent it; tap for the window with the actions. */
@Composable
private fun ReceivedRow(vm: AppViewModel, f: ReceivedFile) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val thumb = produceState<ImageBitmap?>(null, f.uri) {
        value = if (f.mime.startsWith("image/")) Thumbnails.decodeUri(ctx, Uri.parse(f.uri), 160)?.asImageBitmap() else null
    }.value
    Panel(Modifier.fillMaxWidth(), outlined = true, onClick = { vm.showTransfer(f.id) }, contentPadding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else FileTypeIcon(f.name, 24.dp)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.transfer_from, f.from) + "  ·  " + DateUtils.getRelativeTimeSpanString(f.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Without notifications a request is only seen with the app open: say so, and take the user to the setting. */
@Composable
private fun NotificationsHint() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    if (enabled) return
    Panel(Modifier.fillMaxWidth(), shape = AppShapes.tile, color = MaterialTheme.colorScheme.secondaryContainer, contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.notif_enable), Modifier.weight(1f).padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextAction(onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text(stringResource(R.string.notif_turn_on)) }
        }
    }
}
