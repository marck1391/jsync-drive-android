package dev.marck.jsyncdrive.ui.screens

import android.net.Uri
import android.os.Environment
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.ReceivedFile
import dev.marck.jsyncdrive.node.Transfer
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.Thumbnails
import dev.marck.jsyncdrive.ui.kit.CellState
import dev.marck.jsyncdrive.ui.kit.FileTypeIcon
import dev.marck.jsyncdrive.ui.kit.Gauge
import dev.marck.jsyncdrive.ui.kit.Panel
import dev.marck.jsyncdrive.ui.kit.PrimaryButton
import dev.marck.jsyncdrive.ui.kit.SecondaryButton
import dev.marck.jsyncdrive.ui.kit.StateCell
import dev.marck.jsyncdrive.ui.kit.TextAction
import dev.marck.jsyncdrive.ui.theme.AppShapes

/**
 * The window of one transfer, which follows it from the request to the file: while it waits for an answer it says
 * who sends what and offers Accept / Always / Decline; while it runs, its progress; once it arrived, the picture
 * itself (or the icon of its kind) with Open, Share and Delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferDialog(vm: AppViewModel, id: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val transfers by vm.p2pTransfers.collectAsState()
    val received by vm.received.collectAsState()
    val t = transfers.firstOrNull { it.id == id }
    val r = received.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }

    // What the transfer was, and nothing of it is left (cleared elsewhere): nothing to show.
    if (t == null && r == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val name = r?.name ?: t?.name.orEmpty()
    val size = r?.size ?: t?.size ?: 0L
    val peer = r?.from ?: t?.peerName.orEmpty()
    val mime = r?.mime ?: t?.mime.orEmpty()
    val sending = t?.send == true

    // The picture, once it is on this phone.
    val picture = produceState<ImageBitmap?>(null, r?.uri) {
        value = if (r != null && r.mime.startsWith("image/")) Thumbnails.decodeUri(ctx, Uri.parse(r.uri), 1280)?.asImageBitmap() else null
    }.value

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Panel(
            Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth(),
            shape = AppShapes.card, color = cs.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // header: who and what
                Text(
                    when {
                        r != null -> stringResource(R.string.tr_received_from, peer)
                        sending -> stringResource(R.string.transfer_to, peer)
                        t?.status == "incoming" -> stringResource(R.string.notif_wants_to_send, peer)
                        else -> stringResource(R.string.transfer_from, peer)
                    },
                    style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant,
                )

                // preview: the picture when there is one, else the icon of its kind
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picture != null) {
                        Image(picture, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
                    } else {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FileTypeIcon(name, 72.dp)
                            if (t?.status == "incoming" && mime.startsWith("image/")) {
                                Text(stringResource(R.string.tr_preview_after), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                Column {
                    Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Formatter.formatShortFileSize(ctx, size), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }

                // state
                when {
                    r != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StateCell(CellState.Solid, stringResource(R.string.transfer_done))
                        Text(stringResource(R.string.tr_saved_in, savedFolder(r)), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    t != null && t.status == "active" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Gauge(if (t.size > 0) (t.bytes.toFloat() / t.size).coerceIn(0f, 1f) else null, description = stringResource(R.string.transfer_working))
                        val speed = if (t.speed > 0) "  ·  " + Formatter.formatShortFileSize(ctx, t.speed.toLong()) + "/s" else ""
                        Text(
                            stringResource(R.string.transfer_progress, Formatter.formatShortFileSize(ctx, t.bytes), Formatter.formatShortFileSize(ctx, t.size)) + speed,
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                    t != null && (t.status == "waiting" || t.status == "connecting") -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Gauge(null, description = stringResource(R.string.transfer_working))
                        Text(t.note.ifEmpty { stringResource(R.string.transfer_connecting) }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    t != null && t.status == "done" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StateCell(if (sending) CellState.Solid else CellState.Hollow, stringResource(if (sending) R.string.transfer_done else R.string.tr_saving))
                        Text(
                            if (sending) stringResource(R.string.transfer_sent, Formatter.formatShortFileSize(ctx, t.size)) else stringResource(R.string.tr_saving),
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        )
                    }
                    t != null && t.status != "incoming" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StateCell(if (t.status == "error") CellState.Struck else CellState.Dashed, stringResource(R.string.transfer_failed))
                        Text(
                            when (t.status) {
                                "declined" -> stringResource(R.string.transfer_declined)
                                "cancelled" -> t.note.ifEmpty { stringResource(R.string.transfer_cancelled) }
                                else -> t.error.ifEmpty { stringResource(R.string.transfer_failed) }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (t.status == "error") cs.error else cs.onSurfaceVariant,
                        )
                    }
                }

                // what can be done
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        r != null -> {
                            PrimaryButton(onClick = { vm.openReceived(r) }) { Text(stringResource(R.string.tr_open)) }
                            SecondaryButton(onClick = { vm.shareReceived(r) }) { Text(stringResource(R.string.share)) }
                            TextAction(onClick = { confirmDelete = true }, danger = true) { Text(stringResource(R.string.delete)) }
                        }
                        t != null && t.status == "incoming" -> {
                            PrimaryButton(onClick = { vm.p2p.accept(t.id) }) { Text(stringResource(R.string.accept)) }
                            SecondaryButton(onClick = { vm.p2p.accept(t.id, trust = true); vm.setTrusted(t.peerId, true) }) {
                                Text(stringResource(R.string.tr_always, peer), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextAction(onClick = { vm.p2p.decline(t.id); onDismiss() }, danger = true) { Text(stringResource(R.string.decline)) }
                        }
                        t != null && (t.status == "active" || t.status == "waiting" || t.status == "connecting") ->
                            TextAction(onClick = { vm.p2p.cancel(t.id); onDismiss() }, danger = true) { Text(stringResource(R.string.cancel)) }
                        t != null && (t.status != "done" || sending) ->
                            TextAction(onClick = { vm.p2p.dismiss(t.id); onDismiss() }) { Text(stringResource(R.string.clear)) }
                    }
                    TextAction(onClick = onDismiss) { Text(stringResource(R.string.tr_close)) }
                }
            }
        }
    }

    if (confirmDelete && r != null) {
        ConfirmDialog(
            stringResource(R.string.delete_title, r.name), stringResource(R.string.tr_delete_body), stringResource(R.string.delete), danger = true,
            onDismiss = { confirmDelete = false },
        ) { confirmDelete = false; vm.deleteReceived(r); onDismiss() }
    }
}

/** Where a received file was filed, as the user sees it. */
private fun savedFolder(f: ReceivedFile): String = when {
    f.mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
    f.mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
    f.mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
    else -> Environment.DIRECTORY_DOWNLOADS
} + "/jsync"
