package dev.marck.jsyncdrive.ui.kit

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.ui.theme.AppShapes

/** True when the user switched animations off in the system settings (Remove animations / animator scale 0). */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** A rounded surface. With [onClick] it is a button (ripple, role); with [onLongClick] too it is the long-press target. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.card,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    outlined: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val clickable = if (onClick != null) {
        Modifier.combinedClickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick, onLongClick = onLongClick)
    } else Modifier
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(
            modifier
                .clip(shape)
                .background(color, shape)
                .then(if (outlined) Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape) else Modifier)
                .then(clickable)
                .padding(contentPadding),
            content = content,
        )
    }
}

// --- buttons --------------------------------------------------------------------------------------------------

@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled,
        shape = AppShapes.pill, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled,
        shape = AppShapes.pill, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
fun TextAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, content: @Composable RowScope.() -> Unit) {
    TextButton(
        onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled,
        shape = AppShapes.pill,
        colors = ButtonDefaults.textButtonColors(contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
        content = content,
    )
}

/** A heading for a group on a screen. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, top: androidx.compose.ui.unit.Dp = 20.dp) {
    Text(
        text, modifier.padding(top = top, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** Clickable with the platform ripple and a role, for rows that are not panels. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, label: String? = null): Modifier =
    this.clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
