package dev.marck.jsyncdrive.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What a state mark says. Each is a different ICON as well as a different tone, so it reads without colour. */
enum class CellState {
    /** Check: connected, synced, done. */
    Solid,

    /** Spinner: working on it. */
    Hollow,

    /** Empty ring: off, paused, offline, nothing to do. */
    Dashed,

    /** Lock: partly (read-only). */
    Half,

    /** Exclamation: failed or refused. */
    Struck,
}

@Composable
fun StateCell(state: CellState, description: String, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val cs = MaterialTheme.colorScheme
    val m = modifier.size(size).semantics { contentDescription = description }
    when (state) {
        CellState.Hollow -> CircularProgressIndicator(m, strokeWidth = 2.dp, color = cs.primary, trackColor = cs.primaryContainer)
        else -> {
            val (icon, tint) = when (state) {
                CellState.Solid -> Icons.Filled.CheckCircle to cs.primary
                CellState.Dashed -> Icons.Outlined.RadioButtonUnchecked to cs.outline
                CellState.Half -> Icons.Outlined.Lock to cs.onSurfaceVariant
                else -> Icons.Outlined.ErrorOutline to cs.error
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = m)
        }
    }
}

/** The app's mark: a cloud on a blue tile. */
@Composable
fun AppMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.58f))
    }
}

/** A lock in a tonal circle: the sign of an end-to-end encrypted folder. */
@Composable
fun LockBadge(size: Dp, modifier: Modifier = Modifier, open: Boolean = false) {
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(
            if (open) Icons.Outlined.LockOpen else Icons.Outlined.Lock, contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** A progress bar. [progress] null means working with no known end. */
@Composable
fun Gauge(progress: Float?, modifier: Modifier = Modifier, description: String = "") {
    val m = modifier.fillMaxWidth().semantics { if (description.isNotEmpty()) contentDescription = description }
    val cs = MaterialTheme.colorScheme
    if (progress == null) LinearProgressIndicator(m, color = cs.primary, trackColor = cs.primaryContainer)
    else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = m, color = cs.primary, trackColor = cs.primaryContainer)
}

/** A big soft icon for places with nothing in them. */
@Composable
fun EmptyIllustration(size: Dp, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.FolderOpen) {
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.46f))
    }
}
