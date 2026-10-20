package dev.marck.jsyncdrive.ui.kit

import androidx.compose.ui.graphics.Color
import dev.marck.jsyncdrive.R

/** The colours a folder can be tagged with. Mirrors the Drive server's palette exactly (it refuses any other value). */
class FolderPaletteColor(val hex: String, val name: Int) {
    val color: Color get() = parseHex(hex)
}

val FolderPalette = listOf(
    FolderPaletteColor("#4285F4", R.string.color_blue),
    FolderPaletteColor("#34A853", R.string.color_green),
    FolderPaletteColor("#FBBC04", R.string.color_yellow),
    FolderPaletteColor("#EA4335", R.string.color_red),
    FolderPaletteColor("#A142F4", R.string.color_purple),
    FolderPaletteColor("#FF6D01", R.string.color_orange),
    FolderPaletteColor("#F439A0", R.string.color_pink),
    FolderPaletteColor("#24C1E0", R.string.color_cyan),
    FolderPaletteColor("#9AA0A6", R.string.color_grey),
)

fun parseHex(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
