@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app
import android.os.Build

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
        SimMonitor.start(this)
        Thread {
            try {
                Config(this).captureStatus = catchUpInbox(this)
            } catch (_: Exception) {
                Config(this).captureStatus = "收件箱补扫失败，实时广播不受影响"
            }
        }.start()
        syncNow(this)
        if (Config(this).keepAlive && Config(this).token.isNotEmpty() && !RuntimeHealth.connection.value.running) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, RealtimeService::class.java))
            } catch (_: Exception) {
                RuntimeHealth.connection.value = ConnectionHealth(detail = "系统不允许启动后台服务")
            }
        }
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

    var selectedSender by remember { mutableStateOf("") }
    var threadReceiver by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var simFilter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var loadedMessages by remember { mutableStateOf<List<CachedMessage>>(emptyList()) }
    var messageLimit by remember { mutableIntStateOf(200) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 840

    fun action(task: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { task() }
                snackbar.showSnackbar(text)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "操作失败")
            } finally {
                busy = false
                Store.changes.value++
            }
        }
    }

    var permissionContinuation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
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

    LaunchedEffect(searchQuery, simFilter, selectedSender, threadReceiver) {
        messageLimit = 200
    }

    LaunchedEffect(changes, searchQuery, simFilter, selectedSender, threadReceiver, messageLimit) {
        loadedMessages = withContext(Dispatchers.IO) {
            repo.store.messages(
                selectedSender,
                searchQuery,
                if (selectedSender.isNotEmpty()) threadReceiver else simFilter,
                limit = messageLimit,
            )
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30000)
            if (repo.config.token.isNotEmpty()) syncNow(activity)
        }
    }

    fun closeThread() {
        selectedSender = ""
        threadReceiver = ""
    }

    BackHandler(selectedSender.isNotEmpty() || isSearchActive) {
        when {
            selectedSender.isNotEmpty() -> closeThread()
            isSearchActive -> {
                isSearchActive = false
                searchQuery = ""
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (isExpanded) {
                // 大屏平板/折叠屏双栏布局
                Row(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier
                            .width(400.dp)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ConversationListView(
                            activity = activity,
                            repo = repo,
                            loadedMessages = loadedMessages,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            isSearchActive = isSearchActive,
                            onSearchActiveChange = { isSearchActive = it },
                            simFilter = simFilter,
                            onSimFilterChange = { simFilter = it },
                            selectedSender = selectedSender,
                            onSelectConversation = { s, r ->
                                selectedSender = s
                                threadReceiver = r
                            },
                            onLoadMore = { messageLimit += 200 },
                            messageLimit = messageLimit,
                            changes = changes,
                            busy = busy,
                            onOpenSettings = { showSettingsDialog = true },
                            onSync = { action { syncNow(activity, manual = true); "已请求中枢同步" } },
                        )
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        if (selectedSender.isEmpty()) {
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
                                sender = selectedSender,
                                contactName = loadedMessages.firstOrNull()?.contact ?: "",
                                messages = loadedMessages,
                                deviceId = repo.config.deviceId,
                                onBack = { closeThread() },
                                onLoadMore = { messageLimit += 200 },
                                messageLimit = messageLimit,
                            )
                        }
                    }
                }
            } else {
                // 紧凑单栏视图
                AnimatedContent(
                    targetState = selectedSender.isNotEmpty(),
                    transitionSpec = {
                        slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (targetState) it else -it } togetherWith
                                slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (targetState) -it else it }
                    },
                    label = "compact_detail_transition",
                ) { inDetail ->
                    if (inDetail) {
                        ConversationDetailPane(
                            sender = selectedSender,
                            contactName = loadedMessages.firstOrNull()?.contact ?: "",
                            messages = loadedMessages,
                            deviceId = repo.config.deviceId,
                            onBack = { closeThread() },
                            onLoadMore = { messageLimit += 200 },
                            messageLimit = messageLimit,
                        )
                    } else {
                        ConversationListView(
                            activity = activity,
                            repo = repo,
                            loadedMessages = loadedMessages,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            isSearchActive = isSearchActive,
                            onSearchActiveChange = { isSearchActive = it },
                            simFilter = simFilter,
                            onSimFilterChange = { simFilter = it },
                            selectedSender = selectedSender,
                            onSelectConversation = { s, r ->
                                selectedSender = s
                                threadReceiver = r
                            },
                            onLoadMore = { messageLimit += 200 },
                            messageLimit = messageLimit,
                            changes = changes,
                            busy = busy,
                            onOpenSettings = { showSettingsDialog = true },
                            onSync = { action { syncNow(activity, manual = true); "已请求中枢同步" } },
                        )
                    }
                }
            }

            // Wavy progress bar on top during sync
            if (busy) {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            activity = activity,
            repo = repo,
            busy = busy,
            onDismissRequest = { showSettingsDialog = false },
            onRequestPermissions = { permissions, continuation ->
                val missing = permissions.filterNot { hasPermission(activity, it) }.toTypedArray()
                if (missing.isEmpty()) continuation()
                else {
                    permissionContinuation = continuation
                    permissionLauncher.launch(missing)
                }
            },
            onAction = ::action,
        )
    }
}

/**
 * 鸿枢首页会话列表视图
 */
@Composable
private fun ConversationListView(
    activity: MainActivity,
    repo: Repository,
    loadedMessages: List<CachedMessage>,
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
    LaunchedEffect(changes) {
        sims = withContext(Dispatchers.IO) {
            (repo.store.sims().map { it.phone } + repo.store.receivers()).distinct()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：
            // "信息" 标题 + 搜索放大镜 + 圆形头像 (点击弹出账号弹窗)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSearchActive) {
                    IconButton(onClick = {
                        onSearchActiveChange(false)
                        onSearchQueryChange("")
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
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

            // SIM 过滤器 (以极简药丸 FilterChip 呈现)
            if (sims.isNotEmpty()) {
                var simMenuExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        FilterChip(
                            selected = simFilter.isNotEmpty(),
                            onClick = { simMenuExpanded = true },
                            label = { Text(simFilter.ifEmpty { "全部 SIM 卡" }) },
                            leadingIcon = {
                                Icon(
                                    if (simFilter.isNotEmpty()) Icons.Default.Done else Icons.Default.SimCard,
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
            if (loadedMessages.isEmpty()) {
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
                            "短信历史将在您连接私有中枢后在此同步展现",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (repo.config.token.isEmpty()) {
                            Button(
                                onClick = onOpenSettings,
                                shape = RoundedCornerShape(20.dp),
                            ) {
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
                        )
                    }
                    if (loadedMessages.size >= messageLimit) {
                        item {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
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
 * 单条会话行（1:1 复刻截图 1）
 * 头像：正圆形彩色底（黄/绿/粉/蓝），内部为白人像图标或首字
 * 标题：发件人粗体，右对齐日期（例如 9月5日）
 * 副标题：最新内容，如果是自己发送的带 "您: " 前缀
 */
@Composable
private fun ConversationRow(
    message: CachedMessage,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val displayName = message.contact.ifEmpty { message.sender }
    val avatarBg = remember(message.sender) { avatarColorFor(message.sender) }

    Row(
        modifier = Modifier
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
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(avatarBg),
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
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

/**
 * 会话详情界面
 */
@Composable
private fun ConversationDetailPane(
    sender: String,
    contactName: String,
    messages: List<CachedMessage>,
    deviceId: String,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    messageLimit: Int,
) {
    val displayName = contactName.ifEmpty { sender }

    Column(Modifier.fillMaxSize()) {
        // 顶部导航栏
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
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
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                val isOutgoing = msg.deviceId == deviceId && msg.sender != sender
                MessageBubble(msg = msg, isOutgoing = isOutgoing)
            }
            if (messages.size >= messageLimit) {
                item {
                    TextButton(
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("加载更早历史")
                    }
                }
            }
        }
    }
}

/**
 * 现代 Material 3 不对称圆角气泡
 */
@Composable
private fun MessageBubble(
    msg: CachedMessage,
    isOutgoing: Boolean,
) {
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(22.dp, 22.dp, 4.dp, 22.dp)
    } else {
        RoundedCornerShape(22.dp, 22.dp, 22.dp, 4.dp)
    }

    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val contentColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
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
            text = "${formatDetailDateTime(msg.timestamp)} · ${if (msg.receiver.isNotEmpty()) msg.receiver else "本机"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
