@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.hongshu.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

typealias PermissionRequest = (Array<String>, () -> Unit) -> Unit

@Composable
fun SettingsDialog(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onRequestPermissions: PermissionRequest,
    onAction: (suspend () -> String) -> Unit,
) {
    var page by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    // Preferences can also change from a worker/service while the dialog is open.
    val refresh = changes + tick
    BackHandler { if (page != null) page = null else onDismissRequest() }
    Dialog(
        onDismissRequest,
        DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (page != null)
                        IconButton(onClick = { page = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    else Spacer(Modifier.size(48.dp))
                    Text(
                        when (page) {
                            "hub" -> "管理中枢与凭据"
                            "sims" -> "SIM 卡与号码识别"
                            "sync" -> "实时广播与同步"
                            "about" -> "关于鸿枢"
                            else -> "中枢与设置"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onDismissRequest) { Icon(Icons.Default.Close, "关闭") }
                }
                Box(Modifier.weight(1f)) {
                    when (page) {
                        "hub" -> HubLoginSubPage(repo, busy, settingsAction) { page = null }
                        "sims" ->
                            SimSettingsSubPage(
                                activity,
                                repo,
                                busy,
                                onRequestPermissions,
                                settingsAction,
                            )
                        "sync" ->
                            SyncControlSubPage(
                                activity,
                                repo,
                                busy,
                                refresh,
                                onRequestPermissions,
                                settingsAction,
                            )
                        "about" -> AboutSubPage()
                        else -> SettingsMainOverview(repo, refresh) { page = it }
                    }
                    SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsMainOverview(repo: Repository, refresh: Long, onNavigate: (String) -> Unit) {
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
                        onClick = { onNavigate("hub") },
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
                    SheetActionRow(Icons.Default.SimCard, "SIM 卡与号码识别") { onNavigate("sims") }
                    HorizontalDivider()
                    SheetActionRow(Icons.Default.Sensors, "实时广播与同步") { onNavigate("sync") }
                    HorizontalDivider()
                    SheetActionRow(Icons.Default.Info, "关于鸿枢") { onNavigate("about") }
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

    LaunchedEffect(automatic) {
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
) {
    val c = remember(refresh) { repo.config }
    val health by RuntimeHealth.connection.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SettingSwitch("同步本机短信", c.upload, !busy) { enabled ->
                onAction {
                    check(c.token.isNotEmpty()) { "请先连接中枢" }
                    repo.api.request(
                        "/devices/${c.deviceId}",
                        "PATCH",
                        JSONObject().put("upload", enabled),
                    )
                    c.upload = enabled
                    c.serverUploadAllowed = enabled
                    if (enabled)
                        withContext(Dispatchers.Main) {
                            onRequestPermissions(
                                arrayOf(
                                    Manifest.permission.READ_SMS,
                                    Manifest.permission.RECEIVE_SMS,
                                )
                            ) {
                                syncNow(activity, manual = true)
                            }
                        }
                    syncNow(activity, manual = true)
                    if (enabled) "已开启，历史短信将自动同步" else "本机短信同步已关闭"
                }
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
        item { StatusRow("短信同步状态", smsSyncStatus(activity, repo)) }
        item { StatusRow("后台保活状态", keepAliveStatus(activity, c, health)) }
        item { StatusRow("通知接收状态", notificationStatus(activity, c, health)) }
        item {
            SettingSwitch("后台保活开关", c.keepAlive, !busy) { enabled ->
                onAction {
                    check(!enabled || c.token.isNotEmpty()) { "请先连接中枢" }
                    withContext(Dispatchers.Main) {
                        if (enabled) {
                            c.keepAlive = true
                            try {
                                ContextCompat.startForegroundService(
                                    activity,
                                    Intent(activity, RealtimeService::class.java),
                                )
                            } catch (e: Exception) {
                                c.keepAlive = false
                                throw e
                            }
                        } else {
                            c.keepAlive = false
                            activity.stopService(Intent(activity, RealtimeService::class.java))
                        }
                    }
                    if (enabled) "后台保活已启用，实际状态见上方" else "后台保活已关闭"
                }
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
