package dev.marck.jsyncdrive.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.NodeStatus
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.kit.PrimaryButton
import dev.marck.jsyncdrive.ui.kit.TextAction
import dev.marck.jsyncdrive.ui.kit.LockBadge
import dev.marck.jsyncdrive.ui.kit.Panel
import dev.marck.jsyncdrive.ui.kit.SectionHeading
import dev.marck.jsyncdrive.ui.kit.StatusPlate
import dev.marck.jsyncdrive.ui.kit.SecondaryButton
import dev.marck.jsyncdrive.ui.kit.ScreenTitle

/** Account and connection on top (the proof), then the two switches, then the encrypted folders, then leaving. */
@Composable
fun SettingsScreen(vm: AppViewModel, status: NodeStatus) {
    var hub by rememberSaveable(status.hub) { mutableStateOf(status.hub) }
    var server by rememberSaveable(status.server) { mutableStateOf(status.server) }
    var keep by remember { mutableStateOf(vm.prefs.keepConnected) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importCertificate(uri) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.nav_settings))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusPlate(status, onClick = {})
                Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Fact(stringResource(R.string.settings_account), status.email)
                        Fact(stringResource(R.string.settings_this_device), status.device.ifEmpty { "?" })
                        Fact(stringResource(R.string.settings_device_id), status.machineId)
                    }
                }

                SectionHeading(stringResource(R.string.settings_connection))
                Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppField(server, { server = it }, stringResource(R.string.signin_field_drive), null, KeyboardType.Uri, ImeAction.Next)
                        AppField(hub, { hub = it }, stringResource(R.string.signin_field_hub), null, KeyboardType.Uri, ImeAction.Done)
                        PrimaryButton(
                            onClick = { vm.applySettings(hub = hub, server = server) },
                            enabled = hub != status.hub || server != status.server,
                        ) { Text(stringResource(R.string.settings_apply)) }
                        SecondaryButton(onClick = { certPicker.launch(arrayOf("*/*")) }) {
                            Text(stringResource(R.string.settings_trust_ca, status.extraCas))
                        }
                    }
                }

                SectionHeading(stringResource(R.string.settings_behaviour))
                Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SettingSwitch(stringResource(R.string.settings_readonly), stringResource(R.string.settings_readonly_body), status.readOnly) {
                            vm.applySettings(readOnly = it)
                        }
                        SettingSwitch(stringResource(R.string.settings_background), stringResource(R.string.settings_background_body), keep) {
                            keep = it; vm.setKeepConnected(it)
                        }
                    }
                }

                if (status.protectedFolders.isNotEmpty()) {
                    SectionHeading(stringResource(R.string.settings_encrypted))
                    Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
                        Column {
                            status.protectedFolders.forEach { f ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LockBadge(32.dp, open = f.open)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(f.path, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            stringResource(if (f.open) R.string.settings_folder_open else R.string.settings_folder_locked),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (f.open) TextAction(onClick = { vm.lock(f.path) }) { Text(stringResource(R.string.lock_now)) }
                                }
                            }
                        }
                    }
                }

                SectionHeading(stringResource(R.string.settings_about))
                Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        Fact(
                            stringResource(R.string.settings_version),
                            "${dev.marck.jsyncdrive.update.Updater.installedName(ctx)} (${dev.marck.jsyncdrive.update.Updater.installedCode(ctx)})",
                        )
                        UpdateStatus(vm)
                    }
                }

                SectionHeading(stringResource(R.string.settings_leave))
                Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_signout_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextAction(onClick = { confirmSignOut = true }, danger = true) { Text(stringResource(R.string.settings_signout)) }
                    }
                }
            }
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            stringResource(R.string.settings_signout_title), stringResource(R.string.settings_signout_body), stringResource(R.string.settings_signout), danger = true,
            onDismiss = { confirmSignOut = false },
        ) { confirmSignOut = false; vm.signOut() }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest, uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}
