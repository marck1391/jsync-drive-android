package dev.marck.jsyncdrive.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val JsyncShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object AppShapes {
    /** Cards and panels. */
    val card = RoundedCornerShape(18.dp)

    /** Folder tiles and file thumbnails. */
    val tile = RoundedCornerShape(14.dp)

    /** Text fields and small surfaces. */
    val field = RoundedCornerShape(12.dp)

    /** Buttons and the search pill. */
    val pill = CircleShape
}

/** One easing for the whole app: a fast start that settles (exponential ease-out). */
val EmphasizedEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

object Motion {
    const val Quick = 180
    const val Standard = 300
}
