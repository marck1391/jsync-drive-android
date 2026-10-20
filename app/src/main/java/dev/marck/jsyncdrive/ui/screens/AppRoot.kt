package dev.marck.jsyncdrive.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.kit.Destination
import dev.marck.jsyncdrive.ui.kit.AppNavigationBar
import dev.marck.jsyncdrive.ui.kit.AppNavigationRail
import dev.marck.jsyncdrive.ui.kit.rememberReducedMotion
import dev.marck.jsyncdrive.ui.theme.EmphasizedEase
import dev.marck.jsyncdrive.ui.theme.Motion

enum class Tab { FILES, SYNC, DEVICES, SETTINGS }

/** Asks the app to show a place (and, with [transfer], the window of that transfer). [nonce] makes every request new. */
data class NavRequest(val tab: Tab = Tab.FILES, val transfer: String? = null, val nonce: Long = 0L)

/** The window width from which the navigation moves to a rail on the side and the content gets room to breathe. */
const val WideBreakpointDp = 600

/** The whole app: signing in until there is a login, then four places, navigated from the bottom or, on wide screens, the side. */
@Composable
fun AppRoot(vm: AppViewModel, nav: NavRequest = NavRequest()) {
    val status by vm.status.collectAsState()
    var tab by rememberSaveable { mutableStateOf(nav.tab) }
    val transfers by vm.p2pTransfers.collectAsState()
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    val signedIn = status.loggedIn && status.state != "pending"
    // A notification was tapped while the app was already open: go where it points.
    var handledNav by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(nav.nonce, signedIn) {
        if (signedIn && nav.nonce != 0L && nav.nonce != handledNav) {
            handledNav = nav.nonce
            tab = nav.tab
            nav.transfer?.let { vm.showTransfer(it) }
        }
    }
    // A request that arrives while the app is on screen opens its window by itself (once), unless the sender is trusted.
    LaunchedEffect(transfers, lifecycleState, signedIn) {
        if (signedIn && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && vm.transferDialog == null) {
            transfers.firstOrNull { it.status == "incoming" && !it.send && it.peerId !in vm.trusted && vm.claimRequestWindow(it.id) }
                ?.let { vm.showTransfer(it.id) }
        }
    }
    val wide = LocalConfiguration.current.screenWidthDp >= WideBreakpointDp
    val reduced = rememberReducedMotion()
    val destinations = listOf(
        Destination(stringResource(R.string.nav_files), Icons.Outlined.Folder, Icons.Filled.Folder),
        Destination(stringResource(R.string.nav_sync), Icons.Outlined.Sync, Icons.Filled.Sync),
        Destination(stringResource(R.string.nav_devices), Icons.Outlined.Devices, Icons.Filled.Devices),
        Destination(stringResource(R.string.nav_settings), Icons.Outlined.Settings, Icons.Filled.Settings),
    )
    val bottomBar = signedIn && !wide
    // From any other place, Back goes to Files first (and the predictive back animation gets a real destination).
    BackHandler(enabled = signedIn && tab != Tab.FILES) { tab = Tab.FILES }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding()) },
        bottomBar = { if (bottomBar) AppNavigationBar(destinations, tab.ordinal, { tab = Tab.entries[it] }) },
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().then(if (bottomBar) Modifier else Modifier.navigationBarsPadding())) {
            if (signedIn && wide) AppNavigationRail(destinations, tab.ordinal, { tab = Tab.entries[it] }, Modifier.fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (!signedIn) {
                    SignInScreen(vm, status)
                } else {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            if (reduced) ContentTransform(EnterTransition.None, ExitTransition.None)
                            else fadeIn(tween(Motion.Standard, delayMillis = 60, easing = EmphasizedEase)) togetherWith fadeOut(tween(Motion.Quick))
                        },
                        label = "tabs",
                    ) { t ->
                        when (t) {
                            Tab.FILES -> FilesScreen(vm, status, onOpenSettings = { tab = Tab.SETTINGS }, wide = wide)
                            Tab.SYNC -> SyncScreen(vm)
                            Tab.DEVICES -> DevicesScreen(vm)
                            Tab.SETTINGS -> SettingsScreen(vm, status)
                        }
                    }
                }
            }
        }
    }
    vm.transferDialog?.let { id -> if (signedIn) TransferDialog(vm, id) { vm.transferDialog = null } }
}
