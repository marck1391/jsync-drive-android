package dev.marck.jsyncdrive.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.Entry
import dev.marck.jsyncdrive.ui.AppViewModel
import dev.marck.jsyncdrive.ui.Thumbnails

/**
 * Pictures open inside the app: swipe between the pictures of the folder, pinch or double-tap to zoom. It does not
 * depend on another app being able to read the Drive's content addresses (some gallery apps cannot).
 */
@Composable
fun ImageViewer(vm: AppViewModel, images: List<Entry>, start: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
    var zoomed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { images[it].path }) { page ->
                val e = images[page]
                Page(vm, e, active = page == pager.currentPage, onZoom = { zoomed = it }, onExternal = { vm.openExternally(e) })
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().background(Color(0x66000000)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.viewer_close), tint = Color.White) }
                Text(
                    images[pager.currentPage].name, Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { vm.shareExternally(images[pager.currentPage]) }) {
                    Icon(Icons.Outlined.Share, stringResource(R.string.share), tint = Color.White)
                }
                IconButton(onClick = { vm.openExternally(images[pager.currentPage]) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.viewer_open_with), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun Page(vm: AppViewModel, e: Entry, active: Boolean, onZoom: (Boolean) -> Unit, onExternal: () -> Unit) {
    val ctx = LocalContext.current
    val state = produceState<Result?>(null, e.path, e.size, e.mtime) {
        val bmp = Thumbnails.load(ctx, { vm.host.api }, e, px = 2048)
        value = Result(bmp?.asImageBitmap())
    }.value
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state == null -> CircularProgressIndicator(color = Color.White)
            state.image == null -> Text(
                stringResource(R.string.viewer_failed), Modifier.padding(32.dp).pressableOpen(onExternal),
                style = MaterialTheme.typography.bodyLarge, color = Color.White, textAlign = TextAlign.Center,
            )
            else -> Zoomable(state.image, active, onZoom)
        }
    }
}

private class Result(val image: ImageBitmap?)

private fun Modifier.pressableOpen(onClick: () -> Unit): Modifier = this.pointerInput(Unit) { detectTapGestures { onClick() } }

@Composable
private fun Zoomable(image: ImageBitmap, active: Boolean, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    // Back at rest when the page is swiped away.
    if (!active && scale != 1f) { scale = 1f; offset = Offset.Zero }
    Image(
        image, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    onZoom(scale > 1f)
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // One finger on a picture at rest is the pager's swipe; only pinching, or panning a zoomed picture, is ours.
                        if (event.changes.size > 1 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else clamp(offset + pan, scale)
                            onZoom(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}
