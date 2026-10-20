package dev.marck.jsyncdrive.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.NodeStatus
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.kit.PrimaryButton
import dev.marck.jsyncdrive.ui.kit.TextAction
import dev.marck.jsyncdrive.ui.kit.Gauge
import dev.marck.jsyncdrive.ui.kit.Panel
import dev.marck.jsyncdrive.ui.kit.SecondaryButton
import dev.marck.jsyncdrive.ui.kit.AppMark
import dev.marck.jsyncdrive.ui.theme.KeyCode
import dev.marck.jsyncdrive.ui.theme.AppShapes

/** Before there is a login: three things to tell the app once, then a code to approve on another device. */
@Composable
fun SignInScreen(vm: AppViewModel, status: NodeStatus) {
    var server by rememberSaveable { mutableStateOf(status.server.ifEmpty { vm.prefs.lastServer }) }
    var hub by rememberSaveable { mutableStateOf(status.hub.ifEmpty { vm.prefs.lastHub }) }
    var device by rememberSaveable { mutableStateOf(status.device.ifEmpty { android.os.Build.MODEL ?: "Android" }) }
    val ctx = LocalContext.current
    val login = status.login
    val waiting = login != null && login.error.isEmpty() && status.state == "pending"
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importCertificate(uri) }

    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppMark(72.dp)
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.signin_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))

            if (waiting && login != null) {
                Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.signin_approve_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.signin_approve_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Panel(Modifier.fillMaxWidth(), shape = AppShapes.field, color = MaterialTheme.colorScheme.surfaceContainer, contentPadding = PaddingValues(vertical = 16.dp)) {
                            Text(
                                login.userCode, style = KeyCode, color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        PrimaryButton(
                            onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.uri))) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.signin_open_page)) }
                        Gauge(null, description = stringResource(R.string.signin_waiting))
                        Text(stringResource(R.string.signin_waiting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextAction(onClick = { vm.cancelSignIn() }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            } else {
                Panel(Modifier.fillMaxWidth(), outlined = true, contentPadding = PaddingValues(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppField(server, { server = it }, stringResource(R.string.signin_field_drive), "https://drive.example.com", KeyboardType.Uri, ImeAction.Next)
                        AppField(hub, { hub = it }, stringResource(R.string.signin_field_hub), "hub.example.com:7830", KeyboardType.Uri, ImeAction.Next, support = stringResource(R.string.signin_hint_hub))
                        AppField(device, { device = it }, stringResource(R.string.signin_field_device), null, KeyboardType.Text, ImeAction.Done)
                        if (login != null && login.error.isNotEmpty()) {
                            Text(login.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (server.startsWith("https")) {
                            SecondaryButton(onClick = { certPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (status.extraCas > 0) stringResource(R.string.signin_trust_ca_count, status.extraCas) else stringResource(R.string.signin_trust_ca))
                            }
                            Text(stringResource(R.string.signin_trust_ca_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PrimaryButton(
                            onClick = { vm.signIn(server, hub, device) },
                            enabled = server.isNotBlank() && hub.isNotBlank() && device.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.signin_button)) }
                    }
                }
            }
        }
    }
}
