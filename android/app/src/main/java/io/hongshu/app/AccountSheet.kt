@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.hongshu.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

typealias PermissionRequest = (Array<String>, () -> Unit) -> Unit

@Composable
fun SettingsScreen(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    page: SettingsPage,
    onNavigate: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onRequestPermissions: PermissionRequest,
    onAction: (suspend () -> String) -> Unit,
) {
    val feedback = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val settingsAction: (suspend () -> String) -> Unit = { task ->
        onAction {
            try {
                val message = task()
                withContext(Dispatchers.Main) { scope.launch { feedback.showSnackbar(message) } }
                message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    scope.launch { feedback.showSnackbar(e.message ?: "保存失败") }
                }
                throw e
            }
        }
    }
    var tick by remember { mutableLongStateOf(0) }
    val changes by Store.changes.collectAsState()
    LaunchedEffect(activity) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1000)
                tick++
            }
        }
    }
    // Preferences can also change from a worker/service while the settings page is open.
    val refresh = changes + tick
    Surface(
        Modifier.fillMaxSize().imePadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (page != SettingsPage.Overview) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                } else Spacer(Modifier.size(48.dp))
                Text(page.title, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭") }
            }
            Box(Modifier.weight(1f).navigationBarsPadding()) {
                when (page) {
                    SettingsPage.Hub -> HubLoginSubPage(repo, busy, settingsAction, onBack)
                    SettingsPage.Sims ->
                        SimSettingsSubPage(
                            activity,
                            repo,
                            busy,
                            onRequestPermissions,
                            settingsAction,
                        )
                    SettingsPage.Pending -> LocalPendingSubPage(repo, refresh) {
                        onNavigate(SettingsPage.Sims)
                    }
                    SettingsPage.Sync ->
                        SyncControlSubPage(
                            activity,
                            repo,
                            busy,
                            refresh,
                            onRequestPermissions,
                            settingsAction,
                        ) {
                            onNavigate(SettingsPage.Pending)
                        }
                    SettingsPage.About -> AboutSubPage()
                    SettingsPage.Overview -> SettingsMainOverview(repo, refresh, onNavigate)
                }
                SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsMainOverview(
    repo: Repository,
    refresh: Long,
    onNavigate: (SettingsPage) -> Unit,
) {
    val c = remember(refresh) { repo.config }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            Icons.Default.Smartphone,
                            null,
                            Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                c.deviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (c.token.isNotEmpty()) c.url else "尚未连接中枢",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { onNavigate(SettingsPage.Hub) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (c.token.isNotEmpty()) "管理中枢服务器与凭据" else "连接您的私有短信中枢")
                    }
                }
            }
        }
        item {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column {
                    SheetActionRow(Icons.Default.SimCard, "SIM 卡与号码识别") {
                        onNavigate(SettingsPage.Sims)
                    }
                    HorizontalDivider()
                    SheetActionRow(Icons.Default.Sensors, "实时广播与同步") {
                        onNavigate(SettingsPage.Sync)
                    }
                    HorizontalDivider()
                    SheetActionRow(Icons.Default.Outbox, "本机待同步短信") {
                        onNavigate(SettingsPage.Pending)
                    }
                    HorizontalDivider()
                    SheetActionRow(Icons.Default.Info, "关于鸿枢") {
                        onNavigate(SettingsPage.About)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetActionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HubLoginSubPage(
    repo: Repository,
    busy: Boolean,
    onAction: (suspend () -> String) -> Unit,
    onSuccess: () -> Unit,
) {
    val c = repo.config
    var url by remember { mutableStateOf(c.url) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(c.deviceName) }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Text("建议使用 HTTPS；已连接时留空密码可直接保存设备名称。", style = MaterialTheme.typography.bodyMedium) }
        item {
            OutlinedTextField(
                url,
                { url = it },
                label = { Text("中枢地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("设备名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("中枢密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                enabled =
                    !busy &&
                        url.isNotBlank() &&
                        name.isNotBlank() &&
                        (c.token.isNotEmpty() || password.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onAction {
                        val previousDevice = c.deviceId
                        repo.saveConnection(url, password, name)
                        withContext(Dispatchers.Main) {
                            if (previousDevice != c.deviceId) {
                                val service = Intent(repo.context, RealtimeService::class.java)
                                repo.context.stopService(service)
                                if (c.keepAlive) {
                                    try {
                                        ContextCompat.startForegroundService(repo.context, service)
                                    } catch (_: Exception) {
                                        RuntimeHealth.connection.value =
                                            ConnectionHealth(detail = "设置已保存，但后台服务启动失败")
                                    }
                                }
                            }
                            password = ""
                            onSuccess()
                        }
                        "中枢设置与设备名称已保存"
                    }
                },
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun SimSettingsSubPage(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    onRequestPermissions: PermissionRequest,
    onAction: (suspend () -> String) -> Unit,
) {
    var automatic by remember { mutableStateOf(repo.config.automaticSim) }
    var phone1 by remember { mutableStateOf("") }
    var phone2 by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<SimMapping>>(emptyList()) }
    var scanStatus by remember { mutableStateOf("等待识别") }
    var initialized by remember { mutableStateOf(false) }

    fun scan(fill: Boolean) {
        onRequestPermissions(
            arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
        ) {
            onAction {
                val next = scanSims(activity)
                withContext(Dispatchers.Main) {
                    candidates = next
                    scanStatus = if (next.isEmpty()) "未检测到 SIM 卡" else ""
                    if (fill) {
                        phone1 = next.find { it.slot == 0 }?.phone.orEmpty()
                        phone2 = next.find { it.slot == 1 }?.phone.orEmpty()
                    }
                }
                "扫描完成；无法识别的号码不会自动填入"
            }
        }
    }

    val lifecycleState by activity.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(automatic, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        while (true) {
            if (hasPermission(activity, Manifest.permission.READ_PHONE_STATE)) {
                val next =
                    withContext(Dispatchers.IO) { runCatching { scanSims(activity) }.getOrNull() }
                if (next != null) {
                    candidates = next
                    scanStatus = if (next.isEmpty()) "未检测到 SIM 卡" else ""
                    if (!initialized) {
                        val saved = withContext(Dispatchers.IO) { repo.store.sims() }
                        phone1 =
                            next
                                .find { it.slot == 0 }
                                ?.let { s -> saved.find { it.sub == s.sub }?.phone }
                                .orEmpty()
                        phone2 =
                            next
                                .find { it.slot == 1 }
                                ?.let { s -> saved.find { it.sub == s.sub }?.phone }
                                .orEmpty()
                        initialized = true
                    }
                } else scanStatus = "SIM 识别失败"
            } else scanStatus = "缺少 SIM 识别权限"
            if (!automatic) break
            delay(3000)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("手动识别", "自动识别").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = automatic == (index == 1),
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        enabled = !busy,
                        onClick = {
                            automatic = index == 1
                            if (automatic) scan(false)
                        },
                    ) {
                        Text(label)
                    }
                }
            }
        }
        if (!automatic) {
            item {
                OutlinedTextField(
                    phone1,
                    { phone1 = it },
                    label = { Text("SIM1 手机号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    phone2,
                    { phone2 = it },
                    label = { Text("SIM2 手机号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FilledTonalButton(
                    enabled = !busy,
                    onClick = { scan(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("自动扫描并填入号码")
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (slot in 0..1) {
                        val sim = candidates.find { it.slot == slot }
                        Text(
                            "SIM${slot + 1}：" +
                                when {
                                    scanStatus.isNotEmpty() -> scanStatus
                                    sim == null -> "未插卡"
                                    sim.phone.isEmpty() -> "未识别到手机号"
                                    else -> "已匹配 ${sim.phone}"
                                }
                        )
                    }
                }
            }
        }
        item {
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val permissions =
                        if (automatic)
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_PHONE_NUMBERS,
                            )
                        else arrayOf(Manifest.permission.READ_PHONE_STATE)
                    onRequestPermissions(permissions) {
                        onAction {
                            val active = scanSims(activity)
                            val mappings =
                                if (automatic) {
                                    check(active == candidates) { "SIM 匹配已变化，请核对后重新保存" }
                                    active
                                } else manualSimMappings(active, listOf(phone1, phone2))
                            saveSimSelection(activity, automatic, mappings)
                            SimMonitor.start(activity)
                            syncNow(activity, manual = true)
                            if (automatic) "自动识别已保存，后续换卡或换号将自动更新" else "手机号已保存"
                        }
                    }
                },
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun SyncControlSubPage(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    refresh: Long,
    onRequestPermissions: PermissionRequest,
    onAction: (suspend () -> String) -> Unit,
    onPending: () -> Unit,
) {
    val c = remember(refresh) { repo.config }
    val health by RuntimeHealth.connection.collectAsState()
    var diagnostics by remember { mutableStateOf(CaptureDiagnostics()) }
    var queued by remember { mutableIntStateOf(0) }
    var unresolved by remember { mutableIntStateOf(0) }
    var invalid by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            val stats = repo.store.captureDiagnostics()
            val count = repo.store.pendingCount()
            val unknown = repo.store.unresolvedCount()
            val blocked = repo.store.blockedCount()
            withContext(Dispatchers.Main) {
                diagnostics = stats
                queued = count
                unresolved = unknown
                invalid = blocked
            }
        }
    }
    var confirmHide by remember { mutableStateOf(false) }

    fun setRealtime(enabled: Boolean) {
        onAction {
            check(!enabled || c.token.isNotEmpty()) { "请先连接中枢" }
            withContext(Dispatchers.Main) {
                c.keepAlive = enabled
                if (enabled) {
                    if (!startRealtimeIfEnabled(activity)) {
                        c.keepAlive = false
                        error("实时服务启动失败；周期同步仍启用")
                    }
                } else activity.stopService(Intent(activity, RealtimeService::class.java))
            }
            if (enabled) "后台实时连接已启用，实际状态见上方" else "实时连接已关闭；周期同步仍启用"
        }
    }

    fun setHidden(hidden: Boolean) {
        onAction {
            withContext(Dispatchers.Main) {
                check(updateRecentTaskVisibility(activity, hidden)) { "系统不支持修改当前任务显示状态" }
                c.hideFromRecents = hidden
            }
            if (hidden) "最近任务已隐藏，可从桌面图标或通知重新打开" else "当前任务已恢复显示"
        }
    }

    if (confirmHide) {
        AlertDialog(
            onDismissRequest = { confirmHide = false },
            title = { Text("隐藏最近任务卡片") },
            text = {
                Text("隐藏后仍可从桌面图标或连接通知打开应用。若手机需要任务加锁，请先在最近任务中加锁，再开启隐藏。隐藏不是保活授权，也不能保证厂商保留锁定保护。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmHide = false
                        setHidden(true)
                    }
                ) {
                    Text("开启隐藏")
                }
            },
            dismissButton = { TextButton(onClick = { confirmHide = false }) { Text("取消") } },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingSwitch("同步本机短信", c.upload, !busy) { enabled ->
                onAction {
                    check(!enabled || c.token.isNotEmpty()) { "请先连接中枢" }
                    c.upload = enabled
                    if (enabled)
                        withContext(Dispatchers.Main) {
                            onRequestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS)) {
                                syncNow(activity, manual = true)
                            }
                        }
                    try {
                        repo.api.request(
                            "/devices/${c.deviceId}",
                            "PATCH",
                            JSONObject().put("upload", enabled),
                        )
                        c.serverUploadAllowed = enabled
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        c.syncHealthy = false
                        c.status = "本机采集设置已保存 · 中枢设置未确认，请联网后重试"
                    }
                    syncNow(activity, manual = true)
                    if (enabled) "实时采集已开启；历史读取单独设置" else "采集已关闭，已有待传记录保留"
                }
            }
        }
        item {
            Text(
                "实时广播只需接收短信权限，但系统可能不向鸿枢投递部分验证码。可开启新短信补漏；拒绝读取权限仍可保存实际收到的广播。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (c.upload && !hasPermission(activity, Manifest.permission.RECEIVE_SMS))
            item {
                FilledTonalButton(
                    onClick = {
                        onRequestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS)) {
                            syncNow(activity)
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("授予实时短信权限")
                }
            }
        item {
            SettingSwitch("新短信补漏", c.recoveryEnabled, !busy) { enabled ->
                onAction {
                    c.recoveryEnabled = enabled
                    if (enabled)
                        withContext(Dispatchers.Main) {
                            onRequestPermissions(arrayOf(Manifest.permission.READ_SMS)) {
                                syncNow(activity, manual = true)
                            }
                        }
                    syncNow(activity, manual = true)
                    if (enabled) "新短信补漏已开启 · 只读取开启后的短信，不开启全量历史" else "新短信补漏已关闭，已收到的广播仍会保存"
                }
            }
        }
        item {
            Text(
                "补漏需系统允许读取短信；重新进入应用及周期任务会补扫，后台可能延迟。补漏新增 0 条不代表验证码可读；开启前遗漏的短信需使用历史补齐。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (c.upload && c.recoveryEnabled && !hasPermission(activity, Manifest.permission.READ_SMS))
            item {
                FilledTonalButton(
                    onClick = {
                        onRequestPermissions(arrayOf(Manifest.permission.READ_SMS)) {
                            syncNow(activity, manual = true)
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("授予新短信补漏权限")
                }
            }
        item {
            FilledTonalButton(
                enabled = !busy && c.upload && (c.recoveryEnabled || c.historyEnabled),
                onClick = {
                    onAction {
                        val result =
                            if (c.recoveryEnabled) recoverRecentInbox(activity)
                            else catchUpInbox(activity)
                        if (c.recoveryEnabled) c.recoveryStatus = result
                        else c.captureStatus = result
                        syncNow(activity, manual = true).await()
                        result
                    }
                },
            ) {
                Text("立即读取并检查收件箱")
            }
        }
        item {
            SettingSwitch("补齐历史短信", c.historyEnabled, !busy) { enabled ->
                onAction {
                    c.historyEnabled = enabled
                    if (enabled)
                        withContext(Dispatchers.Main) {
                            onRequestPermissions(arrayOf(Manifest.permission.READ_SMS)) {
                                syncNow(activity, manual = true)
                            }
                        }
                    syncNow(activity, manual = true)
                    if (enabled) "历史读取已开启，权限和状态见下方" else "历史读取已关闭，实时采集不受影响"
                }
            }
        }
        if (c.upload && c.historyEnabled && !hasPermission(activity, Manifest.permission.READ_SMS))
            item {
                FilledTonalButton(
                    onClick = {
                        onRequestPermissions(arrayOf(Manifest.permission.READ_SMS)) {
                            syncNow(activity, manual = true)
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("授予历史读取权限")
                }
            }
        item {
            SettingSwitch("接收全部通知", c.notify, !busy) { enabled ->
                onAction {
                    check(c.token.isNotEmpty()) { "请先连接中枢" }
                    repo.api.request(
                        "/devices/${c.deviceId}",
                        "PATCH",
                        JSONObject().put("notify", enabled),
                    )
                    c.notify = enabled
                    if (enabled && Build.VERSION.SDK_INT >= 33)
                        withContext(Dispatchers.Main) {
                            onRequestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) {
                                syncNow(activity)
                            }
                        }
                    syncNow(activity)
                    "通知设置已保存"
                }
            }
        }
        item { StatusRow("实时短信采集", realtimeSmsStatus(activity, diagnostics)) }
        item { StatusRow("系统短信权限与访问策略", smsPermissionStatus(activity)) }
        item { StatusRow("新短信收件箱补漏", recoverySmsStatus(activity)) }
        item { StatusRow("历史短信读取", historySmsStatus(activity)) }
        item { StatusRow("本地队列", "$queued 条待同步 · $unresolved 条待确认号码 · $invalid 条被阻塞") }
        item { FilledTonalButton(onClick = onPending) { Text("查看本机待同步短信") } }
        item { StatusRow("中枢同步状态", smsSyncStatus(activity, repo)) }
        item { StatusRow("后台保活状态", keepAliveStatus(activity, c, health)) }
        item { StatusRow("通知接收状态", notificationStatus(activity, c, health)) }
        item { StatusRow("实时连接通知状态", connectionNotificationStatus(activity)) }
        item {
            SettingSwitch("后台保活开关", c.keepAlive, !busy) { enabled ->
                if (enabled && c.token.isNotEmpty() && Build.VERSION.SDK_INT >= 33)
                    onRequestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) {
                        setRealtime(true)
                    }
                else setRealtime(enabled)
            }
        }
        item {
            SettingSwitch("不在最近任务列表显示", c.hideFromRecents, !busy) { hidden ->
                if (hidden) confirmHide = true else setHidden(false)
            }
        }
        item {
            SettingSwitch("开机恢复实时连接", c.restoreRealtimeOnBoot, !busy) { enabled ->
                onAction {
                    c.restoreRealtimeOnBoot = enabled
                    if (enabled) "已保存；需同时开启后台实时连接，恢复仍受系统限制" else "开机不再恢复实时连接"
                }
            }
        }
        item {
            Text(
                "首页返回只退出界面，不停止实时连接。隐藏任务与后台保活相互独立。请在系统中允许后台运行和自启动；无法自动检测厂商任务锁。强制停止后需重新打开应用。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onAction {
                        withContext(Dispatchers.Main) {
                            check(openBackgroundSettings(activity, battery = true)) { "无法打开系统设置" }
                        }
                        "请在系统电池优化列表允许鸿枢后台运行，返回后显示实际状态"
                    }
                },
            ) {
                Text("电池优化设置")
            }
        }
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onAction {
                        withContext(Dispatchers.Main) {
                            check(openBackgroundSettings(activity, battery = false)) { "无法打开系统设置" }
                        }
                        "请按手机系统配置后台运行、自启动和通知权限"
                    }
                },
            ) {
                Text("应用信息与后台权限")
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun StatusRow(title: String, status: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutSubPage() {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("鸿枢 (Hongshu)", style = MaterialTheme.typography.headlineSmall)
        Text("跨设备私有短信同步与聚合平台")
        Text("Go / MySQL 中枢 · Android · Web / PWA")
    }
}
