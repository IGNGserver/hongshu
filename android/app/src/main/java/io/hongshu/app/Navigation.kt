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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Responsive layout metrics derived from the current window size.
 *
 * Compact (`< 600dp`) uses a single-pane flow; Medium (`600..839dp`, such as phones in landscape
 * or small tablets) and Expanded (`>= 840dp`, tablets and desktop windows) use a master-detail
 * two-pane layout with proportional pane widths and bounded reading widths.
 */
data class AdaptiveLayoutSpec(
    val width: Dp,
    val height: Dp,
    val twoPane: Boolean,
    val expanded: Boolean,
    val compactHeight: Boolean,
    val listPaneWidth: Dp,
    val settingsListPaneWidth: Dp,
    val paneSpacing: Dp,
    val outerPadding: Dp,
    val bubbleMaxWidth: Dp,
    val conversationMaxWidth: Dp,
    val settingsMaxWidth: Dp,
)

fun adaptiveLayoutSpec(width: Dp, height: Dp): AdaptiveLayoutSpec {
    val twoPane = width >= 600.dp
    val expanded = width >= 840.dp
    val compactHeight = height < 480.dp
    val listPaneWidth =
        when {
            !twoPane -> width
            expanded -> (width * 0.37f).coerceIn(360.dp, 420.dp)
            else -> (width * 0.42f).coerceIn(300.dp, 348.dp)
        }
    val settingsListPaneWidth =
        when {
            !twoPane -> width
            expanded -> (width * 0.36f).coerceIn(340.dp, 400.dp)
            else -> (width * 0.40f).coerceIn(288.dp, 336.dp)
        }
    return AdaptiveLayoutSpec(
        width = width,
        height = height,
        twoPane = twoPane,
        expanded = expanded,
        compactHeight = compactHeight,
        listPaneWidth = listPaneWidth,
        settingsListPaneWidth = settingsListPaneWidth,
        paneSpacing = if (expanded) 12.dp else 8.dp,
        outerPadding = if (expanded) 16.dp else 12.dp,
        bubbleMaxWidth = if (twoPane) 460.dp else 320.dp,
        conversationMaxWidth = if (expanded) 860.dp else 720.dp,
        settingsMaxWidth = if (expanded) 780.dp else 680.dp,
    )
}

@Composable
fun rememberAdaptiveLayoutSpec(): AdaptiveLayoutSpec {
    val window = LocalWindowInfo.current
    val density = LocalDensity.current
    val width = with(density) { window.containerSize.width.toDp() }
    val height = with(density) { window.containerSize.height.toDp() }
    return remember(width, height) { adaptiveLayoutSpec(width, height) }
}

sealed interface AppDestination {
    data object Home : AppDestination

    data object Search : AppDestination

    data class Conversation(val sender: String) : AppDestination

    data class Settings(val page: SettingsPage) : AppDestination
}

enum class SettingsPage(val title: String) {
    Overview("设置"),
    Hub("中枢连接"),
    Analysis("AI 短信分组"),
    Capture("短信采集"),
    Sims("SIM 卡与号码"),
    Contacts("本机联系人"),
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
 * Pops one step from the navigation stack.
 *
 * In two-pane settings, Overview is permanently shown in the left pane while the right pane shows
 * the active subpage, so pressing Back from a top-level subpage closes settings directly instead
 * of stopping on a redundant Overview-only state.
 */
internal fun popNavigationStack(
    stack: List<AppDestination>,
    twoPane: Boolean,
): List<AppDestination> {
    if (stack.size <= 1) return stack
    if (
        twoPane &&
            stack.lastOrNull() is AppDestination.Settings &&
            stack.getOrNull(stack.lastIndex - 1) == AppDestination.Settings(SettingsPage.Overview)
    ) {
        return closeSettingsDestinations(stack)
    }
    return popDestination(stack)
}

/**
 * Updates the navigation stack when entering or exiting search mode.
 */
internal fun updateSearchDestination(
    stack: List<AppDestination>,
    active: Boolean,
): List<AppDestination> {
    if (active) {
        return if (AppDestination.Search in stack) stack
        else pushDestination(stack, AppDestination.Search)
    }
    val searchIndex = stack.lastIndexOf(AppDestination.Search)
    return if (searchIndex >= 0) {
        val next = stack.filterIndexed { index, _ -> index != searchIndex }
        next.ifEmpty { listOf(AppDestination.Home) }
    } else {
        stack
    }
}

/**
 * Navigates to a settings page while avoiding unbounded subpage history in two-pane mode.
 *
 * In single-pane mode each drill-down pushes onto the stack so back returns to Overview first.
 * In two-pane mode Overview is always visible on the left, so switching between subpages from the
 * left menu replaces the active subpage while sub-drills (e.g. Pending -> Sims) still push if
 * needed or switch cleanly.
 */
internal fun navigateSettingsDestination(
    stack: List<AppDestination>,
    page: SettingsPage,
    twoPane: Boolean,
): List<AppDestination> {
    val target = AppDestination.Settings(page)
    val current = stack.lastOrNull()
    return if (twoPane && current is AppDestination.Settings && page != SettingsPage.Overview) {
        if (current.page == SettingsPage.Overview) pushDestination(stack, target)
        else replaceTopDestination(stack, target)
    } else {
        pushDestination(stack, target)
    }
}

/**
 * True when the search bar and filtered conversation list should be active.
 *
 * In two-pane mode, opening a conversation from search keeps `Search` on the stack right below
 * `Conversation`, so the left pane stays in search mode while the right pane previews the thread.
 */
internal fun isSearchMode(stack: List<AppDestination>, twoPane: Boolean): Boolean {
    val last = stack.lastOrNull() ?: return false
    if (last == AppDestination.Search) return true
    return twoPane &&
        last is AppDestination.Conversation &&
        stack.getOrNull(stack.lastIndex - 1) == AppDestination.Search
}

/**
 * Which destinations share one screen and therefore change in place instead of sliding.
 *
 * Search is the home list with its header in search mode, and on wide screens a conversation
 * opens in the detail pane next to the list and settings subpages open next to the overview pane,
 * so none of those transitions is a separate full-screen slide.
 */
internal fun AppDestination.screenKey(twoPane: Boolean): String =
    when (this) {
        AppDestination.Home,
        AppDestination.Search -> "main"
        is AppDestination.Conversation -> if (twoPane) "main" else "conversation:$sender"
        is AppDestination.Settings -> if (twoPane) "settings" else "settings:${page.name}"
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
