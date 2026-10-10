@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val lightScheme = lightColorScheme(
                primary = Color(0xFF315B58),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFD4E7E4),
                onPrimaryContainer = Color(0xFF0D2825),
                secondary = Color(0xFF4D635C),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFD0E8DF),
                onSecondaryContainer = Color(0xFF0B1F1A),
                tertiary = Color(0xFF446379),
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFCBE6FF),
                onTertiaryContainer = Color(0xFF001E30),
                surface = Color(0xFFF7FAF7),
                onSurface = Color(0xFF181D1B),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF1F5F1),
                surfaceContainer = Color(0xFFEBEFEC),
                surfaceContainerHigh = Color(0xFFE5EAE6),
                surfaceContainerHighest = Color(0xFFDFE4E0),
                onSurfaceVariant = Color(0xFF404946),
                outline = Color(0xFF707975),
                outlineVariant = Color(0xFFBFC9C4),
            )
            MaterialExpressiveTheme(
                colorScheme = lightScheme,
                motionScheme = MotionScheme.expressive(),
            ) {
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
        Thread {
            try {
                catchUpInbox(this)
            } catch (_: Exception) {
                Config(this).status = "收件箱补扫失败，实时广播不受影响"
            }
        }.start()
        syncNow(this)
    }
}

private fun date(timestamp: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
fun HongshuUI(activity: MainActivity) {
    val repo = remember { Repository(activity) }
    DisposableEffect(Unit) { onDispose { repo.store.close() } }
    val changes by Store.changes.collectAsState()
    var currentDestination by remember { mutableStateOf("inbox") }
    var selectedSender by remember { mutableStateOf("") }
    var threadReceiver by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var simFilter by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loadedMessages by remember { mutableStateOf<List<CachedMessage>>(emptyList()) }
    var messageLimit by remember { mutableIntStateOf(200) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val configuration = LocalConfiguration.current
    val isExpanded = configuration.screenWidthDp >= 840
    val isMedium = configuration.screenWidthDp in 600..839
    val useNavRail = isMedium || isExpanded

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

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            scope.launch {
                snackbar.showSnackbar(
                    if (grants.values.all { it }) "权限已授予，可以继续操作" else "部分权限未授予，普通查看不受影响"
                )
            }
            Store.changes.value++
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

    BackHandler(currentDestination != "inbox" || selectedSender.isNotEmpty()) {
        if (currentDestination != "inbox") currentDestination = "inbox" else closeThread()
    }

    Row(Modifier.fillMaxSize()) {
        // Navigation Rail on Medium / Expanded screens
        if (useNavRail) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                header = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "枢",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
            ) {
                NavigationRailItem(
                    selected = currentDestination == "inbox",
                    onClick = {
                        currentDestination = "inbox"
                        closeThread()
                    },
                    icon = { Icon(Icons.Default.Inbox, contentDescription = "收件箱") },
                    label = { Text("收件箱") },
                )
                NavigationRailItem(
                    selected = currentDestination == "settings",
                    onClick = { currentDestination = "settings" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                    label = { Text("设置") },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { syncNow(activity) }) {
                    Icon(Icons.Default.Sync, contentDescription = "立即同步")
                }
            }
        }

        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    title = {
                        Text(
                            when {
                                currentDestination == "settings" -> "设备与连接"
                                selectedSender.isNotEmpty() && !isExpanded ->
                                    loadedMessages.firstOrNull()?.contact?.ifEmpty { selectedSender } ?: selectedSender
                                else -> "鸿枢"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        if ((currentDestination != "inbox" || selectedSender.isNotEmpty()) && (!isExpanded || currentDestination == "settings")) {
                            IconButton(onClick = {
                                if (currentDestination != "inbox") currentDestination = "inbox" else closeThread()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        if (!useNavRail) {
                            IconButton(onClick = { syncNow(activity) }) {
                                Icon(Icons.Default.Sync, contentDescription = "立即同步")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                // Navigation Bar on Compact screens
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        NavigationBarItem(
                            selected = currentDestination == "inbox",
                            onClick = {
                                currentDestination = "inbox"
                                closeThread()
                            },
                            icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                            label = { Text("收件箱") },
                        )
                        NavigationBarItem(
                            selected = currentDestination == "settings",
                            onClick = { currentDestination = "settings" },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("设备与设置") },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                // Expressive Wavy Progress Indicator during async operations
                if (busy) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }

                AnimatedContent(
                    targetState = currentDestination,
                    transitionSpec = {
                        fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                                fadeOut(spring(stiffness = Spring.StiffnessMediumLow))
                    },
                    label = "main_navigation",
                    modifier = Modifier.weight(1f),
                ) { screen ->
                    if (screen == "settings") {
                        SettingsView(activity, repo, busy, permissionLauncher::launch, ::action)
                    } else {
                        InboxContentView(
                            repo = repo,
                            loadedMessages = loadedMessages,
                            isExpanded = isExpanded,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            simFilter = simFilter,
                            onSimFilterChange = { simFilter = it },
                            selectedSender = selectedSender,
                            threadReceiver = threadReceiver,
                            onSelectConversation = { s, r ->
                                selectedSender = s
                                threadReceiver = r
                            },
                            onLoadMore = { messageLimit += 200 },
                            messageLimit = messageLimit,
                            changes = changes,
                            onNavigateSettings = { currentDestination = "settings" },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxContentView(
    repo: Repository,
    loadedMessages: List<CachedMessage>,
    isExpanded: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    simFilter: String,
    onSimFilterChange: (String) -> Unit,
    selectedSender: String,
    threadReceiver: String,
    onSelectConversation: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    messageLimit: Int,
    changes: Long,
    onNavigateSettings: () -> Unit,
) {
    if (isExpanded) {
        // Expanded Dual-Pane Master-Detail View
        Row(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                ConversationListPane(
                    repo = repo,
                    loadedMessages = loadedMessages,
                    searchQuery = searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    simFilter = simFilter,
                    onSimFilterChange = onSimFilterChange,
                    selectedSender = selectedSender,
                    onSelectConversation = onSelectConversation,
                    onLoadMore = onLoadMore,
                    messageLimit = messageLimit,
                    changes = changes,
                    onNavigateSettings = onNavigateSettings,
                )
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                if (selectedSender.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                "选择会话查看短信历史",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    MessageHistoryPane(
                        messages = loadedMessages,
                        deviceId = repo.config.deviceId,
                        onLoadMore = onLoadMore,
                        messageLimit = messageLimit,
                    )
                }
            }
        }
    } else {
        // Compact Single-Pane View
        AnimatedContent(
            targetState = selectedSender.isNotEmpty(),
            transitionSpec = {
                slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (targetState) it else -it } togetherWith
                        slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (targetState) -it else it }
            },
            label = "compact_detail_transition",
        ) { inDetail ->
            if (inDetail) {
                MessageHistoryPane(
                    messages = loadedMessages,
                    deviceId = repo.config.deviceId,
                    onLoadMore = onLoadMore,
                    messageLimit = messageLimit,
                )
            } else {
                ConversationListPane(
                    repo = repo,
                    loadedMessages = loadedMessages,
                    searchQuery = searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    simFilter = simFilter,
                    onSimFilterChange = onSimFilterChange,
                    selectedSender = selectedSender,
                    onSelectConversation = onSelectConversation,
                    onLoadMore = onLoadMore,
                    messageLimit = messageLimit,
                    changes = changes,
                    onNavigateSettings = onNavigateSettings,
                )
            }
        }
    }
}

@Composable
private fun ConversationListPane(
    repo: Repository,
    loadedMessages: List<CachedMessage>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    simFilter: String,
    onSimFilterChange: (String) -> Unit,
    selectedSender: String,
    onSelectConversation: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    messageLimit: Int,
    changes: Long,
    onNavigateSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("搜索短信与联系人") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val sims = remember(changes) {
            (repo.store.sims().map { it.phone } + repo.store.receivers()).distinct()
        }

        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var filterMenuExpanded by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = simFilter.isNotEmpty(),
                    onClick = { filterMenuExpanded = true },
                    label = { Text(simFilter.ifEmpty { "所有 SIM" }) },
                    leadingIcon = {
                        Icon(
                            if (simFilter.isNotEmpty()) Icons.Default.Done else Icons.Default.SimCard,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                )
                DropdownMenu(
                    expanded = filterMenuExpanded,
                    onDismissRequest = { filterMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("所有 SIM") },
                        onClick = {
                            onSimFilterChange("")
                            filterMenuExpanded = false
                        },
                    )
                    sims.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s) },
                            onClick = {
                                onSimFilterChange(s)
                                filterMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        Text(
            repo.config.status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        if (loadedMessages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        if (repo.config.token.isEmpty()) "连接私人短信中枢" else "暂无符合条件的短信",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "跨设备短信历史将在这里安全汇聚",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (repo.config.token.isEmpty()) {
                        Button(
                            onClick = onNavigateSettings,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("配置中枢连接")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(loadedMessages, key = { it.id }) { msg ->
                    val isSelected = selectedSender == msg.sender
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectConversation(msg.sender, msg.receiver) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(if (isSelected) 24.dp else 16.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.primaryContainer
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    (msg.contact.ifEmpty { msg.sender }).take(1),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        msg.contact.ifEmpty { msg.sender },
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(msg.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Text(
                                    listOf(msg.receiver, msg.body).filter { it.isNotEmpty() }.joinToString(" · "),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (loadedMessages.size >= messageLimit) {
                    item {
                        TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("加载更早会话")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageHistoryPane(
    messages: List<CachedMessage>,
    deviceId: String,
    onLoadMore: () -> Unit,
    messageLimit: Int,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { msg ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 1.dp,
                ) {
                    SelectionContainer {
                        Text(
                            msg.body,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    date(msg.timestamp) + " · " + msg.receiver + " · " +
                            if (msg.deviceId == deviceId) "本机" else "跨设备",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
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

@Composable
private fun SettingsView(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    permissions: (Array<String>) -> Unit,
    action: (suspend () -> String) -> Unit,
) {
    val c = repo.config
    var url by remember { mutableStateOf(c.url) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Build.MODEL) }
    var upload by remember { mutableStateOf(c.upload) }
    var notify by remember { mutableStateOf(c.notify) }
    var sub by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("SIM") }
    var candidates by remember { mutableStateOf<List<SimMapping>>(emptyList()) }
    val changes by Store.changes.collectAsState()
    val mappings = remember(changes) { repo.store.sims() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ElevatedCard(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("中枢连接", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "支持 HTTP/HTTPS，每台设备拥有独立凭据；建议生产环境使用 HTTPS 或 VPN 加密传输。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("中枢地址 http(s)://sms.example.com") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("设备名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("中枢密码") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !busy && password.isNotBlank() && c.token.isEmpty(),
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            action {
                                repo.login(url, password, name)
                                password = ""
                                syncNow(activity)
                                "连接成功"
                            }
                        },
                    ) {
                        Text("连接此设备")
                    }
                    if (c.token.isNotEmpty()) {
                        Text(
                            "已成功连接中枢；若需更换请先在原中枢撤销。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        item {
            ElevatedCard(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("同步链路控制", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("采集并上传本机短信", style = MaterialTheme.typography.bodyLarge)
                            Text("关闭时仍可查看中枢历史", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = upload,
                            onCheckedChange = { enabled ->
                                action {
                                    check(c.token.isNotEmpty()) { "请先连接中枢" }
                                    repo.api.request(
                                        "/devices/${c.deviceId}",
                                        "PATCH",
                                        JSONObject().put("upload", enabled),
                                    )
                                    upload = enabled
                                    c.upload = enabled
                                    syncNow(activity)
                                    "上传设置已保存"
                                }
                            },
                        )
                    }
                    if (upload) {
                        OutlinedButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = { permissions(arrayOf(Manifest.permission.RECEIVE_SMS)) },
                        ) {
                            Text("授予新短信广播接收权限")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("接收跨设备推送通知", style = MaterialTheme.typography.bodyLarge)
                            Text("其他设备接收新短信时提醒", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = notify,
                            onCheckedChange = { enabled ->
                                action {
                                    repo.api.request(
                                        "/devices/${c.deviceId}",
                                        "PATCH",
                                        JSONObject().put("notify", enabled),
                                    )
                                    notify = enabled
                                    c.notify = enabled
                                    if (!enabled) activity.stopService(Intent(activity, RealtimeService::class.java))
                                    "通知设置已保存"
                                }
                            },
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        OutlinedButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = { permissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                        ) {
                            Text("授予系统通知权限")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                action {
                                    check(c.token.isNotEmpty() && c.notify) { "请先连接并开启通知" }
                                    withContext(Dispatchers.Main) {
                                        ContextCompat.startForegroundService(
                                            activity,
                                            Intent(activity, RealtimeService::class.java),
                                        )
                                    }
                                    "实时连接已请求启动"
                                }
                            },
                        ) {
                            Text("启动前台实时服务")
                        }
                        OutlinedButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                activity.stopService(Intent(activity, RealtimeService::class.java))
                            },
                        ) {
                            Text("停止服务")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("SIM 接收号码确认", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    var singleSim by remember { mutableStateOf(c.singleSimConfirmed) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "确认本机仅有单张 SIM 卡（未知来源消息默认归属此号码）",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = singleSim,
                            onCheckedChange = {
                                singleSim = it
                                c.singleSimConfirmed = it
                                syncNow(activity)
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                permissions(
                                    arrayOf(
                                        Manifest.permission.READ_PHONE_STATE,
                                        Manifest.permission.READ_PHONE_NUMBERS,
                                    )
                                )
                            },
                        ) {
                            Text("授予 SIM 识别权限")
                        }
                        FilledTonalButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                action {
                                    check(
                                        ContextCompat.checkSelfPermission(
                                            activity,
                                            Manifest.permission.READ_PHONE_STATE,
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) { "请先授权 SIM 识别" }
                                    val sm = activity.getSystemService(SubscriptionManager::class.java)
                                    candidates = sm.activeSubscriptionInfoList.orEmpty().map { s ->
                                        val number = if (
                                            Build.VERSION.SDK_INT >= 33 &&
                                            ContextCompat.checkSelfPermission(
                                                activity,
                                                Manifest.permission.READ_PHONE_NUMBERS,
                                            ) == PackageManager.PERMISSION_GRANTED
                                        ) sm.getPhoneNumber(s.subscriptionId) else ""
                                        SimMapping(s.subscriptionId, s.simSlotIndex, number, s.displayName.toString())
                                    }
                                    "已获取 SIM 识别建议"
                                }
                            },
                        ) {
                            Text("自动识别建议")
                        }
                    }
                    candidates.forEach { s ->
                        val slotText = if (s.slot >= 0) "卡槽 ${s.slot + 1}" else "卡槽未知"
                        OutlinedButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                sub = s.sub.toString()
                                slot = if (s.slot >= 0) s.slot.toString() else ""
                                phone = s.phone
                                label = s.label
                            },
                        ) {
                            Text("${s.label} · $slotText · ${s.phone.ifEmpty { "号码未知" }}")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sub,
                            onValueChange = { sub = it },
                            label = { Text("订阅 ID") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = slot,
                            onValueChange = { slot = it },
                            label = { Text("卡槽 0/1") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("确认的接收号码") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("SIM 标签") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            action {
                                check(confirmedNumber(phone)) { "请填写正确号码，建议国际格式" }
                                val mapping = SimMapping(
                                    sub.toIntOrNull() ?: -1,
                                    slot.toIntOrNull() ?: -1,
                                    phone,
                                    label,
                                )
                                check(c.token.isNotEmpty()) { "请先连接中枢" }
                                repo.api.request(
                                    "/sims",
                                    "PUT",
                                    JSONObject()
                                        .put("phone", phone)
                                        .put("label", label)
                                        .put("subscription_id", mapping.sub),
                                )
                                repo.store.saveSim(mapping)
                                syncNow(activity)
                                "号码配置已确认并保存"
                            }
                        },
                    ) {
                        Text("保存并绑定 SIM")
                    }
                    mappings.forEach { s ->
                        Text("${s.label}: ${s.phone} · sub ${s.sub} · slot ${s.slot}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            ElevatedCard(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("历史与通讯录导入", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = { permissions(arrayOf(Manifest.permission.READ_SMS)) },
                        ) {
                            Text("授予历史读取权限")
                        }
                        Button(
                            shape = RoundedCornerShape(12.dp),
                            enabled = !busy && upload,
                            onClick = { action { "已排队 ${importHistory(activity)} 条历史记录" } },
                        ) {
                            Text("导入历史短信")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            shape = RoundedCornerShape(12.dp),
                            onClick = { permissions(arrayOf(Manifest.permission.READ_CONTACTS)) },
                        ) {
                            Text("授予通讯录权限")
                        }
                        OutlinedButton(
                            shape = RoundedCornerShape(12.dp),
                            enabled = !busy,
                            onClick = { action { "已同步 ${importContacts(activity)} 个联系人" } },
                        ) {
                            Text("同步联系人名称")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地中枢状态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(c.status, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "待上传 ${repo.store.pendingCount()} 条 · 异常保留 ${repo.store.blockedCount()} 条 · 最近同步 ${if (c.lastSync == 0L) "尚未同步" else date(c.lastSync)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            repo.store.retryBlocked()
                            syncNow(activity)
                        },
                    ) {
                        Text("重试上传与补齐")
                    }
                }
            }
        }
    }
}
