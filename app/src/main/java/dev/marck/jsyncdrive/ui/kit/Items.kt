package dev.marck.jsyncdrive.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marck.jsyncdrive.ui.theme.AppShapes

enum class DoorKind { Plain, Locked, Open }

/** A folder as a tile: the folder icon and its name, with a lock when it is end-to-end encrypted. */
@Composable
fun FolderTile(
    name: String,
    kind: DoorKind,
    description: String,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    moreLabel: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    color: Color? = null,
    pinned: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Panel(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        shape = AppShapes.tile, color = cs.surfaceContainer, onClick = onClick, onLongClick = onLongClick,
        contentPadding = PaddingValues(start = 14.dp, end = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = color ?: if (kind == DoorKind.Plain) cs.onSurfaceVariant else cs.primary, modifier = Modifier.size(24.dp))
            Text(
                name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface,
            )
            if (pinned) Icon(Icons.Outlined.PushPin, null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp).size(16.dp))
            when (kind) {
                DoorKind.Locked -> Icon(Icons.Outlined.Lock, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                DoorKind.Open -> Icon(Icons.Outlined.LockOpen, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                DoorKind.Plain -> {}
            }
            if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreVert, moreLabel, tint = cs.onSurfaceVariant) }
            else Box(Modifier.size(12.dp))
        }
    }
}

// --- files ---------------------------------------------------------------------------------------------------

enum class FileKind { Image, Video, Audio, Document, Sheet, Archive, Code, Other }

fun kindOf(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "svg", "avif" -> FileKind.Image
    "mp4", "mkv", "mov", "webm", "avi", "m4v" -> FileKind.Video
    "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac" -> FileKind.Audio
    "pdf", "doc", "docx", "odt", "txt", "md", "rtf", "epub" -> FileKind.Document
    "xls", "xlsx", "ods", "csv", "tsv" -> FileKind.Sheet
    "zip", "rar", "7z", "tar", "gz", "xz", "tgz", "bz2" -> FileKind.Archive
    "kt", "java", "go", "js", "ts", "py", "rs", "c", "cpp", "h", "json", "yaml", "yml", "xml", "html", "css", "sh" -> FileKind.Code
    else -> FileKind.Other
}

private fun iconOf(kind: FileKind): ImageVector = when (kind) {
    FileKind.Image -> Icons.Outlined.Image
    FileKind.Video -> Icons.Outlined.Movie
    FileKind.Audio -> Icons.Outlined.AudioFile
    FileKind.Document -> Icons.Outlined.Description
    FileKind.Sheet -> Icons.Outlined.TableChart
    FileKind.Archive -> Icons.Outlined.FolderZip
    FileKind.Code -> Icons.Outlined.Code
    FileKind.Other -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun tintOf(kind: FileKind): Color = when (kind) {
    FileKind.Image -> Color(0xFFD5644F)
    FileKind.Video -> Color(0xFFC2547E)
    FileKind.Audio -> Color(0xFF8E63CE)
    FileKind.Document -> MaterialTheme.colorScheme.primary
    FileKind.Sheet -> Color(0xFF2E9B63)
    FileKind.Archive -> Color(0xFFC08A1E)
    FileKind.Code -> MaterialTheme.colorScheme.secondary
    FileKind.Other -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The icon of the kind of file [name] is, in the colour of its kind. */
@Composable
fun FileTypeIcon(name: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val kind = kindOf(name)
    Icon(iconOf(kind), contentDescription = null, tint = tintOf(kind), modifier = modifier.size(size))
}

/** The preview of a file, filling its box: the picture when there is one, otherwise the kind of file as a large icon. */
@Composable
private fun Preview(name: String, thumb: ImageBitmap?, iconSize: Int, modifier: Modifier = Modifier, dir: Boolean = false) {
    val kind = kindOf(name)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (dir) Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize.dp))
        else if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(iconOf(kind), contentDescription = null, tint = tintOf(kind), modifier = Modifier.size(iconSize.dp))
    }
}

/** A file in the grid: its preview on top, then its name and what it is. */
@Composable
fun FileCard(
    name: String,
    meta: String,
    thumb: ImageBitmap?,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    moreLabel: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Panel(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$name, $meta" },
        shape = AppShapes.tile, color = cs.surfaceContainer, onClick = onClick, onLongClick = onLongClick,
    ) {
        Column {
            Preview(name, thumb, 44, Modifier.fillMaxWidth().aspectRatio(1.2f).padding(6.dp).clip(RoundedCornerShape(10.dp)))
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                    Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
                }
                if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreVert, moreLabel, tint = cs.onSurfaceVariant) }
                else Box(Modifier.size(12.dp))
            }
        }
    }
}

/** A file in the list: a small preview, its name, size and date. */
@Composable
fun FileRow(
    name: String,
    meta: String,
    thumb: ImageBitmap?,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    moreLabel: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    dir: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Panel(
        modifier.fillMaxWidth(), shape = RectangleShape, color = Color.Transparent, onClick = onClick, onLongClick = onLongClick,
        contentPadding = PaddingValues(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Preview(name, thumb, 24, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), dir)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant)
            }
            if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreVert, moreLabel, tint = cs.onSurfaceVariant) }
        }
    }
}
