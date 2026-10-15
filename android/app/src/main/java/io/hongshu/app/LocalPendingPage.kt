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
fun LocalPendingSubPage(repo: Repository, refresh: Long, onSims: () -> Unit) {
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
            Text(counts.label())
            Text("上传确认前不会删除；这里与中枢收件箱分开。已映射不代表当前可上传，还取决于下方连接与同步状态。")
            Text(
                "未知订阅不会按当前卡槽猜测；配置 SIM 后仅可补传能安全确定归属的记录。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { FilledTonalButton(onClick = onSims) { Text("检查 SIM 与接收号码") } }
        item { Text(smsSyncStatus(repo.context, repo), style = MaterialTheme.typography.bodySmall) }
        if (!loaded) item { Text("正在读取本地队列…") } else if (records.isEmpty()) item { Text("暂无待同步短信") }
        items(records, key = { it.key }) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.sender, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "接收号码：${record.receiver.ifEmpty { "待确认" }} · ${formatTimestamp(record.timestamp)}"
                    )
                    Text(
                        pendingStateLabel(
                            pendingState(
                                record.receiver,
                                record.blocked,
                                config.upload,
                                config.serverUploadAllowed,
                                connected,
                                authorized,
                            )
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (record.historical) Text("历史采集", style = MaterialTheme.typography.labelSmall)
                    Text(record.body, style = MaterialTheme.typography.bodyMedium)
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
