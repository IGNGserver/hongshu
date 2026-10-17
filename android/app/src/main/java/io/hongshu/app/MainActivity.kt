@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateRecentTaskVisibility(this, Config(this).hideFromRecents)
        // Note: Removed window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) to allow screenshots
        setContent {
            HongshuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HongshuUI(this)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateRecentTaskVisibility(this, Config(this).hideFromRecents)
        SimMonitor.start(this)
        SmsInboxObserver.ensure(this)
        syncNow(this)
        if (!RuntimeHealth.connection.value.running) startRealtimeIfEnabled(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    fun leaveForBackground() {
        updateRecentTaskVisibility(this, Config(this).hideFromRecents)
        moveTaskToBack(true)
    }
}

/**
 * 紧凑时间格式化：
 * - 今天：展示 HH:mm
 * - 今年：展示 M月d日
 * - 往年：展示 yyyy年M月d日
 */
fun formatTimestamp(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = timestamp }

    return when {
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) -> {
            SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

/** Store-backed lists the main screen needs besides the messages themselves. */
private data class HomeLists(
    val receivers: List<String> = emptyList(),
    val sims: List<SimMapping> = emptyList(),
    val pending: PendingCounts = PendingCounts(),
    val aiEnabled: Boolean = false,
    val groupCounts: Map<String, Int> = emptyMap(),
)

@Composable
fun HongshuUI(activity: MainActivity) {
    val repo = remember { Repository(activity) }
    DisposableEffect(Unit) { onDispose { repo.store.close() } }
    val changes by Store.changes.collectAsState()
    val health by RuntimeHealth.connection.collectAsState()
    val preferenceVersion = rememberPreferenceVersion(activity)
    val resumeCount = rememberResumeCount()

    var searchQuery by remember { mutableStateOf("") }
    var simFilter by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("all") }
    var aiEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var setupHidden by remember { mutableStateOf(setupDismissed(activity)) }
    var navigationStack by remember { mutableStateOf(listOf<AppDestination>(AppDestination.Home)) }

    // null until the first query returns, so a cold start shows progress instead of flashing
    // the empty state for a frame.
    var loadedHomeMessages by remember { mutableStateOf<List<CachedMessage>?>(null) }
    var loadedSearchMessages by remember { mutableStateOf<List<CachedMessage>?>(null) }
    var messageLimit by remember { mutableIntStateOf(200) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val window = LocalWindowInfo.current
    val isExpanded = with(LocalDensity.current) { window.containerSize.width.toDp() } >= 840.dp

    fun runAction(announce: Boolean, task: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launchUiAction(
            snackbar,
            announce,
            setBusy = { busy = it },
            onFinished = { Store.changes.value++ },
            task,
        )
    }

    // One snackbar host sits above every destination, so settings and the main screen report
    // results the same way.
    fun action(task: suspend () -> String) = runAction(announce = true, task)

    fun showMessage(text: String) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val finished =
                try {
                    withContext(Dispatchers.IO) { syncAndWait(activity) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                } finally {
                    refreshing = false
                }
            if (!finished) showMessage("同步已排队，联网后会自动完成")
        }
    }

    var permissionContinuation by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            Store.changes.value++
            SimMonitor.start(activity)
            syncNow(activity)
            val continuation = permissionContinuation
            permissionContinuation = null
            continuation?.invoke(grants.values.all { it })
        }

    LaunchedEffect(searchQuery, simFilter, group) { messageLimit = 200 }

    LaunchedEffect(changes, simFilter, messageLimit, group) {
        loadedHomeMessages =
            withContext(Dispatchers.IO) {
                repo.store.readUnlessClosed(emptyList()) {
                    messagesWithLocal("", "", simFilter, limit = messageLimit, group = group)
                }
            }
    }

    LaunchedEffect(changes, searchQuery, simFilter, messageLimit, group) {
        loadedSearchMessages =
            withContext(Dispatchers.IO) {
                repo.store.readUnlessClosed(emptyList()) {
                    messagesWithLocal("", searchQuery, simFilter, limit = messageLimit, group = group)
                }
            }
    }

    val lists by
        produceState(HomeLists(), changes, simFilter, searchQuery) {
            value =
                withContext(Dispatchers.IO) {
                    repo.store.readUnlessClosed(HomeLists()) {
                        val sims = sims()
                        HomeLists(
                            receivers = (sims.map { it.phone } + receivers()).filter { it.isNotEmpty() }.distinct(),
                            sims = sims,
                            pending = pendingCounts(),
                            aiEnabled = aiEnabled(),
                            groupCounts = groupCounts(searchQuery, simFilter),
                        )
                    }
                }
        }
    LaunchedEffect(lists.aiEnabled) {
        aiEnabled = lists.aiEnabled
        if (group !in visibleMessageGroups(aiEnabled)) group = "all"
    }
    val facts =
        remember(changes, preferenceVersion, resumeCount, health, lists) {
            setupFacts(activity, repo.config, health, lists.pending)
        }
    val setup =
        remember(facts, lists, setupHidden) {
            setupProgress(facts, lists.sims.any { it.phone.isNotEmpty() }, setupHidden)
        }

    LaunchedEffect(activity) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(30000)
                if (repo.config.token.isNotEmpty()) syncNow(activity)
            }
        }
    }

    fun push(destination: AppDestination) {
        navigationStack = pushDestination(navigationStack, destination)
    }

    fun navigateConversation(sender: String) {
        val destination = AppDestination.Conversation(sender)
        navigationStack =
            if (navigationStack.lastOrNull() is AppDestination.Conversation) {
                replaceTopDestination(navigationStack, destination)
            } else pushDestination(navigationStack, destination)
    }

    fun popNavigation() {
        if (navigationStack.size == 1) {
            activity.leaveForBackground()
            return
        }
        val leaving = navigationStack.last()
        navigationStack = popDestination(navigationStack)
        if (leaving == AppDestination.Search) searchQuery = ""
    }

    fun updateSearch(active: Boolean) {
        if (active) {
            push(AppDestination.Search)
        } else if (navigationStack.lastOrNull() == AppDestination.Search) {
            popNavigation()
        } else {
            searchQuery = ""
        }
    }

    val permissionRequest: PermissionRequest = { permissions, continuation ->
        val missing = permissions.filterNot { hasPermission(activity, it) }.toTypedArray()
        if (missing.isEmpty()) continuation(true)
        else {
            permissionContinuation = continuation
            permissionLauncher.launch(missing)
        }
    }

    val homeActions =
        HomeActions(
            onSearchQueryChange = { searchQuery = it },
            onSearchActiveChange = ::updateSearch,
            onSimFilterChange = { simFilter = it },
            onSelectConversation = ::navigateConversation,
            onBack = ::popNavigation,
            onLoadMore = { messageLimit += 200 },
            onOpenSettings = { page -> push(AppDestination.Settings(page)) },
            onRefresh = ::refresh,
            onDismissSetup = {
                setupHidden = true
                setSetupDismissed(activity, true)
                showMessage("设置引导已隐藏，可在设置中继续完成")
            },
            onMessage = ::showMessage,
            onGroupChange = { group = it },
            onChangeCategory = { id, category ->
                action {
                    identityMutex.withLock { repo.api.request("/messages/$id/category", "PATCH", JSONObject().put("category", category)) }
                    if (syncAndWait(activity)) "分类已更新" else "分类已保存，等待同步到本机"
                }
            },
            onMarkAllRead = {
                action {
                    withContext(Dispatchers.IO) {
                        repo.store.markRead(all = true)
                        try {
                            activity.getSystemService(NotificationManager::class.java).cancel(2)
                            identityMutex.withLock {
                                repo.api.request("/messages/read", "POST", JSONObject().put("all", true))
                            }
                        } catch (_: Exception) {}
                    }
                    "已全部标记为已读"
                }
            },
        )

    Box(Modifier.fillMaxSize()) {
        AppNavigationHost(
            stack = navigationStack,
            twoPane = isExpanded,
            onPop = ::popNavigation,
            onRootBack = { activity.leaveForBackground() },
        ) { destination ->
            when (destination) {
                is AppDestination.Settings ->
                    SettingsScreen(
                        activity = activity,
                        repo = repo,
                        busy = busy,
                        page = destination.page,
                        onNavigate = { page -> push(AppDestination.Settings(page)) },
                        onBack = ::popNavigation,
                        onRequestPermissions = permissionRequest,
                        onAction = ::action,
                        onMessage = ::showMessage,
                    )
                else ->
                    MainSurface(
                        destination = destination,
                        model =
                            HomeModel(
                                repo = repo,
                                messages =
                                    if (destination == AppDestination.Search) loadedSearchMessages
                                    else loadedHomeMessages,
                                searchQuery = searchQuery,
                                simFilter = simFilter,
                                receivers = lists.receivers,
                                sims = lists.sims,
                                facts = facts,
                                messageLimit = messageLimit,
                                changes = changes,
                                busy = busy,
                                refreshing = refreshing,
                                setup = setup,
                                group = group,
                                aiEnabled = aiEnabled,
                                groupCounts = lists.groupCounts,
                            ),
                        actions = homeActions,
                        twoPane = isExpanded,
                    )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding(),
        )
    }
}
