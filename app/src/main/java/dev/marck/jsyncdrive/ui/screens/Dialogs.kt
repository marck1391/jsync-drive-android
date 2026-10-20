package dev.marck.jsyncdrive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.marck.jsyncdrive.ui.kit.FolderPalette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.Entry
import dev.marck.jsyncdrive.node.NodeApi
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.kit.PrimaryButton
import dev.marck.jsyncdrive.ui.kit.TextAction
import dev.marck.jsyncdrive.ui.kit.LockBadge
import dev.marck.jsyncdrive.ui.kit.pressable
import dev.marck.jsyncdrive.ui.theme.AppShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A text field in the vault's grammar: a small recessed window with a brass focus line. */
@Composable
fun AppField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    keyboard: KeyboardType,
    ime: ImeAction,
    modifier: Modifier = Modifier.fillMaxWidth(),
    support: String? = null,
    password: Boolean = false,
    error: String? = null,
    onDone: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.outline) } },
        supportingText = (error ?: support)?.let { { Text(it) } },
        isError = error != null,
        singleLine = true,
        shape = AppShapes.field,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ime),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (visible) R.string.hide_password else R.string.show_password),
                    )
                }
            }
        } else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    )
}

@Composable
fun NameDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            AppField(
                text, { text = it }, label, null, KeyboardType.Text, ImeAction.Done,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                onDone = { if (text.isNotBlank() && '/' !in text) onOk(text.trim()) },
            )
        },
        confirmButton = { TextAction(onClick = { onOk(text.trim()) }, enabled = text.isNotBlank() && '/' !in text) { Text(confirm) } },
        dismissButton = { TextAction(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, danger: Boolean, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { TextAction(onClick = onOk, danger = danger) { Text(confirm) } },
        dismissButton = { TextAction(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Asks for the vault password. [onUnlock] gets the password, how long to keep the box open (minutes, 0 = until it is
 * locked) and a callback for an error to show under the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockDialog(folder: String, onDismiss: () -> Unit, onUnlock: (String, Int, (String?) -> Unit) -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf(30) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        if (password.isNotEmpty() && !busy) {
            busy = true
            onUnlock(password, minutes) { err -> busy = false; error = err }
        }
    }
    val options = listOf(30 to R.string.lock_after_30m, 240 to R.string.lock_after_4h, 0 to R.string.lock_after_never)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        icon = { LockBadge(44.dp) },
        title = { Text(stringResource(R.string.unlock_title, folder.substringAfterLast('/')), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.unlock_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppField(
                    password, { password = it; error = null }, stringResource(R.string.vault_password), null, KeyboardType.Password, ImeAction.Done,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus), password = true, error = error, onDone = submit,
                )
                Text(stringResource(R.string.lock_after), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (m, res) ->
                        SegmentedButton(
                            selected = minutes == m, onClick = { minutes = m },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size, AppShapes.field),
                        ) { Text(stringResource(res), maxLines = 1) }
                    }
                }
            }
        },
        confirmButton = { PrimaryButton(onClick = submit, enabled = password.isNotEmpty() && !busy) { Text(stringResource(if (busy) R.string.unlocking else R.string.unlock)) } },
        dismissButton = { TextAction(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Lets the user pick which Drive folder a phone folder is synced with. */
@Composable
fun DriveFolderPicker(vm: AppViewModel, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        error = null
        folders = emptyList()
        try {
            val list = withContext(Dispatchers.IO) { vm.host.ensureStarted().ls(path) }
            folders = list.filter { it.dir && !it.protected }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            error = e.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.pick_drive_folder), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path.isNotEmpty()) IconButton(onClick = { path = NodeApi.parentOf(path) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.up))
                    }
                    Text(
                        if (path.isEmpty()) stringResource(R.string.files_root) else path,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.heightIn(max = 280.dp)) {
                    when {
                        error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
                        folders.isEmpty() -> Text(stringResource(R.string.no_folders), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn {
                            items(folders, key = { it.path }) { f ->
                                Row(Modifier.fillMaxWidth().pressable({ path = f.path }).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(f.name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(onClick = { onPick(path) }) {
                Text(if (path.isEmpty()) stringResource(R.string.use_whole_drive) else stringResource(R.string.use_folder, path.substringAfterLast('/')))
            }
        },
        dismissButton = { TextAction(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Picks the colour of a folder: the Drive's nine colours, or the default. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ColorDialog(current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.folder_color), style = MaterialTheme.typography.headlineSmall) },
        text = {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Swatch(null, stringResource(R.string.color_default), current == null) { onPick("") }
                FolderPalette.forEach { c ->
                    Swatch(c.color, stringResource(c.name), current.equals(c.hex, ignoreCase = true)) { onPick(c.hex) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextAction(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color?, label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(color ?: cs.surfaceContainerHighest)
            .border(if (selected) 3.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, androidx.compose.foundation.shape.CircleShape)
            .semantics { contentDescription = label; role = Role.RadioButton; this.selected = selected }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) Icon(Icons.Outlined.Folder, null, tint = cs.onSurfaceVariant)
        else if (selected) Icon(Icons.Outlined.Check, null, tint = androidx.compose.ui.graphics.Color.White)
    }
}

/** Shares a folder with another person (by e-mail, as in the web app) and lists who already has it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareFolderDialog(vm: AppViewModel, folder: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var email by remember { mutableStateOf("") }
    var permission by remember { mutableStateOf("read") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(folder) { vm.loadShares() }
    val here = vm.shares.filter { it.path == folder }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cs.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.share_folder_title, folder.substringAfterLast('/')), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (here.isNotEmpty()) {
                    Text(stringResource(R.string.share_with), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    here.forEach { sh ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sh.email, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(if (sh.permission == "write") R.string.perm_edit else R.string.perm_view),
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.unshare(sh.id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.share_remove, sh.email))
                            }
                        }
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                }
                AppField(
                    email, { email = it; error = null }, stringResource(R.string.share_email), null, KeyboardType.Email, ImeAction.Done,
                    error = error,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("read" to R.string.perm_view, "write" to R.string.perm_edit).forEachIndexed { i, (v, res) ->
                        SegmentedButton(selected = permission == v, onClick = { permission = v }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text(stringResource(res), maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    busy = true
                    vm.share(folder, email, permission) { err -> busy = false; if (err == null) email = "" else error = err }
                },
                enabled = email.contains('@') && !busy,
            ) { Text(stringResource(R.string.share_action)) }
        },
        dismissButton = { TextAction(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
