package com.austinlinux.dfmetronome.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

const val PM_MORPH_MS = 420
val PmMotionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@OptIn(ExperimentalSharedTransitionApi::class)
class SharedScopes(val shared: SharedTransitionScope, val animated: AnimatedVisibilityScope)

/** Null outside a nav destination that opted in, so screens still render without a transition. */
val LocalSharedScopes = compositionLocalOf<SharedScopes?> { null }

fun sessionCardKey(id: Long) = "session-card-$id"
fun sessionGraphKey(id: Long) = "session-graph-$id"

@OptIn(ExperimentalSharedTransitionApi::class)
private val PmBoundsTransform = BoundsTransform { _, _ -> tween(PM_MORPH_MS, easing = PmMotionEasing) }

/** History card <-> Summary morph: the bounds animate while the two surfaces cross-fade with overlap. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sessionSharedBounds(key: String, fade: Boolean = true): Modifier {
    val scopes = LocalSharedScopes.current ?: return this
    return with(scopes.shared) {
        this@sessionSharedBounds.sharedBounds(
            rememberSharedContentState(key),
            animatedVisibilityScope = scopes.animated,
            // Container fades overlap (never a gap) so a surface is always drawn; the graph key skips fades and stays visible.
            enter = if (fade) fadeIn(tween(PM_MORPH_MS / 2, easing = PmMotionEasing)) else EnterTransition.None,
            exit = if (fade) fadeOut(tween(PM_MORPH_MS / 2, delayMillis = PM_MORPH_MS / 2)) else ExitTransition.None,
            boundsTransform = PmBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
        )
    }
}

/** Fades in the Summary content that is not shared-bounds (Back, headline, stats/chip, lost warning) during the second half of the morph. */
@Composable
fun Modifier.fadeInAfterMorph(): Modifier {
    val scopes = LocalSharedScopes.current ?: return this
    return with(scopes.animated) {
        this@fadeInAfterMorph.animateEnterExit(
            enter = fadeIn(tween(PM_MORPH_MS / 2, delayMillis = PM_MORPH_MS / 2)),
            exit = fadeOut(tween(150))
        )
    }
}

/** Card-only content fades out early on open (and back in late on Back); mirrors fadeInAfterMorph. */
@Composable
fun Modifier.fadeOutEarly(): Modifier {
    val scopes = LocalSharedScopes.current ?: return this
    return with(scopes.animated) {
        this@fadeOutEarly.animateEnterExit(
            enter = fadeIn(tween(PM_MORPH_MS / 2, delayMillis = PM_MORPH_MS / 2)),
            exit = fadeOut(tween(150))
        )
    }
}
