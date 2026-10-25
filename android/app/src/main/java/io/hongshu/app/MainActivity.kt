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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        CallLogObserver.ensure(this)
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
    val callLog: Boolean = false,
)

@Composable
fun HongshuUI(activity: MainActivity) {
    val repo = remember { Repository(activity) }
    DisposableEffect(Unit) { onDispose { repo.store.close() } }
    val changes by Store.changes.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val health by RuntimeHealth.connection.collectAsState()
    val preferenceVersion = rememberPreferenceVersion(activity)
    val resumeCount = rememberResumeCount()
    val localContacts = remember { LocalContacts(activity.applicationContext) }
    ObserveLocalContacts(localContacts, repo.config.localContacts, hasPermission(activity, android.Manifest.permission.READ_CONTACTS))
    val contactRevision by localContacts.revision.collectAsState()

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
    var homeView by remember { mutableStateOf(HomeView.SMS) }
    var callLimit by remember { mutableIntStateOf(200) }
    var loadedCalls by remember { mutableStateOf<List<CachedCall>?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val layoutSpec = rememberAdaptiveLayoutSpec()
    val isExpanded = layoutSpec.twoPane
    val searchActive = isSearchMode(navigationStack, isExpanded)

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

    val destination = navigationStack.last()
    LaunchedEffect(changes, simFilter, messageLimit, group, destination, searchActive, visible) {
        if (!visible || destination is AppDestination.Settings || searchActive) return@LaunchedEffect
        loadedHomeMessages =
            withContext(Dispatchers.IO) {
                repo.store.readUnlessClosed(emptyList()) {
                    messagesWithLocal("", "", simFilter, limit = messageLimit, group = group)
                }
            }
    }

    LaunchedEffect(changes, callLimit, homeView, visible, destination) {
        if (!visible || destination is AppDestination.Settings || homeView != HomeView.CALLS) return@LaunchedEffect
        loadedCalls =
            withContext(Dispatchers.IO) {
                repo.store.readUnlessClosed(emptyList()) { cachedCalls(callLimit) }
            }
    }

    val searchSenders by produceState<Set<String>>(emptySet(), changes, searchQuery, contactRevision) {
        value = emptySet()
        value = withContext(Dispatchers.IO) {
            if (searchQuery.isBlank() || !localContacts.available()) emptySet()
            else localContacts.matchingSenders(searchQuery, repo.store.readUnlessClosed(emptyList()) { contactSearchSenders() })
        }
    }

    LaunchedEffect(changes, searchQuery, simFilter, messageLimit, group, searchSenders, destination, searchActive, visible) {
        if (!visible || destination is AppDestination.Settings || !searchActive) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        loadedSearchMessages =
            withContext(Dispatchers.IO) {
                repo.store.readUnlessClosed(emptyList()) {
                    messagesWithLocal("", searchQuery, simFilter, limit = messageLimit, group = group, localContactSenders = searchSenders)
                }
            }
    }

    val lists by
        produceState(HomeLists(), changes, simFilter, searchQuery, searchSenders, visible, destination, searchActive) {
            if (!visible || destination is AppDestination.Settings) return@produceState
            val activeQuery = if (searchActive) searchQuery else ""
            value =
                withContext(Dispatchers.IO) {
                    repo.store.readUnlessClosed(HomeLists()) {
                        val sims = sims()
                        HomeLists(
                            receivers = (sims.map { it.phone } + receivers()).filter { it.isNotEmpty() }.distinct(),
                            sims = sims,
                            pending = pendingCounts(),
                            aiEnabled = aiEnabled(),
                            groupCounts = groupCounts(activeQuery, simFilter, searchSenders),
                            callLog = hubSupportsCallLog(),
                        )
                    }
                }
        }
    LaunchedEffect(lists.callLog) {
        if (!lists.callLog && homeView == HomeView.CALLS) {
            homeView = HomeView.SMS
            navigationStack = listOf(AppDestination.Home)
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

    fun navigateSettings(page: SettingsPage) {
        navigationStack = navigateSettingsDestination(navigationStack, page, isExpanded)
    }

    fun navigateConversation(sender: String) {
        val destination = AppDestination.Conversation(sender)
        navigationStack =
            if (navigationStack.lastOrNull() is AppDestination.Conversation) {
                replaceTopDestination(navigationStack, destination)
            } else pushDestination(navigationStack, destination)
    }

    fun navigateCall(id: Long) {
        val destination = AppDestination.Call(id)
        navigationStack =
            if (navigationStack.lastOrNull() is AppDestination.Call) replaceTopDestination(navigationStack, destination)
            else pushDestination(navigationStack, destination)
    }

    // Switching between SMS and calls starts from that view's list.
    fun switchView(view: HomeView) {
        if (view == homeView) return
        homeView = view
        callLimit = 200
        searchQuery = ""
        navigationStack = listOf(AppDestination.Home)
    }

    fun popNavigation() {
        if (navigationStack.size == 1) {
            activity.leaveForBackground()
            return
        }
        val wasSearching = AppDestination.Search in navigationStack
        navigationStack = popNavigationStack(navigationStack, isExpanded)
        if (wasSearching && AppDestination.Search !in navigationStack) searchQuery = ""
    }

    fun updateSearch(active: Boolean) {
        navigationStack = updateSearchDestination(navigationStack, active)
        if (!active) searchQuery = ""
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
            onOpenSettings = ::navigateSettings,
            onRefresh = ::refresh,
            onDismissSetup = {
                setupHidden = true
                setSetupDismissed(activity, true)
                showMessage("设置引导已隐藏，可在设置中继续完成")
            },
            onMessage = ::showMessage,
            onGroupChange = { group = it },
            onViewChange = ::switchView,
            onSelectCall = ::navigateCall,
            onLoadMoreCalls = { callLimit += 200 },
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

    CompositionLocalProvider(LocalContactSource provides localContacts, LocalContactRevision provides contactRevision) {
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
                            onNavigate = ::navigateSettings,
                            onBack = ::popNavigation,
                            onRequestPermissions = permissionRequest,
                            onAction = ::action,
                            onMessage = ::showMessage,
                            layoutSpec = layoutSpec,
                        )
                    else -> {
                        val inSearch = isSearchMode(navigationStack, isExpanded)
                        MainSurface(
                            destination = destination,
                            model =
                                HomeModel(
                                    repo = repo,
                                    messages =
                                        if (inSearch) loadedSearchMessages
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
                                    view = homeView,
                                    callLogAvailable = lists.callLog,
                                    calls = loadedCalls,
                                    callLimit = callLimit,
                                ),
                            actions = homeActions,
                            twoPane = isExpanded,
                            searching = inSearch,
                            layoutSpec = layoutSpec,
                        )
                    }
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .widthIn(max = 560.dp)
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }
}
