package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Horizontally flips the modified node when [LocalLayoutDirection] is [LayoutDirection.Rtl].
 *
 * For a custom directional drawable (a chevron, an arrow) that has no Compose auto-mirrored
 * counterpart — prefer `Icons.AutoMirrored.*` when one exists; reach for this only for a
 * project-owned vector asset. Never apply it to an icon that embeds text or digits: mirroring flips
 * the glyphs along with the shape.
 */
@Composable
@ReadOnlyComposable
public fun Modifier.mirrorOnRtl(): Modifier = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
    this.then(Modifier.scale(scaleX = -1f, scaleY = 1f))
} else {
    this
}

/**
 * Horizontally flips the modified node when [LocalLayoutDirection] is [LayoutDirection.Ltr].
 *
 * For a drawable whose source asset points in the trailing direction but should visually point
 * leading-side under both directions — a back button drawn with a "chevron right" asset, which must
 * point left under LTR and right under RTL.
 */
@Composable
@ReadOnlyComposable
public fun Modifier.mirrorOnLtr(): Modifier = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
    this.then(Modifier.scale(scaleX = -1f, scaleY = 1f))
} else {
    this
}
