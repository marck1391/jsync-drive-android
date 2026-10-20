package dev.marck.jsyncdrive.ui.screens

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.Entry
import dev.marck.jsyncdrive.node.NodeApi
import dev.marck.jsyncdrive.node.NodeStatus
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.kit.CellState
import dev.marck.jsyncdrive.ui.kit.ConnectionBanner
import dev.marck.jsyncdrive.ui.kit.Crumb
import dev.marck.jsyncdrive.ui.kit.DoorKind
import dev.marck.jsyncdrive.ui.kit.EmptyIllustration
import dev.marck.jsyncdrive.ui.kit.FileCard
import dev.marck.jsyncdrive.ui.kit.FileRow
import dev.marck.jsyncdrive.ui.kit.FolderTile
import dev.marck.jsyncdrive.ui.kit.Gauge
import dev.marck.jsyncdrive.ui.kit.LockBadge
import dev.marck.jsyncdrive.ui.kit.Panel
import dev.marck.jsyncdrive.ui.kit.PathBar
import dev.marck.jsyncdrive.ui.kit.PrimaryButton
import dev.marck.jsyncdrive.ui.kit.parseHex
import dev.marck.jsyncdrive.ui.kit.SectionHeading
import dev.marck.jsyncdrive.ui.kit.StateCell
import dev.marck.jsyncdrive.ui.kit.TextAction
import dev.marck.jsyncdrive.ui.Thumbnails
import dev.marck.jsyncdrive.ui.rememberThumbnail
import dev.marck.jsyncdrive.ui.theme.AppShapes
import java.text.DateFormat
import java.util.Date

/**
 * Your Drive as folders and files: a search bar on top, the path under it, folders as tiles and files as previews
 * (the picture itself when it is one). An encrypted folder shows a lock and opens with its password.
 */
@Composable
fun FilesScreen(vm: AppViewModel, status: NodeStatus, onOpenSettings: () -> Unit, wide: Boolean) {
    val ctx = LocalContext.current
    var newFolder by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    var unlocking by remember { mutableStateOf<String?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Pair<List<Entry>, Int>?>(null) }
    var coloring by remember { mutableStateOf<Entry?>(null) }
    var sharingFolder by remember { mutableStateOf<Entry?>(null) }
    var grid by remember { mutableStateOf(vm.prefs.gridView) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris)
    }
    BackHandler(enabled = vm.searching) { vm.stopSearch() }
    BackHandler(enabled = !vm.searching && vm.path.isNotEmpty()) { vm.up() }

    val rootLabel = stringResource(R.string.files_root)
    val crumbs = remember(vm.path, rootLabel) {
        buildList {
            add(Crumb(rootLabel, ""))
            var acc = ""
            vm.path.split('/').filter { it.isNotEmpty() }.forEach { part -> acc = NodeApi.join(acc, part); add(Crumb(part, acc)) }
        }
    }
    val canChange = status.online && status.readWrite && vm.lockedFolder == null && !vm.searching

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SearchBar(vm, status, onOpenSettings)
            ConnectionBanner(status, onOpenSettings, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            UpdateBanner(vm, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (!vm.searching) {
                Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PathBar(crumbs, onCrumb = { vm.open(it) }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { grid = !grid; vm.prefs.gridView = grid }) {
                        Icon(
                            if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                            contentDescription = stringResource(if (grid) R.string.view_list else R.string.view_grid),
                        )
                    }
                    IconButton(onClick = { vm.load() }) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh)) }
                }
            }
            val locked = vm.lockedFolder
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    vm.searching -> SearchResults(vm, ctx) { e ->
                        when {
                            e.dir && e.locked -> { vm.stopSearch(); unlocking = e.path }
                            e.dir -> { vm.stopSearch(); vm.open(e.path) }
                            Thumbnails.canThumb(e) -> viewer = listOf(e) to 0
                            else -> vm.openExternally(e)
                        }
                    }
                    locked != null -> LockedBlock(locked) { unlocking = locked }
                    vm.entries.isEmpty() && vm.loading -> Centered { Gauge(null, Modifier.size(width = 160.dp, height = 4.dp), stringResource(R.string.loading)) }
                    vm.error != null -> ErrorBlock(vm.error ?: "") { vm.load() }
                    vm.entries.isEmpty() && status.online -> EmptyBlock(canChange)
                    vm.entries.isEmpty() -> {}
                    else -> Contents(
                        vm, status, wide, grid, ctx,
                        onOpen = { e ->
                            when {
                                e.dir && e.locked -> unlocking = e.path
                                e.dir -> vm.open(e.path)
                                Thumbnails.canThumb(e) -> {
                                    val pics = vm.entries.filter { Thumbnails.canThumb(it) }
                                    viewer = pics to pics.indexOfFirst { it.path == e.path }.coerceAtLeast(0)
                                }
                                else -> vm.openExternally(e)
                            }
                        },
                        menuFor = menuFor, onMenu = { menuFor = it },
                        act = ItemActions(
                            rename = { renaming = it; menuFor = null },
                            delete = { deleting = it; menuFor = null },
                            lock = { vm.lock(it.path); menuFor = null },
                            share = { vm.shareExternally(it); menuFor = null },
                            download = { vm.download(it); menuFor = null },
                            pin = { vm.togglePin(it.path); menuFor = null },
                            color = { coloring = it; menuFor = null },
                            shareFolder = { sharingFolder = it; menuFor = null },
                        ),
                    )
                }
            }
            if (vm.transfers.isNotEmpty()) UploadShelf(vm)
        }

        if (canChange) {
            Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { addOpen = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = AppShapes.card, elevation = FloatingActionButtonDefaults.elevation(3.dp, 3.dp, 3.dp, 3.dp),
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add)) },
                )
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.upload_files)) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, null) },
                        onClick = { addOpen = false; picker.launch(arrayOf("*/*")) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.new_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                        onClick = { addOpen = false; newFolder = true },
                    )
                }
            }
        }
    }

    viewer?.let { (pics, at) -> ImageViewer(vm, pics, at) { viewer = null } }
    coloring?.let { f ->
        ColorDialog(vm.colors[f.path], onDismiss = { coloring = null }) { hex -> vm.setColor(f.path, hex); coloring = null }
    }
    sharingFolder?.let { f -> ShareFolderDialog(vm, f.path) { sharingFolder = null } }
    if (newFolder) NameDialog(stringResource(R.string.new_folder), stringResource(R.string.name), "", stringResource(R.string.create), { newFolder = false }) { vm.newFolder(it); newFolder = false }
    renaming?.let { e -> NameDialog(stringResource(R.string.rename), stringResource(R.string.name), e.name, stringResource(R.string.rename), { renaming = null }) { vm.rename(e, it); renaming = null } }
    deleting?.let { e ->
        ConfirmDialog(
            stringResource(R.string.delete_title, e.name), stringResource(R.string.delete_body), stringResource(R.string.delete), danger = true,
            onDismiss = { deleting = null },
        ) { vm.delete(e); deleting = null }
    }
    unlocking?.let { folder ->
        UnlockDialog(folder, onDismiss = { unlocking = null }) { pw, minutes, done ->
            vm.unlock(folder, pw, minutes) { err ->
                done(err)
                if (err == null) { unlocking = null; vm.stopSearch(); vm.open(folder) }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// --- the search bar (and the way to Settings) -----------------------------------------------------------------

@Composable
private fun SearchBar(vm: AppViewModel, status: NodeStatus, onOpenSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.searching) { if (vm.searching) focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Panel(
            Modifier.weight(1f).height(52.dp), shape = AppShapes.pill, color = cs.surfaceContainerHigh,
            onClick = if (vm.searching || !status.online) null else ({ vm.startSearch() }),
            onClickLabel = stringResource(R.string.search),
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (vm.searching) {
                    IconButton(onClick = { vm.stopSearch() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.close_search)) }
                    TextField(
                        value = vm.query, onValueChange = { vm.search(it) },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text(stringResource(R.string.search_hint), color = cs.onSurfaceVariant) },
                        singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search(vm.query) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = cs.primary,
                        ),
                    )
                    if (vm.query.isNotEmpty()) IconButton(onClick = { vm.search("") }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear)) }
                } else {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 12.dp))
                    Text(stringResource(R.string.search_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!vm.searching) {
            val initial = (status.email.firstOrNull() ?: status.device.firstOrNull() ?: 'J').uppercaseChar().toString()
            Box(
                Modifier.padding(start = 12.dp).size(44.dp).clip(CircleShape).background(cs.primaryContainer)
                    .clickable(onClickLabel = stringResource(R.string.open_settings)) { onOpenSettings() },
                contentAlignment = Alignment.Center,
            ) {
                Text(initial, style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
            }
        }
    }
}

// --- contents -------------------------------------------------------------------------------------------------

@Composable
private fun Contents(
    vm: AppViewModel,
    status: NodeStatus,
    wide: Boolean,
    grid: Boolean,
    ctx: Context,
    onOpen: (Entry) -> Unit,
    menuFor: Entry?,
    onMenu: (Entry?) -> Unit,
    act: ItemActions,
) {
    val inProtected = status.protectedFolders.any { vm.path == it.path || vm.path.startsWith(it.path + "/") }
    val entries = vm.entries
    val pins = vm.pins
    val folders = remember(entries, pins) { entries.filter { it.dir }.sortedByDescending { it.path in pins } }
    val files = remember(entries) { entries.filter { !it.dir } }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val canChange = status.readWrite
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) 200.dp else 156.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (folders.isNotEmpty()) {
            full("h-folders") { SectionHeading(stringResource(R.string.files_boxes), top = 0.dp) }
            items(folders, key = { "d:" + it.path }, contentType = { "folder" }) { e ->
                val kind = when {
                    e.protected && e.locked -> DoorKind.Locked
                    e.protected -> DoorKind.Open
                    else -> DoorKind.Plain
                }
                val desc = when (kind) {
                    DoorKind.Locked -> stringResource(R.string.door_desc_locked, e.name)
                    DoorKind.Open -> stringResource(R.string.door_desc_open, e.name)
                    DoorKind.Plain -> stringResource(R.string.door_desc_plain, e.name)
                }
                val hasMenu = !e.locked
                Box {
                    FolderTile(
                        e.name, kind, desc, onClick = { onOpen(e) },
                        onMore = if (hasMenu && wide) ({ onMenu(e) }) else null, moreLabel = stringResource(R.string.more_actions, e.name),
                        onLongClick = if (hasMenu) ({ onMenu(e) }) else null,
                        color = vm.colors[e.path]?.let(::parseHex), pinned = e.path in vm.pins,
                    )
                    EntryMenu(e, menuFor, onMenu, act, canChange, inProtected, e.path in vm.pins)
                }
            }
        }
        if (files.isNotEmpty()) {
            full("h-files") { SectionHeading(stringResource(R.string.files_contents), top = 14.dp) }
            if (grid) {
                items(files, key = { "f:" + it.path }, contentType = { "card" }) { e ->
                    Box {
                        FileCard(
                            e.name, remember(e.size, e.mtime) { fileMeta(ctx, dateFormat, e) }, rememberThumbnail(vm, e), onClick = { onOpen(e) },
                            onMore = { onMenu(e) }, moreLabel = stringResource(R.string.more_actions, e.name),
                            onLongClick = { onMenu(e) },
                        )
                        EntryMenu(e, menuFor, onMenu, act, canChange, inProtected, e.path in vm.pins)
                    }
                }
            } else {
                items(files, key = { "f:" + it.path }, contentType = { "row" }, span = { GridItemSpan(if (wide) maxOf(1, maxLineSpan / 2) else maxLineSpan) }) { e ->
                    Box {
                        FileRow(
                            e.name, remember(e.size, e.mtime) { fileMeta(ctx, dateFormat, e) }, rememberThumbnail(vm, e), onClick = { onOpen(e) },
                            onMore = { onMenu(e) }, moreLabel = stringResource(R.string.more_actions, e.name),
                            onLongClick = { onMenu(e) },
                        )
                        EntryMenu(e, menuFor, onMenu, act, canChange, inProtected, e.path in vm.pins)
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }, key = key) { content() }
}

/** What the item menus can do; every action receives the item it was opened for. */
private class ItemActions(
    val rename: (Entry) -> Unit,
    val delete: (Entry) -> Unit,
    val lock: (Entry) -> Unit,
    val share: (Entry) -> Unit,
    val download: (Entry) -> Unit,
    val pin: (Entry) -> Unit,
    val color: (Entry) -> Unit,
    val shareFolder: (Entry) -> Unit,
)

/**
 * The same options as the web app: pin, share, folder colour, rename and delete for folders (colour, pin and
 * sharing are not offered inside an encrypted folder); share, download, rename and delete for files.
 */
@Composable
private fun EntryMenu(
    e: Entry,
    menuFor: Entry?,
    onMenu: (Entry?) -> Unit,
    act: ItemActions,
    canChange: Boolean,
    inProtected: Boolean,
    pinned: Boolean,
) {
    if (menuFor?.path != e.path) return
    DropdownMenu(expanded = true, onDismissRequest = { onMenu(null) }) {
        if (e.dir && !inProtected && !e.protected) {
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.unpin else R.string.pin)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = { act.pin(e) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_with_people)) },
                leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) }, onClick = { act.shareFolder(e) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folder_color)) },
                leadingIcon = { Icon(Icons.Outlined.Palette, null) }, onClick = { act.color(e) },
            )
        }
        if (!e.dir) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = { act.share(e) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download)) }, leadingIcon = { Icon(Icons.Outlined.Download, null) },
                onClick = { act.download(e) },
            )
        }
        if (e.protected && !e.locked) {
            DropdownMenuItem(text = { Text(stringResource(R.string.lock_now)) }, onClick = { act.lock(e) })
        }
        if (canChange && !e.protected) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { act.rename(e) })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { act.delete(e) })
        }
    }
}

@Composable
private fun LockedBlock(folder: String, onUnlock: () -> Unit) {
    Centered {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LockBadge(88.dp)
            Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.locked_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            PrimaryButton(onClick = onUnlock) { Text(stringResource(R.string.unlock_named, folder.substringAfterLast('/'))) }
        }
    }
}

@Composable
private fun EmptyBlock(canAdd: Boolean) {
    Centered {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EmptyIllustration(112.dp)
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                stringResource(if (canAdd) R.string.empty_body_add else R.string.empty_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Centered {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StateCell(CellState.Struck, stringResource(R.string.error_generic), size = 40.dp)
            Text(stringResource(R.string.error_load), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            PrimaryButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        }
    }
}

// --- search results -------------------------------------------------------------------------------------------

@Composable
private fun SearchResults(vm: AppViewModel, ctx: Context, onOpen: (Entry) -> Unit) {
    when {
        vm.query.isBlank() -> Centered {
            Text(stringResource(R.string.search_prompt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp), textAlign = TextAlign.Center)
        }
        vm.results.isEmpty() -> Centered {
            Text(stringResource(R.string.search_none, vm.query), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp), textAlign = TextAlign.Center)
        }
        else -> LazyVerticalGrid(columns = GridCells.Fixed(1), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            items(vm.results, key = { it.path }) { e ->
                val where = NodeApi.parentOf(e.path).ifEmpty { stringResource(R.string.files_root) }
                FileRow(
                    e.name, if (e.dir) where else where + "  ·  " + Formatter.formatShortFileSize(ctx, e.size), rememberThumbnail(vm, e),
                    onClick = { onOpen(e) }, onMore = null, moreLabel = "", dir = e.dir,
                )
            }
        }
    }
}

// --- uploads --------------------------------------------------------------------------------------------------

@Composable
private fun UploadShelf(vm: AppViewModel) {
    Panel(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.uploads), style = MaterialTheme.typography.titleMedium)
                TextAction(onClick = { vm.clearTransfers() }) { Text(stringResource(R.string.clear)) }
            }
            vm.transfers.takeLast(4).forEach { t ->
                val state = when {
                    t.error != null -> CellState.Struck
                    t.done -> CellState.Solid
                    else -> CellState.Hollow
                }
                val label = when {
                    t.error != null -> stringResource(R.string.upload_failed)
                    t.done -> stringResource(R.string.upload_done)
                    else -> stringResource(R.string.upload_working)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StateCell(state, label)
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.error != null) Text(t.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                    }
                }
            }
        }
    }
}

// --- helpers --------------------------------------------------------------------------------------------------

private fun dateOf(df: DateFormat, e: Entry): String = if (e.mtime > 0) df.format(Date(e.mtime)) else ""

private fun fileMeta(ctx: Context, df: DateFormat, e: Entry): String =
    listOf(Formatter.formatShortFileSize(ctx, e.size), dateOf(df, e)).filter { it.isNotEmpty() }.joinToString("  ·  ")
