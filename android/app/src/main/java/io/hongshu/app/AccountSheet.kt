@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun GoogleAccountDialog(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onRequestPermissions: (Array<String>) -> Unit,
    onAction: (suspend () -> String) -> Unit,
) {
    var activeSubPage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Top close bar with "X" button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (activeSubPage != null) {
                        IconButton(onClick = { activeSubPage = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }

                    Text(
                        text = when (activeSubPage) {
                            "hub_login" -> "中枢连接设置"
                            "sim_settings" -> "SIM 卡与号码配置"
                            "sync_control" -> "实时广播与同步控制"
                            "import" -> "历史导入与通讯录"
                            else -> "账号与设备"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(24.dp))
                    }
                }

                Box(Modifier.weight(1f)) {
                    when (activeSubPage) {
                        "hub_login" -> HubLoginSubPage(repo, busy, onAction) { activeSubPage = null }
                        "sim_settings" -> SimSettingsSubPage(activity, repo, onRequestPermissions, onAction)
                        "sync_control" -> SyncControlSubPage(activity, repo, onRequestPermissions, onAction)
                        "import" -> ImportSubPage(activity, repo, busy, onRequestPermissions, onAction)
                        else -> AccountMainOverview(
                            activity = activity,
                            repo = repo,
                            busy = busy,
                            onNavigate = { activeSubPage = it },
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountMainOverview(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    onNavigate: (String) -> Unit,
    onAction: (suspend () -> String) -> Unit,
) {
    val c = repo.config
    val isConnected = c.token.isNotEmpty()
    val deviceName = if (c.deviceId.isNotEmpty()) Build.MODEL else "未连接设备"
    val hubDisplay = if (isConnected) c.url else "尚未连接短信中枢"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // 1. Top Profile Card with Google Account Avatar style
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Avatar with concentric border
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE08D3C)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (if (isConnected) deviceName else "枢").take(1).uppercase(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = hubDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            // Status Badge
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isConnected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Text(
                                    text = if (isConnected) "Pro · 已连接中枢" else "离线模式",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.outline,
                                )
                            }
                        }

                        IconButton(onClick = { onNavigate("hub_login") }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "切换或配置")
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Pill button inside Card: "管理您的鸿枢账号 / 中枢设置"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onNavigate("hub_login") },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = if (isConnected) "管理中枢连接与凭据" else "连接您的私有短信中枢",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        // 2. Quick Action Pill: 立即同步短信
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable {
                        onAction {
                            syncNow(activity)
                            "立即同步已触发"
                        }
                    },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "立即执行双向云同步",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // 3. Functional Sections grouped in Rounded Cards (Exact Google Account Sheet layout)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column {
                    SheetActionRow(
                        icon = Icons.Default.SimCard,
                        title = "SIM 卡与号码识别",
                        onClick = { onNavigate("sim_settings") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.padding(horizontal = 16.dp))
                    SheetActionRow(
                        icon = Icons.Default.Sensors,
                        title = "实时同步与推送链路",
                        onClick = { onNavigate("sync_control") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.padding(horizontal = 16.dp))
                    SheetActionRow(
                        icon = Icons.Default.ImportExport,
                        title = "导入本地短信与通讯录",
                        onClick = { onNavigate("import") },
                    )
                }
            }
        }

        // 4. Data & Privacy & Settings Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column {
                    SheetActionRow(
                        icon = Icons.Default.Security,
                        title = "本地中枢状态与待传队列",
                        onClick = {
                            onAction {
                                repo.store.retryBlocked()
                                syncNow(activity)
                                "已触发重试并刷新状态"
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.padding(horizontal = 16.dp))
                    SheetActionRow(
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        title = "关于鸿枢跨设备短信",
                        onClick = {},
                    )
                }
            }
        }

        // Bottom status note
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = c.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "待上传 ${repo.store.pendingCount()} 条 · 异常保留 ${repo.store.blockedCount()} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ----------------- 子页面实现 -----------------

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
    var name by remember { mutableStateOf(Build.MODEL) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "连接私人短信中枢",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "支持 HTTP/HTTPS，建议生产环境使用 HTTPS 或内网穿透加密传输。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("中枢地址 http(s)://sms.example.com") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("设备名称") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("中枢密码") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                enabled = !busy && password.isNotBlank() && c.token.isEmpty(),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onAction {
                        repo.login(url, password, name)
                        password = ""
                        onSuccess()
                        "连接成功"
                    }
                },
            ) {
                Text("连接此设备", modifier = Modifier.padding(vertical = 4.dp))
            }
            if (c.token.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已成功连接中枢；若需更换请先在 Web 端或数据库撤销设备凭据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SimSettingsSubPage(
    activity: MainActivity,
    repo: Repository,
    onRequestPermissions: (Array<String>) -> Unit,
    onAction: (suspend () -> String) -> Unit,
) {
    val c = repo.config
    var singleSim by remember { mutableStateOf(c.singleSimConfirmed) }
    var sub by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("SIM") }
    var candidates by remember { mutableStateOf<List<SimMapping>>(emptyList()) }
    val changes by Store.changes.collectAsState()
    val mappings = remember(changes) { repo.store.sims() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "SIM 接收号码与卡槽识别",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "确认卡槽绑定的真实手机号码，用于跨设备精确分类与展示。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("单 SIM 卡设备简易模式", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("未知来源短信默认归属此号码", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = singleSim,
                    onCheckedChange = {
                        singleSim = it
                        c.singleSimConfirmed = it
                        syncNow(activity)
                    },
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        onRequestPermissions(
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_PHONE_NUMBERS,
                            )
                        )
                    },
                ) {
                    Text("授予 SIM 权限")
                }
                FilledTonalButton(
                    shape = RoundedCornerShape(16.dp),
                    onClick = {
                        onAction {
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
                            "已识别当前插入的 SIM 卡"
                        }
                    },
                ) {
                    Text("自动识别建议")
                }
            }
        }

        items(candidates.size) { i ->
            val s = candidates[i]
            val slotText = if (s.slot >= 0) "卡槽 ${s.slot + 1}" else "卡槽未知"
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
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

        item {
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
                    label = { Text("卡槽 (0/1)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("确认的接收号码") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("SIM 标签") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onAction {
                        check(confirmedNumber(phone)) { "请填写正确号码，建议包含国家区号" }
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
                        "号码配置已保存并绑定"
                    }
                },
            ) {
                Text("保存并绑定 SIM", modifier = Modifier.padding(vertical = 4.dp))
            }
        }

        item {
            Text("已配置的 SIM 映射", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            mappings.forEach { s ->
                Text("• ${s.label}: ${s.phone} (订阅 ID: ${s.sub}, 卡槽: ${s.slot})", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SyncControlSubPage(
    activity: MainActivity,
    repo: Repository,
    onRequestPermissions: (Array<String>) -> Unit,
    onAction: (suspend () -> String) -> Unit,
) {
    val c = repo.config
    var upload by remember { mutableStateOf(c.upload) }
    var notify by remember { mutableStateOf(c.notify) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("实时广播与通知链路", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("采集并上传本机短信", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("关闭时仅接收和查看中枢历史", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = upload,
                    onCheckedChange = { enabled ->
                        onAction {
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
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    shape = RoundedCornerShape(14.dp),
                    onClick = { onRequestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS)) },
                ) {
                    Text("授予短信广播接收权限 (RECEIVE_SMS)")
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("接收跨设备推送通知", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("其他设备收到短信时本机发出系统通知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = notify,
                    onCheckedChange = { enabled ->
                        onAction {
                            check(c.token.isNotEmpty()) { "请先连接中枢" }
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
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    shape = RoundedCornerShape(14.dp),
                    onClick = { onRequestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                ) {
                    Text("授予系统通知权限 (POST_NOTIFICATIONS)")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    shape = RoundedCornerShape(14.dp),
                    onClick = {
                        onAction {
                            check(c.token.isNotEmpty() && c.notify) { "请先连接并开启通知" }
                            withContext(Dispatchers.Main) {
                                ContextCompat.startForegroundService(
                                    activity,
                                    Intent(activity, RealtimeService::class.java),
                                )
                            }
                            "前台常驻服务已启动"
                        }
                    },
                ) {
                    Text("启动前台常驻服务")
                }
                OutlinedButton(
                    shape = RoundedCornerShape(14.dp),
                    onClick = { activity.stopService(Intent(activity, RealtimeService::class.java)) },
                ) {
                    Text("停止服务")
                }
            }
        }
    }
}

@Composable
private fun ImportSubPage(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    onRequestPermissions: (Array<String>) -> Unit,
    onAction: (suspend () -> String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("历史短信与联系人导入", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    shape = RoundedCornerShape(14.dp),
                    onClick = { onRequestPermissions(arrayOf(Manifest.permission.READ_SMS)) },
                ) {
                    Text("授予短信读取权限")
                }
                Button(
                    shape = RoundedCornerShape(14.dp),
                    enabled = !busy && repo.config.upload,
                    onClick = { onAction { "已排队 ${importHistory(activity)} 条历史记录" } },
                ) {
                    Text("导入历史短信")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    shape = RoundedCornerShape(14.dp),
                    onClick = { onRequestPermissions(arrayOf(Manifest.permission.READ_CONTACTS)) },
                ) {
                    Text("授予通讯录权限")
                }
                OutlinedButton(
                    shape = RoundedCornerShape(14.dp),
                    enabled = !busy,
                    onClick = { onAction { "已同步 ${importContacts(activity)} 个联系人" } },
                ) {
                    Text("同步联系人名称")
                }
            }
        }
    }
}
