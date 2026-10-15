package io.hongshu.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun networkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
fun LocalPendingSubPage(
    repo: Repository,
    refresh: Long,
    busy: Boolean,
    onAction: (suspend () -> String) -> Unit,
    onSims: () -> Unit,
) {
    var sims by remember { mutableStateOf<List<SimMapping>>(emptyList()) }
    var assigning by remember { mutableStateOf<List<String>?>(null) }
    var discarding by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(refresh) { sims = withContext(Dispatchers.IO) { repo.store.sims() } }
    assigning?.let { keys ->
        AssignReceiverDialog(
            count = keys.size,
            sims = sims,
            onDismiss = { assigning = null },
            onConfirm = { sim ->
                assigning = null
                onAction {
                    val count = repo.store.assignReceiver(keys, sim)
                    syncNow(repo.context, manual = true)
                    "已为 $count 条短信指定接收号码 ${sim.phone}，正在上传"
                }
            },
        )
    }
    discarding?.let { keys ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text("放弃上传") },
            text = {
                Text("这 ${keys.size} 条短信将不再上传到中枢，也不会被再次采集。手机短信应用中的原短信不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discarding = null
                        onAction { "已放弃 ${repo.store.discard(keys)} 条记录" }
                    }
                ) {
                    Text("放弃上传")
                }
            },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text("取消") } },
        )
    }
    var offset by remember { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf(PendingCounts()) }
    val total = counts.total
    var records by remember { mutableStateOf<List<LocalPendingMessage>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val config = repo.config
    val connected = remember(refresh) { networkAvailable(repo.context) }
    val authorized = remember(refresh) { config.token.isNotEmpty() }
    LaunchedEffect(refresh, offset) {
        val page =
            withContext(Dispatchers.IO) {
                val summary = repo.store.pendingCounts()
                val count = summary.total
                val start = if (count == 0) 0 else offset.coerceAtMost(((count - 1) / 100) * 100)
                Triple(summary, start, repo.store.localPending(100, start))
            }
        counts = page.first
        offset = page.second
        records = page.third
        loaded = true
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                "这里是本机已收到、但中枢还没确认收到的短信。上传成功后会自动从这里消失，并出现在会话列表中。手机短信应用里的原短信不受影响。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (loaded && counts.ready > 0)
            item {
                PendingGroup("等待上传 ${counts.ready} 条", uploadAttemptStatus(config, connected)) {
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = {
                            onAction {
                                syncNow(repo.context, manual = true)
                                "已开始上传"
                            }
                        },
                    ) {
                        Text("立即上传")
                    }
                }
            }
        if (counts.needsSim > 0)
            item {
                PendingGroup(
                    "需确认接收号码 ${counts.needsSim} 条",
                    if (sims.isEmpty())
                        "系统没有告诉鸿枢这些短信是哪张卡收到的，且尚未配置任何手机号。请先填写 SIM 卡号码。"
                    else "系统没有告诉鸿枢这些短信是哪张卡收到的。确认后为它们选择接收号码即可上传。",
                ) {
                    if (sims.isEmpty())
                        FilledTonalButton(onClick = onSims) { Text("填写 SIM 卡号码") }
                    else
                        FilledTonalButton(
                            enabled = !busy,
                            onClick = {
                                onAction {
                                    val keys = repo.store.unresolvedKeys()
                                    withContext(Dispatchers.Main) {
                                        assigning = keys.takeIf { it.isNotEmpty() }
                                    }
                                    if (keys.isEmpty()) "没有需要指定号码的短信" else "请选择接收这些短信的号码"
                                }
                            },
                        ) {
                            Text("指定接收号码")
                        }
                }
            }
        if (counts.invalid > 0)
            item {
                PendingGroup(
                    "无法上传 ${counts.invalid} 条",
                    "内容或格式被本机校验或中枢拒绝。可以重试；确认不需要时，在下方逐条放弃上传。",
                ) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            onAction {
                                repo.store.retryInvalid()
                                syncNow(repo.context, manual = true)
                                "已重新排队 ${counts.invalid} 条记录"
                            }
                        },
                    ) {
                        Text("全部重试")
                    }
                }
            }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSims) { Text("SIM 卡与号码") }
            }
            Text("中枢同步：${smsSyncStatus(repo.context, repo)}", style = MaterialTheme.typography.bodySmall)
        }
        if (!loaded) item { Text("正在读取…") } else if (records.isEmpty()) item { Text("没有未上传的短信") }
        items(records, key = { it.key }) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.sender, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "接收号码：${record.receiver.ifEmpty { "待确认" }} · ${formatTimestamp(record.timestamp)}"
                    )
                    val state =
                        pendingState(
                            record.receiver,
                            record.blocked,
                            config.upload,
                            config.serverUploadAllowed,
                            connected,
                            authorized,
                        )
                    Text(pendingStateLabel(state), color = MaterialTheme.colorScheme.primary)
                    if (record.historical) Text("历史采集", style = MaterialTheme.typography.labelSmall)
                    Text(record.body, style = MaterialTheme.typography.bodyMedium)
                    if (state == PendingState.NEEDS_SIM || state == PendingState.INVALID)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state == PendingState.NEEDS_SIM && sims.isNotEmpty())
                                TextButton(enabled = !busy, onClick = { assigning = listOf(record.key) }) {
                                    Text("指定号码")
                                }
                            if (state == PendingState.INVALID)
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        onAction {
                                            repo.store.retryInvalid(listOf(record.key))
                                            syncNow(repo.context, manual = true)
                                            "已重新排队"
                                        }
                                    },
                                ) {
                                    Text("重试")
                                }
                            TextButton(enabled = !busy, onClick = { discarding = listOf(record.key) }) {
                                Text("放弃上传")
                            }
                        }
                }
            }
        }
        if (total > 100)
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = { offset = (offset - 100).coerceAtLeast(0) },
                        enabled = offset > 0,
                    ) {
                        Text("上一页")
                    }
                    Text("${offset / 100 + 1} / ${(total + 99) / 100}")
                    TextButton(onClick = { offset += 100 }, enabled = offset + 100 < total) {
                        Text("下一页")
                    }
                }
            }
    }
}

@Composable
private fun AssignReceiverDialog(
    count: Int,
    sims: List<SimMapping>,
    onDismiss: () -> Unit,
    onConfirm: (SimMapping) -> Unit,
) {
    var selected by remember { mutableStateOf(sims.singleOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("指定接收号码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("系统没有告诉鸿枢这 $count 条短信是哪张卡收到的。仅在你确认后选择，选择后会按该号码上传。")
                for (sim in sims)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == sim, onClick = { selected = sim })
                        Text("${sim.label} · ${sim.phone}")
                    }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onConfirm) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PendingGroup(title: String, detail: String, action: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            action()
        }
    }
}
