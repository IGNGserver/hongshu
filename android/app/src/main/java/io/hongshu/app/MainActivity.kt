@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private fun formatDetailDateTime(timestamp: Long): String =
    SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
fun HongshuUI(activity: MainActivity) {
    val repo = remember { Repository(activity) }
    DisposableEffect(Unit) { onDispose { repo.store.close() } }
    val changes by Store.changes.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var simFilter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var navigationStack by remember { mutableStateOf(listOf<AppDestination>(AppDestination.Home)) }

    // null until the first query returns, so a cold start shows progress instead of flashing
    // the empty state for a frame.
    var loadedHomeMessages by remember { mutableStateOf<List<CachedMessage>?>(null) }
    var loadedSearchMessages by remember { mutableStateOf<List<CachedMessage>?>(null) }
    var messageLimit by remember { mutableIntStateOf(200) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 840

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

    fun action(task: suspend () -> String) = runAction(announce = true, task)

    // Settings pages report through their own SnackbarHost; the main host is not on screen.
    fun settingsAction(task: suspend () -> String) = runAction(announce = false, task)

    var permissionContinuation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            scope.launch {
                snackbar.showSnackbar(
                    if (grants.values.all { it }) "权限已授予，可以继续操作" else "部分权限未授予，普通查看不受影响"
                )
            }
            Store.changes.value++
            SimMonitor.start(activity)
            syncNow(activity)
            val continuation = permissionContinuation
            permissionContinuation = null
            if (grants.values.all { it }) continuation?.invoke()
        }

    LaunchedEffect(searchQuery, simFilter) { messageLimit = 200 }

    LaunchedEffect(changes, simFilter, messageLimit) {
        loadedHomeMessages =
            withContext(Dispatchers.IO) {
                repo.store.messages(
                    "",
                    "",
                    simFilter,
                    limit = messageLimit,
                )
            }
    }

    LaunchedEffect(changes, searchQuery, simFilter, messageLimit) {
        loadedSearchMessages =
            withContext(Dispatchers.IO) {
                repo.store.messages(
                    "",
                    searchQuery,
                    simFilter,
                    limit = messageLimit,
                )
            }
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

    fun navigateConversation(sender: String, receiver: String) {
        val destination = AppDestination.Conversation(sender, receiver)
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

    fun openSettings() {
        push(AppDestination.Settings(SettingsPage.Overview))
    }

    fun closeSettings() {
        navigationStack = closeSettingsDestinations(navigationStack)
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
        if (missing.isEmpty()) continuation()
        else {
            permissionContinuation = continuation
            permissionLauncher.launch(missing)
        }
    }

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
                    onClose = ::closeSettings,
                    onRequestPermissions = permissionRequest,
                    onAction = ::settingsAction,
                )
            else ->
                MainSurface(
                    destination = destination,
                    activity = activity,
                    repo = repo,
                    loadedHomeMessages = loadedHomeMessages,
                    loadedSearchMessages = loadedSearchMessages,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onSearchActiveChange = ::updateSearch,
                    simFilter = simFilter,
                    onSimFilterChange = { simFilter = it },
                    onSelectConversation = ::navigateConversation,
                    onBack = ::popNavigation,
                    onLoadMore = { messageLimit += 200 },
                    messageLimit = messageLimit,
                    changes = changes,
                    busy = busy,
                    onOpenSettings = ::openSettings,
                    onSync = {
                        action {
                            syncNow(activity, manual = true)
                            "已请求中枢同步"
                        }
                    },
                    isExpanded = isExpanded,
                    snackbar = snackbar,
                )
        }
    }
}

@Composable
private fun MainSurface(
    destination: AppDestination,
    activity: MainActivity,
    repo: Repository,
    loadedHomeMessages: List<CachedMessage>?,
    loadedSearchMessages: List<CachedMessage>?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    simFilter: String,
    onSimFilterChange: (String) -> Unit,
    onSelectConversation: (String, String) -> Unit,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    messageLimit: Int,
    changes: Long,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    onSync: () -> Unit,
    isExpanded: Boolean,
    snackbar: SnackbarHostState,
) {
    val conversation = destination as? AppDestination.Conversation
    val showDetail = conversation != null
    val showSearch = destination == AppDestination.Search
    val listSelectedSender = conversation?.sender.orEmpty()
    val listMessages = if (showSearch) loadedSearchMessages else loadedHomeMessages

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (isExpanded) {
                Row(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.width(400.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ConversationListView(
                            activity = activity,
                            repo = repo,
                            loadedMessages = listMessages,
                            searchQuery = searchQuery,
                            onSearchQueryChange = onSearchQueryChange,
                            isSearchActive = showSearch,
                            onSearchActiveChange = onSearchActiveChange,
                            simFilter = simFilter,
                            onSimFilterChange = onSimFilterChange,
                            selectedSender = listSelectedSender,
                            onSelectConversation = onSelectConversation,
                            onLoadMore = onLoadMore,
                            messageLimit = messageLimit,
                            changes = changes,
                            busy = busy,
                            onOpenSettings = onOpenSettings,
                            onSync = onSync,
                        )
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        if (!showDetail) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ChatBubbleOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                    Text(
                                        "选择对话开始查看短信历史",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            ConversationDetailPane(
                                conversation = conversation!!,
                                repo = repo,
                                changes = changes,
                                showBack = false,
                                onBack = onBack,
                            )
                        }
                    }
                }
            } else if (showDetail) {
                ConversationDetailPane(
                    conversation = conversation!!,
                    repo = repo,
                    changes = changes,
                    showBack = true,
                    onBack = onBack,
                )
            } else {
                ConversationListView(
                    activity = activity,
                    repo = repo,
                    loadedMessages = listMessages,
                    searchQuery = searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    isSearchActive = showSearch,
                    onSearchActiveChange = onSearchActiveChange,
                    simFilter = simFilter,
                    onSimFilterChange = onSimFilterChange,
                    selectedSender = listSelectedSender,
                    onSelectConversation = onSelectConversation,
                    onLoadMore = onLoadMore,
                    messageLimit = messageLimit,
                    changes = changes,
                    busy = busy,
                    onOpenSettings = onOpenSettings,
                    onSync = onSync,
                )
            }

            if (busy) {
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

/** 鸿枢首页会话列表视图 */
@Composable
private fun ConversationListView(
    activity: MainActivity,
    repo: Repository,
    loadedMessages: List<CachedMessage>?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    simFilter: String,
    onSimFilterChange: (String) -> Unit,
    selectedSender: String,
    onSelectConversation: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    messageLimit: Int,
    changes: Long,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    onSync: () -> Unit,
) {
    var sims by remember { mutableStateOf<List<String>>(emptyList()) }
    var localPending by remember { mutableStateOf(PendingCounts()) }
    LaunchedEffect(changes) {
        sims =
            withContext(Dispatchers.IO) {
                (repo.store.sims().map { it.phone } + repo.store.receivers()).distinct()
            }
        localPending = withContext(Dispatchers.IO) { repo.store.pendingCounts() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：
            // "信息" 标题 + 搜索放大镜 + 圆形头像 (点击弹出账号弹窗)
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSearchActive) {
                    IconButton(
                        onClick = {
                            onSearchActiveChange(false)
                            onSearchQueryChange("")
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle =
                            TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.sp,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "搜索信息和联系人...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            innerTextField()
                        },
                    )
                } else {
                    Text(
                        text = "鸿枢",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(onClick = { onSearchActiveChange(true) }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    IconButton(onClick = onSync) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "同步",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(localPending.total > 0) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(horizontal = 18.dp),
                ) {
                    Text("${localPending.label()} · 设置中可离线查看")
                }
            }

            // SIM 过滤器 (以极简药丸 FilterChip 呈现)
            if (sims.isNotEmpty()) {
                var simMenuExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        FilterChip(
                            selected = simFilter.isNotEmpty(),
                            onClick = { simMenuExpanded = true },
                            label = { Text(simFilter.ifEmpty { "全部 SIM 卡" }) },
                            leadingIcon = {
                                Icon(
                                    if (simFilter.isNotEmpty()) Icons.Default.Done
                                    else Icons.Default.SimCard,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                        )
                        DropdownMenu(
                            expanded = simMenuExpanded,
                            onDismissRequest = { simMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部 SIM 卡") },
                                onClick = {
                                    onSimFilterChange("")
                                    simMenuExpanded = false
                                },
                            )
                            sims.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s) },
                                    onClick = {
                                        onSimFilterChange(s)
                                        simMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 消息会话列表（通栏设计，1:1 还原截图 1）
            if (loadedMessages == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else if (loadedMessages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            if (repo.config.token.isEmpty()) "连接短信中枢以开始" else "暂无短信记录",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (repo.config.token.isEmpty()) "短信历史将在您连接私有中枢后在此同步展现"
                            else "开启本机短信采集，或等待其他设备上传短信",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (repo.config.token.isEmpty()) {
                            Button(onClick = onOpenSettings, shape = RoundedCornerShape(20.dp)) {
                                Text("立即配置中枢")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(loadedMessages, key = { it.id }) { msg ->
                        ConversationRow(
                            message = msg,
                            isSelected = selectedSender == msg.sender,
                            onClick = { onSelectConversation(msg.sender, msg.receiver) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (loadedMessages.size >= messageLimit) {
                        item {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            ) {
                                Text("加载更早的会话")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单条会话行（1:1 复刻截图 1） 头像：正圆形彩色底（黄/绿/粉/蓝），内部为白人像图标或首字 标题：发件人粗体，右对齐日期（例如 9月5日） 副标题：最新内容，如果是自己发送的带 "您: "
 * 前缀
 */
@Composable
private fun ConversationRow(
    message: CachedMessage,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = message.contact.ifEmpty { message.sender }
    val avatarBg = remember(message.sender) { avatarColorFor(message.sender) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                    else Color.Transparent
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 圆形大头像 (52dp)
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(avatarBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }

        // 文本区（发件人、日期、短信内容预览）
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 会话详情界面 */
@Composable
private fun ConversationDetailPane(
    conversation: AppDestination.Conversation,
    repo: Repository,
    changes: Long,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    val sender = conversation.sender
    // Each conversation screen owns its query, so a leaving screen keeps its own messages for the
    // whole exit animation instead of reloading as soon as the destination changes.
    var messageLimit by remember(conversation) { mutableIntStateOf(200) }
    var messages by remember(conversation) { mutableStateOf<List<CachedMessage>>(emptyList()) }
    LaunchedEffect(conversation, changes, messageLimit) {
        messages =
            withContext(Dispatchers.IO) {
                repo.store.messages(sender, "", conversation.receiver, limit = messageLimit)
            }
    }
    val onLoadMore = { messageLimit += 200 }
    val deviceId = repo.config.deviceId
    val contactName = messages.firstOrNull()?.contact.orEmpty()
    val displayName = contactName.ifEmpty { sender }

    Column(Modifier.fillMaxSize()) {
        // 顶部导航栏
        TopAppBar(
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier.size(36.dp)
                                .clip(CircleShape)
                                .background(avatarColorFor(sender)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (contactName.isNotEmpty() && sender.isNotEmpty()) {
                            Text(
                                text = sender,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            },
        )

        // 对话气泡流
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                val isOutgoing = msg.deviceId == deviceId && msg.sender != sender
                MessageBubble(msg = msg, isOutgoing = isOutgoing, Modifier.animateItem())
            }
            if (messages.size >= messageLimit) {
                item {
                    TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("加载更早历史")
                    }
                }
            }
        }
    }
}

/** 现代 Material 3 不对称圆角气泡 */
@Composable
private fun MessageBubble(msg: CachedMessage, isOutgoing: Boolean, modifier: Modifier = Modifier) {
    val bubbleShape =
        if (isOutgoing) {
            RoundedCornerShape(22.dp, 22.dp, 4.dp, 22.dp)
        } else {
            RoundedCornerShape(22.dp, 22.dp, 22.dp, 4.dp)
        }

    val bubbleColor =
        if (isOutgoing) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

    val contentColor =
        if (isOutgoing) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            SelectionContainer {
                Text(
                    text = msg.body,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                )
            }
        }

        Spacer(Modifier.height(3.dp))

        Text(
            text =
                "${formatDetailDateTime(msg.timestamp)} · ${if (msg.receiver.isNotEmpty()) msg.receiver else "本机"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
