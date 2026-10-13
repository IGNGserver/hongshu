package io.hongshu.app

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException

sealed interface AppDestination {
    data object Home : AppDestination

    data object Search : AppDestination

    data class Conversation(val sender: String, val receiver: String) : AppDestination

    data class Settings(val page: SettingsPage) : AppDestination
}

enum class SettingsPage(val title: String) {
    Overview("中枢与设置"),
    Hub("管理中枢与凭据"),
    Sims("SIM 卡与号码识别"),
    Sync("实时广播与同步"),
    Pending("本机待同步短信"),
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
 * A small navigation host for the app's finite destination set.
 *
 * The host deliberately does not own the stack. Keeping stack mutations in the screen owner
 * makes stateful actions (such as closing settings after a successful save) explicit while this
 * composable owns the system back gesture and its interactive animation.
 */
@Composable
fun AppNavigationHost(
    stack: List<AppDestination>,
    onPop: () -> Unit,
    onRootBack: () -> Unit,
    content: @Composable (AppDestination) -> Unit,
) {
    check(stack.isNotEmpty()) { "The app navigation stack must contain a root destination" }

    val current = stack.last()
    val previous = stack.getOrNull(stack.lastIndex - 1)
    val canPop = previous != null
    val backProgress = remember { Animatable(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var gestureActive by remember { mutableStateOf(false) }
    val latestOnPop by rememberUpdatedState(onPop)
    val stateHolder = rememberSaveableStateHolder()

    PredictiveBackHandler(enabled = canPop) { progressFlow ->
        gestureActive = true
        try {
            progressFlow.collect { event ->
                swipeEdge = event.swipeEdge
                backProgress.snapTo(event.progress)
            }
            backProgress.snapTo(1f)
            latestOnPop()
        } catch (_: CancellationException) {
            backProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            )
        } finally {
            backProgress.snapTo(0f)
            gestureActive = false
        }
    }

    BackHandler(enabled = !canPop, onBack = onRootBack)

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        if (gestureActive && previous != null) {
            val direction = if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
            val progress = backProgress.value

            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = direction * (progress - 1f) * widthPx
                },
            ) {
                DestinationContent(stateHolder, previous, content)
            }
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = direction * progress * widthPx
                },
            ) {
                DestinationContent(stateHolder, current, content)
            }
        } else {
            DestinationContent(stateHolder, current, content)
        }
    }
}

@Composable
private fun DestinationContent(
    stateHolder: SaveableStateHolder,
    destination: AppDestination,
    content: @Composable (AppDestination) -> Unit,
) {
    stateHolder.SaveableStateProvider(destination.saveableKey()) { content(destination) }
}

private fun AppDestination.saveableKey(): String =
    when (this) {
        AppDestination.Home -> "home"
        AppDestination.Search -> "search"
        is AppDestination.Conversation -> "conversation:$sender:$receiver"
        is AppDestination.Settings -> "settings:${page.name}"
    }
