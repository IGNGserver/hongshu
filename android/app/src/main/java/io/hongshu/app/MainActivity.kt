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
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            MaterialExpressiveTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Color(0xFF315B58),
                        secondaryContainer = Color(0xFFE3EDE3),
                        surface = Color(0xFFF8FAF5),
                    )
            ) {
                HongshuUI(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
    var screen by remember { mutableStateOf("inbox") }
    var sender by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var sim by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<List<CachedMessage>>(emptyList()) }
    var messageLimit by remember { mutableIntStateOf(200) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
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
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            scope.launch {
                snackbar.showSnackbar(
                    if (grants.values.all { it }) "权限已授予，可以继续操作" else "部分权限未授予，普通查看不受影响"
                )
            }
            Store.changes.value++
        }
    LaunchedEffect(search, sim, sender) { messageLimit = 200 }
    LaunchedEffect(changes, search, sim, sender, messageLimit) {
        loaded =
            withContext(Dispatchers.IO) {
                repo.store.messages(sender, search, sim, limit = messageLimit)
            }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30000)
            if (repo.config.token.isNotEmpty()) syncNow(activity)
        }
    }
    BackHandler(screen != "inbox" || sender.isNotEmpty()) {
        if (screen != "inbox") screen = "inbox" else sender = ""
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (screen == "settings") "设备与连接"
                        else if (sender.isNotEmpty())
                            loaded.firstOrNull()?.contact?.ifEmpty { sender } ?: sender
                        else "鸿枢"
                    )
                },
                navigationIcon = {
                    if (screen != "inbox" || sender.isNotEmpty())
                        IconButton(
                            onClick = { if (screen != "inbox") screen = "inbox" else sender = "" }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, "更多选项")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("设备与连接设置") },
                                onClick = {
                                    menu = false
                                    screen = "settings"
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("立即同步") },
                                onClick = {
                                    menu = false
                                    syncNow(activity)
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            AnimatedContent(
                targetState = screen,
                label = "navigation",
                modifier = Modifier.weight(1f),
            ) { current ->
                if (current == "settings")
                    Settings(activity, repo, busy, permission::launch, ::action)
                else
                    Column(Modifier.fillMaxSize()) {
                        if (sender.isEmpty()) {
                            OutlinedTextField(
                                value = search,
                                onValueChange = { search = it },
                                placeholder = { Text("搜索短信与联系人") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                singleLine = true,
                                shape = RoundedCornerShape(32.dp),
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            val sims =
                                remember(changes) {
                                    (repo.store.sims().map { it.phone } + repo.store.receivers())
                                        .distinct()
                                }
                            Row(
                                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                var filterMenu by remember { mutableStateOf(false) }
                                Box {
                                    FilterChip(
                                        selected = sim.isNotEmpty(),
                                        onClick = { filterMenu = true },
                                        label = { Text(sim.ifEmpty { "所有 SIM" }) },
                                    )
                                    DropdownMenu(filterMenu, { filterMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("所有 SIM") },
                                            onClick = {
                                                sim = ""
                                                filterMenu = false
                                            },
                                        )
                                        sims.forEach { s ->
                                            DropdownMenuItem(
                                                text = { Text(s) },
                                                onClick = {
                                                    sim = s
                                                    filterMenu = false
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
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            )
                        }
                        if (loaded.isEmpty())
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(32.dp),
                                ) {
                                    Text(
                                        if (repo.config.token.isEmpty()) "连接你的私人短信中枢"
                                        else "暂无符合条件的短信",
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        "历史会话在这里汇聚",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (repo.config.token.isEmpty())
                                        FilledTonalButton(onClick = { screen = "settings" }) {
                                            Text("配置连接")
                                        }
                                }
                            }
                        else
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                reverseLayout = sender.isNotEmpty(),
                                contentPadding = PaddingValues(vertical = 12.dp),
                            ) {
                                items(loaded, key = { it.id }) { m ->
                                    if (sender.isEmpty()) {
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable { sender = m.sender }
                                                .padding(horizontal = 20.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        ) {
                                            Box(
                                                Modifier.size(48.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme
                                                            .secondaryContainer,
                                                        CircleShape,
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    (m.contact.ifEmpty { m.sender }).take(1),
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    Text(
                                                        m.contact.ifEmpty { m.sender },
                                                        style =
                                                            MaterialTheme.typography.titleMedium,
                                                        modifier = Modifier.weight(1f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        SimpleDateFormat(
                                                                "MM/dd",
                                                                Locale.getDefault(),
                                                            )
                                                            .format(Date(m.timestamp)),
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                }
                                                Text(
                                                    m.body,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                            }
                                        }
                                    } else
                                        Column(
                                            Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                                        ) {
                                            Surface(
                                                shape =
                                                    RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                            ) {
                                                SelectionContainer {
                                                    Text(
                                                        m.body,
                                                        Modifier.padding(16.dp),
                                                        style = MaterialTheme.typography.bodyLarge,
                                                    )
                                                }
                                            }
                                            Text(
                                                date(m.timestamp) +
                                                    " · " +
                                                    m.receiver +
                                                    " · " +
                                                    if (m.deviceId == repo.config.deviceId) "本机"
                                                    else "跨设备",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier =
                                                    Modifier.padding(start = 8.dp, top = 4.dp),
                                            )
                                        }
                                }
                                if (loaded.size >= messageLimit)
                                    item {
                                        TextButton(
                                            onClick = { messageLimit += 200 },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(if (sender.isNotEmpty()) "加载更早记录" else "加载更多会话")
                                        }
                                    }
                            }
                    }
            }
        }
    }
}

@Composable
private fun Settings(
    activity: MainActivity,
    repo: Repository,
    busy: Boolean,
    permissions: (Array<String>) -> Unit,
    action: (suspend () -> String) -> Unit,
) {
    val c = repo.config
    var url by remember { mutableStateOf(c.url) }
    var code by remember { mutableStateOf("") }
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("连接配置", style = MaterialTheme.typography.titleLarge)
            Text("仅接受 HTTPS；证书必须由系统信任。每台设备有独立凭据。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(
                url,
                { url = it },
                label = { Text("中枢地址 https://sms.example.com") },
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
                code,
                { code = it },
                label = { Text("一次性配对码") },
                singleLine = true,
                visualTransformation =
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                enabled = !busy && code.isNotBlank() && c.token.isEmpty(),
                onClick = {
                    action {
                        repo.pair(url, code, name)
                        code = ""
                        syncNow(activity)
                        "配对成功"
                    }
                },
            ) {
                Text("配对此设备")
            }
            if (c.token.isNotEmpty())
                Text("已配对；更换中枢前请先在原中枢撤销此设备。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider()
            Text("两条独立链路", style = MaterialTheme.typography.titleLarge)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("采集并上传本机短信")
                    Text("关闭时仍可查看其他设备的历史", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    upload,
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
            if (upload)
                OutlinedButton(
                    onClick = { permissions(arrayOf(Manifest.permission.RECEIVE_SMS)) }
                ) {
                    Text("授予新短信采集权限")
                }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("接收跨设备通知", Modifier.weight(1f))
                Switch(
                    notify,
                    onCheckedChange = { enabled ->
                        action {
                            repo.api.request(
                                "/devices/${c.deviceId}",
                                "PATCH",
                                JSONObject().put("notify", enabled),
                            )
                            notify = enabled
                            c.notify = enabled
                            if (!enabled)
                                activity.stopService(Intent(activity, RealtimeService::class.java))
                            "通知设置已保存"
                        }
                    },
                )
            }
            if (Build.VERSION.SDK_INT >= 33)
                OutlinedButton(
                    onClick = { permissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                ) {
                    Text("授予通知权限")
                }
        }
        item {
            Text(
                "实时通知是可选的前台服务，显示常驻通知；Android 15+ 每 24 小时后台 dataSync 最长约 6 小时。其余时间使用周期补齐，不保证即时。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
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
                    }
                ) {
                    Text("启动实时服务")
                }
                OutlinedButton(
                    onClick = {
                        activity.stopService(Intent(activity, RealtimeService::class.java))
                    }
                ) {
                    Text("停止")
                }
            }
        }
        item {
            HorizontalDivider()
            Text("SIM 接收号码", style = MaterialTheme.typography.titleLarge)
            Text("必须确认号码。不能自动识别时手工填写；双卡不猜测来源。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            var singleSim by remember { mutableStateOf(c.singleSimConfirmed) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "确认本机只有一张 SIM，未知来源可使用唯一已确认号码",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    singleSim,
                    onCheckedChange = {
                        singleSim = it
                        c.singleSimConfirmed = it
                        syncNow(activity)
                    },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    permissions(
                        arrayOf(
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.READ_PHONE_NUMBERS,
                        )
                    )
                }
            ) {
                Text("允许识别 SIM（可选）")
            }
            TextButton(
                onClick = {
                    action {
                        check(
                            ContextCompat.checkSelfPermission(
                                activity,
                                Manifest.permission.READ_PHONE_STATE,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            "请先授权 SIM 识别"
                        }
                        val sm = activity.getSystemService(SubscriptionManager::class.java)
                        candidates =
                            sm.activeSubscriptionInfoList.orEmpty().map { s ->
                                val number =
                                    if (
                                        Build.VERSION.SDK_INT >= 33 &&
                                            ContextCompat.checkSelfPermission(
                                                activity,
                                                Manifest.permission.READ_PHONE_NUMBERS,
                                            ) == PackageManager.PERMISSION_GRANTED
                                    )
                                        sm.getPhoneNumber(s.subscriptionId)
                                    else ""
                                SimMapping(
                                    s.subscriptionId,
                                    s.simSlotIndex,
                                    number,
                                    s.displayName.toString(),
                                )
                            }
                        "已读取 SIM 信息，请确认号码"
                    }
                }
            ) {
                Text("读取 SIM 建议")
            }
        }
        items(candidates) { s ->
            val slotText = if (s.slot >= 0) "卡槽 ${s.slot + 1}" else "卡槽未知"
            OutlinedButton(
                onClick = {
                    sub = s.sub.toString()
                    slot = if (s.slot >= 0) s.slot.toString() else ""
                    phone = s.phone
                    label = s.label
                }
            ) {
                Text("${s.label} · $slotText · ${s.phone.ifEmpty{"号码未知"}}")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    sub,
                    { sub = it },
                    label = { Text("订阅 ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    slot,
                    { slot = it },
                    label = { Text("卡槽 0/1") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            OutlinedTextField(
                phone,
                { phone = it },
                label = { Text("用户确认的接收号码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                label,
                { label = it },
                label = { Text("SIM 名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    action {
                        check(confirmedNumber(phone)) { "请填写正确号码，建议国际格式" }
                        val mapping =
                            SimMapping(
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
                        "号码已确认"
                    }
                }
            ) {
                Text("确认并保存 SIM")
            }
        }
        items(mappings) { s -> Text("${s.label}: ${s.phone} · sub ${s.sub} · slot ${s.slot}") }
        item {
            HorizontalDivider()
            Text("可选导入", style = MaterialTheme.typography.titleLarge)
            Text(
                "仅导入系统收件箱；不会请求默认短信应用角色。现代 Android 可能限制验证码访问。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedButton(onClick = { permissions(arrayOf(Manifest.permission.READ_SMS)) }) {
                Text("授予历史读取权限")
            }
            Button(
                enabled = !busy && upload,
                onClick = { action { "已排队 ${importHistory(activity)} 条历史记录" } },
            ) {
                Text("导入历史短信")
            }
        }
        item {
            OutlinedButton(onClick = { permissions(arrayOf(Manifest.permission.READ_CONTACTS)) }) {
                Text("授予通讯录权限（可选）")
            }
            OutlinedButton(
                enabled = !busy,
                onClick = { action { "已同步 ${importContacts(activity)} 个联系人名称" } },
            ) {
                Text("同步联系人名称到中枢")
            }
            Text("名称将共享给所有授权设备。没有权限时始终使用号码显示。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            HorizontalDivider()
            Text("本地状态", style = MaterialTheme.typography.titleLarge)
            Text(c.status)
            Text(
                "待上传 ${repo.store.pendingCount()} 条 · 异常保留 ${repo.store.blockedCount()} 条 · 最近同步 ${if(c.lastSync==0L)"尚未同步" else date(c.lastSync)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "本地短信存储在应用私有目录，备份与截屏默认关闭。异常消息保留而不阻塞其他消息。用户强制停止后需要重新打开应用。",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    repo.store.retryBlocked()
                    syncNow(activity)
                }
            ) {
                Text("重试上传与补齐")
            }
        }
    }
}
