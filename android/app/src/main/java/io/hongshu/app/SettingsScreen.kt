@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Requests runtime permissions and reports whether all of them were granted. Callers decide
 * what a refusal means, so a switch never ends up on without the access it needs.
 */
typealias PermissionRequest = (Array<String>, (Boolean) -> Unit) -> Unit

/** Everything a settings page needs from its host, passed as one value. */
class SettingsHost(
    val activity: ComponentActivity,
    val repo: Repository,
    val busy: Boolean,
    val refresh: Long,
    val facts: SetupFacts,
    val navigate: (SettingsPage) -> Unit,
    val back: () -> Unit,
    val requestPermissions: PermissionRequest,
    /** Runs [task] off the main thread with the busy indicator and shows its result. */
    val action: (suspend () -> String) -> Unit,
    val message: (String) -> Unit,
) {
    val config: Config
        get() = repo.config
}

@Composable
fun SettingsScreen(
    activity: ComponentActivity,
    repo: Repository,
    busy: Boolean,
    page: SettingsPage,
    onNavigate: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onRequestPermissions: PermissionRequest,
    onAction: (suspend () -> String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val changes by Store.changes.collectAsState()
    val health by RuntimeHealth.connection.collectAsState()
    // Preferences can also change from a worker/service while the settings page is open; system
    // permission state changes while the user is away in system settings.
    val refresh = changes + rememberPreferenceVersion(activity) + rememberResumeCount()
    val pending by
        produceState(PendingCounts(), refresh) {
            value = withContext(Dispatchers.IO) { repo.store.pendingCounts() }
        }
    val facts = remember(refresh, health, pending) { setupFacts(activity, repo.config, health, pending) }
    val host =
        SettingsHost(
            activity,
            repo,
            busy,
            refresh,
            facts,
            onNavigate,
            onBack,
            onRequestPermissions,
            onAction,
            onMessage,
        )
    when (page) {
        SettingsPage.Overview -> SettingsOverviewPage(host)
        SettingsPage.Hub -> HubPage(host)
        SettingsPage.Capture -> CapturePage(host)
        SettingsPage.Sims -> SimsPage(host)
        SettingsPage.Notifications -> NotificationsPage(host)
        SettingsPage.Background -> BackgroundPage(host)
        SettingsPage.Pending -> LocalPendingPage(host)
        SettingsPage.Diagnostics -> DiagnosticsPage(host)
        SettingsPage.About -> AboutPage(host)
    }
}

internal fun appVersion(activity: ComponentActivity): String =
    runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }
        .getOrNull()
        .orEmpty()

@Composable
private fun SettingsOverviewPage(host: SettingsHost) {
    val c = host.config
    val f = host.facts
    val attention = attentionItems(f)
    SettingsScaffold(SettingsPage.Overview.title, host.back, host.busy) {
        settingsItem("health") {
            HealthCard(
                deviceName = c.deviceName,
                hub = if (f.connected) c.url else "尚未连接中枢",
                attention = attention,
                onOpen = host.navigate,
            )
        }
        settingsGroup(
            "hub",
            "中枢",
            listOf(
                SettingsRow("hub") {
                    SettingsItem(
                        title = "中枢连接",
                        summary = if (f.connected) "${c.deviceName} · ${c.url}" else "连接你的私有短信中枢",
                        icon = Icons.Default.Dns,
                        status = hubStatus(f),
                        onClick = { host.navigate(SettingsPage.Hub) },
                    )
                }
            ),
        )
        settingsGroup(
            "phone",
            "这台手机",
            listOf(
                SettingsRow("capture") {
                    SettingsItem(
                        title = "短信采集",
                        summary = "把这台手机收到的短信上传到中枢",
                        icon = Icons.Default.Sms,
                        status = captureStatus(f),
                        onClick = { host.navigate(SettingsPage.Capture) },
                    )
                },
                SettingsRow("sims") {
                    SettingsItem(
                        title = "SIM 卡与号码",
                        summary = if (c.automaticSim) "自动识别号码" else "手动填写号码",
                        icon = Icons.Default.SimCard,
                        showChevron = true,
                        onClick = { host.navigate(SettingsPage.Sims) },
                    )
                },
                SettingsRow("pending") {
                    SettingsItem(
                        title = "待上传短信",
                        summary =
                            if (f.pending.total == 0) "没有等待上传的短信"
                            else "${f.pending.total} 条保留在本机，等待上传",
                        icon = Icons.Default.Outbox,
                        showChevron = true,
                        onClick = { host.navigate(SettingsPage.Pending) },
                    )
                },
            ),
        )
        settingsGroup(
            "app",
            "提醒与后台",
            listOf(
                SettingsRow("notifications") {
                    SettingsItem(
                        title = "通知",
                        summary = "其他设备的新短信到达时提醒",
                        icon = Icons.Default.Notifications,
                        status = notificationsStatus(f),
                        onClick = { host.navigate(SettingsPage.Notifications) },
                    )
                },
                SettingsRow("background") {
                    SettingsItem(
                        title = "后台运行",
                        summary = "保持实时连接、开机恢复",
                        icon = Icons.Default.BatteryChargingFull,
                        status = backgroundStatus(f),
                        onClick = { host.navigate(SettingsPage.Background) },
                    )
                },
            ),
        )
        settingsGroup(
            "more",
            null,
            listOf(
                SettingsRow("diagnostics") {
                    SettingsItem(
                        title = "诊断信息",
                        summary = "各项采集与连接的详细状态，用于排查问题",
                        icon = Icons.Default.MonitorHeart,
                        showChevron = true,
                        onClick = { host.navigate(SettingsPage.Diagnostics) },
                    )
                },
                SettingsRow("about") {
                    SettingsItem(
                        title = "关于鸿枢",
                        summary = "版本 ${appVersion(host.activity)}",
                        icon = Icons.Default.Info,
                        showChevron = true,
                        onClick = { host.navigate(SettingsPage.About) },
                    )
                },
            ),
        )
    }
}

/** Summary card at the top of settings: which device this is and what needs attention. */
@Composable
private fun HealthCard(
    deviceName: String,
    hub: String,
    attention: List<Attention>,
    onOpen: (SettingsPage) -> Unit,
) {
    val ok = attention.isEmpty()
    val container by
        animateColorAsState(
            if (ok) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.tertiaryContainer,
            label = "health container",
        )
    val content =
        if (ok) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier.size(56.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(content.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Smartphone, contentDescription = null, Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(hub, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
            AnimatedContent(
                targetState = ok,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "health summary",
            ) { allGood ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (allGood) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        if (allGood) "一切正常" else "有 ${attention.size} 项需要处理",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            AnimatedVisibility(
                visible = !ok,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    attention.forEachIndexed { index, item ->
                        Surface(
                            onClick = { onOpen(item.page) },
                            shape = segmentShape(index, attention.size),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        item.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubPage(host: SettingsHost) {
    val c = host.config
    var editing by rememberSaveable { mutableStateOf(!host.facts.connected) }
    SettingsScaffold(SettingsPage.Hub.title, host.back, host.busy) {
        if (!editing) {
            settingsItem("connected") {
                ConnectedHubCard(
                    url = c.url,
                    deviceName = c.deviceName,
                    status = smsSyncStatusOrConnected(host),
                    level = hubStatus(host.facts),
                    busy = host.busy,
                    onEdit = { editing = true },
                    onSync = {
                        host.action {
                            if (syncAndWait(host.activity)) "同步完成"
                            else "同步已排队，联网后会自动完成"
                        }
                    },
                )
            }
            settingsItem("help") {
                ExpandableHelp(
                    "更换中枢前，本机未上传的短信必须先上传完成，以免发到错误的中枢。修改设备名称不需要重新输入密码。"
                )
            }
        } else {
            settingsItem("form") {
                HubForm(host, onCancel = if (host.facts.connected) ({ editing = false }) else null)
            }
        }
    }
}

private fun smsSyncStatusOrConnected(host: SettingsHost): String =
    if (!host.config.upload) {
        if (host.facts.syncHealthy) "已连接 · ${host.config.status}" else host.config.status
    } else smsSyncStatus(host.activity, host.repo)

@Composable
private fun ConnectedHubCard(
    url: String,
    deviceName: String,
    status: String,
    level: Pair<StatusLevel, String>,
    busy: Boolean,
    onEdit: () -> Unit,
    onSync: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Dns)
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(url, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        "本机名称：$deviceName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(level.first, level.second)
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("修改连接")
                }
                FilledTonalButton(onClick = onSync, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("立即同步")
                }
            }
        }
    }
}

@Composable
private fun HubForm(host: SettingsHost, onCancel: (() -> Unit)?) {
    val c = host.config
    val connected = host.facts.connected
    var url by rememberSaveable { mutableStateOf(c.url) }
    var name by rememberSaveable { mutableStateOf(c.deviceName) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<String?>(null) }
    val urlError =
        url.isNotBlank() && runCatching { hubOrigin(url.trim()) }.isFailure
    val sameHub = connected && runCatching { hubOrigin(url.trim()) == c.url }.getOrDefault(false)
    val needsPassword = !sameHub
    val canSave =
        !host.busy && url.isNotBlank() && !urlError && name.isNotBlank() &&
            (!needsPassword || password.isNotEmpty())

    fun save() {
        host.action {
            val previousDevice = c.deviceId
            host.repo.saveConnection(url, password, name)
            withContext(Dispatchers.Main) {
                if (previousDevice != c.deviceId) {
                    val service = Intent(host.activity, RealtimeService::class.java)
                    host.activity.stopService(service)
                    if (c.keepAlive) {
                        try {
                            ContextCompat.startForegroundService(host.activity, service)
                        } catch (_: Exception) {
                            RuntimeHealth.connection.value =
                                ConnectionHealth(detail = "设置已保存，但后台服务启动失败")
                        }
                    }
                }
                password = ""
                host.back()
            }
            if (sameHub) "设备名称已保存" else "已连接到中枢"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (connected) "修改中枢地址需要重新输入密码；只改设备名称时可以留空。"
            else "输入你部署的鸿枢中枢地址和管理密码。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        OutlinedTextField(
            url,
            {
                url = it
                probe = null
            },
            label = { Text("中枢地址") },
            placeholder = { Text("https://sms.example.com") },
            singleLine = true,
            isError = urlError,
            supportingText = {
                Text(
                    when {
                        urlError -> "需以 http:// 或 https:// 开头，且不含路径"
                        url.trim().startsWith("http://", ignoreCase = true) ->
                            "HTTP 不加密，建议只在局域网内使用"
                        else -> "例如 https://sms.example.com"
                    }
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            name,
            { name = it },
            label = { Text("本机名称") },
            supportingText = { Text("在中枢和其他设备上显示的名称") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("中枢密码") },
            singleLine = true,
            supportingText =
                if (!needsPassword) ({ Text("已连接此中枢，只改名称时可以留空") }) else null,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) save() }),
            visualTransformation =
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(probe != null) {
            NoticeCard(
                icon = Icons.Default.Info,
                title = probe.orEmpty(),
                attention = probe?.startsWith("中枢可以访问") == false,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val target = url
                    host.action {
                        val result = probeHub(target)
                        withContext(Dispatchers.Main) { probe = result }
                        result
                    }
                },
                enabled = !host.busy && url.isNotBlank() && !urlError,
                modifier = Modifier.weight(1f),
            ) {
                Text("测试连接")
            }
            Button(onClick = ::save, enabled = canSave, modifier = Modifier.weight(1f)) {
                Text(if (sameHub) "保存" else "连接")
            }
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("取消") }
        }
    }
}

@Composable
private fun DiagnosticsPage(host: SettingsHost) {
    val activity = host.activity
    val c = host.config
    val health by RuntimeHealth.connection.collectAsState()
    val diagnostics by
        produceState(CaptureDiagnostics(), host.refresh) {
            value = withContext(Dispatchers.IO) { host.repo.store.captureDiagnostics() }
        }
    val vendor by
        produceState<String?>(null, host.refresh) {
            value = withContext(Dispatchers.IO) { vendorSmsStatus(activity) }
        }
    val capture =
        buildList {
            add("实时接收" to realtimeSmsStatus(activity, diagnostics))
            add("短信权限与系统策略" to smsPermissionStatus(activity))
            add("漏收检查" to recoverySmsStatus(activity))
            add("历史补齐" to historySmsStatus(activity))
            add("从通知读取" to notificationCaptureStatus(activity))
            vendor?.let { add("澎湃 OS 短信授权" to it) }
        }
    val sync =
        listOf(
            "本地队列" to host.facts.pending.label(),
            "中枢同步" to smsSyncStatus(activity, host.repo),
        )
    val background =
        listOf(
            "实时连接" to keepAliveStatus(activity, c, health),
            "新短信通知" to notificationStatus(activity, c, health),
            "实时连接通知" to connectionNotificationStatus(activity),
        )
    SettingsScaffold(SettingsPage.Diagnostics.title, host.back, host.busy) {
        for ((key, title, lines) in
            listOf(
                Triple("capture", "短信采集", capture),
                Triple("sync", "同步", sync),
                Triple("background", "后台与通知", background),
            )) {
            settingsGroup(
                key,
                title,
                lines.map { (label, text) ->
                    SettingsRow(label) { SettingsItem(title = label, summary = text) }
                },
            )
        }
        settingsItem("copy") {
            FilledTonalButton(
                onClick = {
                    val report =
                        buildString {
                            appendLine("鸿枢 ${appVersion(activity)} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
                            (capture + sync + background).forEach { (label, text) ->
                                appendLine("$label：$text")
                            }
                        }
                    activity
                        .getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("鸿枢诊断信息", report))
                    host.message("诊断信息已复制，不含短信内容和凭据")
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("复制诊断信息")
            }
        }
    }
}

@Composable
private fun AboutPage(host: SettingsHost) {
    val c = host.config
    SettingsScaffold(SettingsPage.About.title, host.back, host.busy) {
        settingsItem("hero") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painterResource(R.drawable.ic_hongshu_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp).clip(MaterialShapes.Cookie12Sided.toShape()),
                )
                Spacer(Modifier.height(8.dp))
                Text("鸿枢", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "跨设备私有短信同步",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        settingsGroup(
            "info",
            null,
            listOf(
                SettingsRow("version") {
                    SettingsItem(title = "版本", summary = appVersion(host.activity))
                },
                SettingsRow("device") {
                    SettingsItem(
                        title = "本机",
                        summary =
                            if (c.deviceId.isEmpty()) c.deviceName
                            else "${c.deviceName} · ID ${c.deviceId.take(8)}",
                    )
                },
                SettingsRow("stack") {
                    SettingsItem(title = "组成", summary = "Go / MySQL 中枢 · Android · Web / PWA")
                },
            ),
        )
    }
}
