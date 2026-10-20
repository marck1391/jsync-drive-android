package dev.marck.jsyncdrive.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.ui.kit.PrimaryButton
import dev.marck.jsyncdrive.ui.kit.CellState
import dev.marck.jsyncdrive.ui.kit.Crumb
import dev.marck.jsyncdrive.ui.kit.EmptyIllustration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import dev.marck.jsyncdrive.ui.kit.StateCell
import dev.marck.jsyncdrive.ui.kit.PathBar
import dev.marck.jsyncdrive.ui.kit.pressable
import dev.marck.jsyncdrive.ui.screens.AppRoot
import dev.marck.jsyncdrive.ui.screens.NavRequest
import dev.marck.jsyncdrive.ui.screens.Tab
import dev.marck.jsyncdrive.ui.screens.UnlockDialog
import dev.marck.jsyncdrive.ui.theme.JsyncTheme
import dev.marck.jsyncdrive.ui.theme.AppShapes
import dev.marck.jsyncdrive.node.NodeApi

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var nav by mutableStateOf(NavRequest())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        readNavigation(intent)
        setContent { JsyncTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot(vm, nav) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNavigation(intent)
    }

    /** A notification asks for the Devices screen, and for the window of one transfer. */
    private fun readNavigation(intent: Intent) {
        val devices = intent.getStringExtra("screen") == "devices"
        val transfer = intent.getStringExtra("transfer")
        if (devices || transfer != null) nav = NavRequest(Tab.DEVICES, transfer, System.nanoTime())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

/** Opened by a file manager that tried to list an encrypted folder: asks for the vault password. */
class UnlockActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val folder = intent.getStringExtra(EXTRA_FOLDER).orEmpty()
        setContent {
            JsyncTheme {
                if (folder.isEmpty()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Text(stringResource(R.string.unlock_nothing), Modifier.padding(24.dp))
                    }
                } else {
                    UnlockDialog(folder, onDismiss = { finish() }) { pw, minutes, done ->
                        vm.unlock(folder, pw, minutes) { err -> done(err); if (err == null) finish() }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
    }
}

/** "Share → jsync Drive": upload what was shared into a Drive folder, or send it straight to another device. */
class ShareActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        setContent {
            JsyncTheme {
                val status by vm.status.collectAsState()
                var uploading by remember { mutableStateOf(false) }
                var toDevice by remember { mutableStateOf(false) }
                LaunchedEffect(toDevice) { if (toDevice) vm.loadDevices() }
                val root = stringResource(R.string.files_root)
                val crumbs = remember(vm.path, root, toDevice) {
                    if (toDevice) listOf(Crumb("", ""))
                    else buildList {
                        add(Crumb(root, ""))
                        var acc = ""
                        vm.path.split('/').filter { it.isNotEmpty() }.forEach { part -> acc = NodeApi.join(acc, part); add(Crumb(part, acc)) }
                    }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                            PathBar(
                                if (toDevice) listOf(Crumb(stringResource(R.string.share_to_device_title), "")) else crumbs,
                                onCrumb = { vm.open(it) }, statusBar = true,
                            )
                            Text(
                                pluralStringResource(R.plurals.share_count, uris.size, uris.size),
                                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val chip = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                FilterChip(!toDevice, { toDevice = false }, { Text(stringResource(R.string.share_to_drive)) }, shape = AppShapes.field, colors = chip)
                                FilterChip(toDevice, { toDevice = true }, { Text(stringResource(R.string.share_to_device)) }, shape = AppShapes.field, colors = chip)
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when {
                                    !status.loggedIn -> Note(stringResource(R.string.share_sign_in_first))
                                    !status.online -> Note(stringResource(R.string.status_connecting))
                                    toDevice -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                                        if (vm.devices.isEmpty()) item { Note(vm.devicesError ?: stringResource(R.string.devices_empty_title)) }
                                        items(vm.devices, key = { it.id }) { d ->
                                            val state = stringResource(if (d.online) R.string.device_online else R.string.device_offline)
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .pressable({ if (d.online && uris.isNotEmpty()) { vm.sendTo(d, uris); finish() } }, stringResource(R.string.send))
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                StateCell(if (d.online) CellState.Solid else CellState.Dashed, state)
                                                Column(Modifier.padding(start = 12.dp)) {
                                                    Text(d.name, style = MaterialTheme.typography.titleMedium, color = if (d.online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                                                    Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                    }
                                    !status.readWrite -> Note(stringResource(R.string.share_read_only))
                                    else -> {
                                        val folders = vm.entries.filter { it.dir && !it.protected }
                                        if (folders.isEmpty()) Note(stringResource(R.string.share_no_subfolders))
                                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                                            items(folders, key = { it.path }) { e ->
                                                Row(
                                                    Modifier.fillMaxWidth().pressable({ vm.open(e.path) }, e.name).padding(horizontal = 16.dp, vertical = 14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Icon(Icons.Filled.Folder, null, Modifier.padding(end = 14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                                                }
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            }
                                        }
                                    }
                                }
                            }
                            if (!toDevice) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                                    PrimaryButton(
                                        enabled = status.online && status.readWrite && !uploading && uris.isNotEmpty(),
                                        onClick = { uploading = true; vm.upload(uris, vm.path) { finish() } },
                                    ) {
                                        Text(
                                            if (uploading) stringResource(R.string.share_uploading)
                                            else stringResource(R.string.share_upload_here, vm.path.substringAfterLast('/').ifEmpty { root }),
                                        )
                                    }
                                }
                            } else {
                                Box(Modifier.navigationBarsPadding())
                            }
                        }
                    }
                }
            }
        }
        vm.open("")
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}

@Composable
private fun Note(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptyIllustration(72.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
