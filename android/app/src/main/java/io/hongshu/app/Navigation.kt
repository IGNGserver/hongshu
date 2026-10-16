package io.hongshu.app

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface AppDestination {
    data object Home : AppDestination

    data object Search : AppDestination

    data class Conversation(val sender: String, val receiver: String) : AppDestination

    data class Settings(val page: SettingsPage) : AppDestination
}

enum class SettingsPage(val title: String) {
    Overview("设置"),
    Hub("中枢连接"),
    Analysis("AI 短信分组"),
    Capture("短信采集"),
    Sims("SIM 卡与号码"),
    Notifications("通知"),
    Background("后台运行"),
    Pending("未上传到中枢的短信"),
    Diagnostics("诊断信息"),
    About("关于鸿枢"),
}

internal fun pushDestination(
    stack: List<AppDestination>,
    destination: AppDestination,
): List<AppDestination> =
    if (stack.lastOrNull() == destination) stack else stack + destination

internal fun replaceTopDestination(
    stack: List<AppDestination>,
    destination: AppDestination,
): List<AppDestination> =
    if (stack.isEmpty()) listOf(destination) else stack.dropLast(1) + destination

internal fun popDestination(stack: List<AppDestination>): List<AppDestination> =
    if (stack.size <= 1) stack else stack.dropLast(1)

internal fun closeSettingsDestinations(stack: List<AppDestination>): List<AppDestination> {
    val lastNonSettings = stack.indexOfLast { it !is AppDestination.Settings }
    return if (lastNonSettings >= 0) stack.take(lastNonSettings + 1)
    else listOf(AppDestination.Home)
}

/**
 * Which destinations share one screen and therefore change in place instead of sliding.
 *
 * Search is the home list with its header in search mode, and on wide screens a conversation
 * opens in the detail pane next to the list, so neither is a separate full-screen page.
 */
internal fun AppDestination.screenKey(twoPane: Boolean): String =
    when (this) {
        AppDestination.Home,
        AppDestination.Search -> "main"
        is AppDestination.Conversation -> if (twoPane) "main" else "conversation:$sender:$receiver"
        is AppDestination.Settings -> "settings:${page.name}"
    }

/** True when moving from [from] to [to] goes back up the stack. */
internal fun isBackNavigation(
    stack: List<AppDestination>,
    from: AppDestination,
    to: AppDestination,
): Boolean {
    val fromIndex = stack.indexOf(from)
    // A destination that is no longer on the stack has just been popped.
    return fromIndex < 0 || stack.indexOf(to) in 0 until fromIndex
}

// M3 emphasized easing; durations follow the shared-axis pattern for page navigation.
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val NavDuration = 400

private fun forwardTransition(): ContentTransform =
    (slideInHorizontally(tween(NavDuration, easing = Emphasized)) { it / 5 } +
        fadeIn(tween(NavDuration / 2, delayMillis = NavDuration / 8))) togetherWith
        (slideOutHorizontally(tween(NavDuration, easing = Emphasized)) { -it / 10 } +
            fadeOut(tween(NavDuration / 4, easing = EmphasizedAccelerate)))

private fun backTransition(): ContentTransform =
    (slideInHorizontally(tween(NavDuration, easing = Emphasized)) { -it / 10 } +
        fadeIn(tween(NavDuration / 2))) togetherWith
        // M3 predictive back: the leaving page shrinks toward 90 % while the previous page
        // settles in beneath it; dragging the system back gesture scrubs this same transition.
        (scaleOut(
            tween(NavDuration, easing = Emphasized),
            targetScale = 0.9f,
            transformOrigin = TransformOrigin(0.5f, 0.5f),
        ) +
            slideOutHorizontally(tween(NavDuration, easing = Emphasized)) { it / 8 } +
            fadeOut(tween(NavDuration / 2, delayMillis = NavDuration / 4)))

/**
 * A small navigation host for the app's finite destination set.
 *
 * The host deliberately does not own the stack. Keeping stack mutations in the screen owner
 * makes stateful actions (such as closing settings after a successful save) explicit while this
 * composable owns the transition and the predictive back gesture that scrubs it.
 */
@Composable
fun AppNavigationHost(
    stack: List<AppDestination>,
    twoPane: Boolean,
    onPop: () -> Unit,
    onRootBack: () -> Unit,
    content: @Composable (AppDestination) -> Unit,
) {
    check(stack.isNotEmpty()) { "The app navigation stack must contain a root destination" }

    val current = stack.last()
    val previous = stack.getOrNull(stack.lastIndex - 1)
    val latestOnPop by rememberUpdatedState(onPop)
    val latestStack by rememberUpdatedState(stack)
    val stateHolder = rememberSaveableStateHolder()

    val transitionState = remember { SeekableTransitionState(current) }
    val transition = rememberTransition(transitionState, label = "navigation")
    var inPredictiveBack by remember { mutableStateOf(false) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }

    // Popping to a destination that shares the current screen (search, a conversation in the
    // detail pane) changes content in place, so it keeps the plain back handling below.
    val previousIsOtherScreen =
        previous != null && previous.screenKey(twoPane) != current.screenKey(twoPane)

    PredictiveBackHandler(enabled = previousIsOtherScreen) { progressFlow ->
        inPredictiveBack = true
        try {
            progressFlow.collect { event ->
                swipeEdge = event.swipeEdge
                backProgress = event.progress
            }
            inPredictiveBack = false
            latestOnPop()
        } catch (e: CancellationException) {
            inPredictiveBack = false
            throw e
        }
    }
    BackHandler(enabled = previous != null && !previousIsOtherScreen) { latestOnPop() }
    BackHandler(enabled = previous == null, onBack = onRootBack)

    if (inPredictiveBack && previous != null) {
        LaunchedEffect(backProgress) { transitionState.seekTo(backProgress, previous) }
    } else {
        LaunchedEffect(current) {
            if (transitionState.currentState != current) {
                transitionState.animateTo(current)
            } else if (transitionState.targetState != current) {
                // A cancelled back gesture: run the scrubbed transition back to where it
                // started, then settle on the current destination.
                val millis = (transitionState.fraction * NavDuration).toInt()
                animate(transitionState.fraction, 0f, animationSpec = tween(millis)) { value, _ ->
                    launch {
                        if (value > 0f) transitionState.seekTo(value)
                        if (value == 0f) transitionState.snapTo(current)
                    }
                }
            }
        }
    }

    val transitionSpec: AnimatedContentTransitionScope<AppDestination>.() -> ContentTransform = {
        val back = isBackNavigation(latestStack, initialState, targetState)
        if (back) backTransition().apply { targetContentZIndex = -1f } else forwardTransition()
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer {
            // Nudge the whole content toward the edge the gesture started from, matching the
            // system's cross-activity back preview.
            if (inPredictiveBack) {
                val direction = if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                translationX = direction * backProgress * 8.dp.toPx()
            }
        }
    ) {
        transition.AnimatedContent(
            transitionSpec = transitionSpec,
            contentKey = { it.screenKey(twoPane) },
        ) { destination ->
            stateHolder.SaveableStateProvider(destination.screenKey(twoPane)) {
                content(destination)
            }
        }
    }
}
