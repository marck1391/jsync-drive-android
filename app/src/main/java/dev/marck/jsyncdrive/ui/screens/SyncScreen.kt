package dev.marck.jsyncdrive.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.marck.jsyncdrive.sync.SyncPair
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.kit.CellState
import dev.marck.jsyncdrive.ui.kit.EmptyIllustration
import dev.marck.jsyncdrive.ui.kit.TextAction
import dev.marck.jsyncdrive.ui.kit.Panel
import dev.marck.jsyncdrive.ui.kit.StateCell
import dev.marck.jsyncdrive.ui.kit.SecondaryButton
import dev.marck.jsyncdrive.ui.kit.ScreenTitle
import dev.marck.jsyncdrive.ui.theme.AppShapes

/** Folders of this phone kept equal to folders of the Drive: one plate per pair, with its state on the left. */
@Composable
fun SyncScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val pairs by vm.folderSync.pairs.collectAsState()
    val statuses by vm.folderSync.status.collectAsState()
    var pickedTree by remember { mutableStateOf<Uri?>(null) }
    var removing by remember { mutableStateOf<SyncPair?>(null) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            pickedTree = uri
        }
    }
    val phoneFolder = stringResource(R.string.sync_phone_folder)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.nav_sync))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(340.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.sync_explainer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pairs.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EmptyIllustration(88.dp)
                            Text(stringResource(R.string.sync_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                            Text(stringResource(R.string.sync_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
                items(pairs, key = { it.id }) { p ->
                    val st = statuses[p.id]
                    val cell = when {
                        !p.enabled -> CellState.Dashed
                        st?.error == true -> CellState.Struck
                        st == null || st.running -> CellState.Hollow
                        else -> CellState.Solid
                    }
                    val line = when {
                        !p.enabled -> stringResource(R.string.sync_paused)
                        st == null -> stringResource(R.string.sync_waiting)
                        st.running -> st.message
                        st.lastRun > 0 -> st.message + "  ·  " + DateUtils.getRelativeTimeSpanString(st.lastRun, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
                        else -> st.message
                    }
                    val drive = if (p.driveFolder.isEmpty()) stringResource(R.string.files_root) else p.driveFolder
                    Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                StateCell(cell, line, Modifier.padding(top = 4.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(p.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.sync_pair, drive), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Switch(
                                    p.enabled, { vm.folderSync.setEnabled(p.id, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest, uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                                    ),
                                )
                            }
                            Text(
                                line, style = MaterialTheme.typography.bodyMedium,
                                color = if (st?.error == true && p.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(onClick = { vm.folderSync.syncNow(p.id) }, enabled = p.enabled) { Text(stringResource(R.string.sync_now)) }
                                TextAction(onClick = { removing = p }, danger = true) { Text(stringResource(R.string.sync_stop)) }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
            ExtendedFloatingActionButton(
                onClick = { treePicker.launch(null) },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = AppShapes.pill, elevation = FloatingActionButtonDefaults.elevation(2.dp, 2.dp, 2.dp, 2.dp),
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.sync_add)) },
            )
        }
    }

    pickedTree?.let { tree ->
        DriveFolderPicker(vm, onDismiss = { pickedTree = null }) { folder ->
            val label = runCatching {
                DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifEmpty { phoneFolder }
            }.getOrDefault(phoneFolder)
            vm.folderSync.add(tree, label, folder)
            pickedTree = null
        }
    }
    removing?.let { p ->
        ConfirmDialog(
            stringResource(R.string.sync_stop_title, p.label), stringResource(R.string.sync_stop_body), stringResource(R.string.sync_stop_confirm), danger = false,
            onDismiss = { removing = null },
        ) { vm.folderSync.remove(p.id); removing = null }
    }
}
