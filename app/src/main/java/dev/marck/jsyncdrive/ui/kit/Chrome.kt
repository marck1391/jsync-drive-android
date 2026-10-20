package dev.marck.jsyncdrive.ui.kit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.NodeStatus
import dev.marck.jsyncdrive.ui.theme.AppShapes

private data class StateLine(val cell: CellState, val title: Int, val proof: String)

@Composable
private fun stateLine(status: NodeStatus): StateLine {
    val (cell, title) = when {
        status.state == "online" && !status.readWrite -> CellState.Half to R.string.status_connected_ro
        status.state == "online" -> CellState.Solid to R.string.status_connected
        status.state == "connecting" || status.state == "starting" -> CellState.Hollow to R.string.status_connecting
        status.state == "pending" -> CellState.Hollow to R.string.status_pending
        status.state == "offline" -> CellState.Dashed to R.string.status_offline
        status.state == "needs_setup" -> CellState.Half to R.string.status_needs_setup
        status.state == "logged_out" -> CellState.Dashed to R.string.status_signed_out
        else -> CellState.Struck to R.string.status_stopped
    }
    val proof = when (status.state) {
        "online" -> listOf(status.email, status.hub).filter { it.isNotEmpty() }.joinToString("  ·  ")
        "offline" -> status.detail.ifEmpty { stringResource(R.string.status_retrying) }
        "connecting", "starting" -> status.hub.ifEmpty { stringResource(R.string.status_reaching_hub) }
        "needs_setup" -> stringResource(R.string.status_needs_setup_proof)
        "pending" -> stringResource(R.string.status_pending_proof)
        else -> status.detail
    }
    return StateLine(cell, title, proof)
}

/** The full connection card (Settings): state, the account and hub as proof, and that the session is encrypted. */
@Composable
fun StatusPlate(status: NodeStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val line = stateLine(status)
    val titleText = stringResource(line.title)
    Panel(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$titleText. ${line.proof}" },
        color = MaterialTheme.colorScheme.surfaceContainer, onClick = onClick, onClickLabel = stringResource(R.string.open_settings),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StateCell(line.cell, titleText)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(titleText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (line.proof.isNotEmpty()) Text(line.proof, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (status.state == "online") {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.status_encrypted), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A slim notice under the search bar when the Drive is not connected. Nothing at all while all is well. */
@Composable
fun ConnectionBanner(status: NodeStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (status.state == "online" && status.readWrite) return
    val line = stateLine(status)
    val titleText = stringResource(line.title)
    Panel(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$titleText. ${line.proof}" },
        shape = AppShapes.tile, color = MaterialTheme.colorScheme.secondaryContainer, onClick = onClick, onClickLabel = stringResource(R.string.open_settings),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateCell(line.cell, titleText, size = 18.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(titleText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                if (line.proof.isNotEmpty() && status.state != "online") {
                    Text(line.proof, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One step of a path. */
data class Crumb(val label: String, val path: String)

/** The path as steps you can jump back along. [statusBar] keeps it clear of the status bar when it is the top element. */
@Composable
fun PathBar(crumbs: List<Crumb>, onCrumb: (String) -> Unit, modifier: Modifier = Modifier, statusBar: Boolean = false) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier.fillMaxWidth().then(if (statusBar) Modifier.statusBarsPadding().height(56.dp) else Modifier).horizontalScroll(scroll).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, c ->
            val last = i == crumbs.lastIndex
            if (i > 0) Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            Text(
                c.label,
                style = if (last) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.then(if (last) Modifier else Modifier.pressable({ onCrumb(c.path) }, c.label)).padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
    }
}

/** A plain screen title, with room for actions on the right. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A destination of the main navigation. */
class Destination(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
fun AppNavigationBar(destinations: List<Destination>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        destinations.forEachIndexed { i, d ->
            NavigationBarItem(
                selected = i == selected, onClick = { onSelect(i) },
                icon = { Icon(if (i == selected) d.selectedIcon else d.icon, contentDescription = null) },
                label = { Text(d.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            )
        }
    }
}

@Composable
fun AppNavigationRail(destinations: List<Destination>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(
        modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        header = { Spacer(Modifier.height(16.dp)); AppMark(40.dp); Spacer(Modifier.height(8.dp)) },
    ) {
        Spacer(Modifier.weight(1f))
        destinations.forEachIndexed { i, d ->
            NavigationRailItem(
                selected = i == selected, onClick = { onSelect(i) },
                icon = { Icon(if (i == selected) d.selectedIcon else d.icon, contentDescription = null) },
                label = { Text(d.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}
