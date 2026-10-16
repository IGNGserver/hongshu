@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun networkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private const val PageSize = 100

@Composable
fun LocalPendingPage(host: SettingsHost) {
    val repo = host.repo
    val config = repo.config
    var offset by remember { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf(PendingCounts()) }
    var records by remember { mutableStateOf<List<LocalPendingMessage>?>(null) }
    var sims by remember { mutableStateOf<List<SimMapping>>(emptyList()) }
    var assigning by remember { mutableStateOf<List<String>?>(null) }
    var discarding by remember { mutableStateOf<List<String>?>(null) }
    val connected = remember(host.refresh) { networkAvailable(repo.context) }
    val authorized = remember(host.refresh) { config.token.isNotEmpty() }

    LaunchedEffect(host.refresh) { sims = withContext(Dispatchers.IO) { repo.store.sims() } }
    LaunchedEffect(host.refresh, offset) {
        val page =
            withContext(Dispatchers.IO) {
                val summary = repo.store.pendingCounts()
                val count = summary.total
                val start =
                    if (count == 0) 0 else offset.coerceAtMost(((count - 1) / PageSize) * PageSize)
                Triple(summary, start, repo.store.localPending(PageSize, start))
            }
        counts = page.first
        offset = page.second
        records = page.third
    }
    val total = counts.total

    assigning?.let { keys ->
        AssignReceiverDialog(
            count = keys.size,
            sims = sims,
            onDismiss = { assigning = null },
            onConfirm = { sim ->
                assigning = null
                host.action {
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
                        host.action { "已放弃 ${repo.store.discard(keys)} 条记录" }
                    }
                ) {
                    Text("放弃上传")
                }
            },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text("取消") } },
        )
    }

    SettingsScaffold(SettingsPage.Pending.title, host.back, host.busy) {
        settingsItem("summary") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountTile("等待上传", counts.ready, Modifier.weight(1f))
                CountTile("待确认号码", counts.needsSim, Modifier.weight(1f), counts.needsSim > 0)
                CountTile("异常", counts.invalid, Modifier.weight(1f), counts.invalid > 0)
            }
        }
        settingsItem("sync") {
            Text(
                smsSyncStatus(repo.context, repo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
        if (counts.needsSim > 0)
            settingsItem("needs-sim") {
                NoticeCard(
                    icon = Icons.Default.SimCard,
                    title = "${counts.needsSim} 条短信不确定是哪张卡收到的",
                    text =
                        if (sims.isEmpty()) "请先在“SIM 卡与号码”中填写手机号"
                        else "选择接收号码后，这些短信会按该号码上传",
                    actionLabel = if (sims.isEmpty()) "设置号码" else "指定号码",
                    onAction = {
                        if (sims.isEmpty()) host.navigate(SettingsPage.Sims)
                        else
                            host.action {
                                val keys = withContext(Dispatchers.IO) { repo.store.unresolvedKeys() }
                                withContext(Dispatchers.Main) {
                                    assigning = keys.takeIf { it.isNotEmpty() }
                                }
                                if (keys.isEmpty()) "没有需要指定号码的短信" else "请选择接收这些短信的号码"
                            }
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        if (counts.invalid > 0)
            settingsItem("retry-all") {
                OutlinedButton(
                    onClick = {
                        host.action {
                            repo.store.retryInvalid()
                            syncNow(repo.context, manual = true)
                            "已重新排队 ${counts.invalid} 条异常记录"
                        }
                    },
                    enabled = !host.busy,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text("重试 ${counts.invalid} 条异常记录")
                }
            }
        val list = records
        when {
            list == null ->
                settingsItem("loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
            list.isEmpty() ->
                settingsItem("empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("所有短信都已上传", style = MaterialTheme.typography.titleMedium)
                    }
                }
            else ->
                settingsGroup(
                    "records",
                    "保留在本机的短信",
                    list.map { record ->
                        val state =
                            pendingState(
                                record.receiver,
                                record.blocked,
                                config.upload,
                                config.serverUploadAllowed,
                                connected,
                                authorized,
                            )
                        SettingsRow(record.key) {
                            PendingRecord(
                                record = record,
                                state = state,
                                canAssign = state == PendingState.NEEDS_SIM && sims.isNotEmpty(),
                                canRetry = state == PendingState.INVALID,
                                busy = host.busy,
                                onAssign = { assigning = listOf(record.key) },
                                onRetry = {
                                    host.action {
                                        repo.store.retryInvalid(listOf(record.key))
                                        syncNow(repo.context, manual = true)
                                        "已重新排队"
                                    }
                                },
                                onDiscard = { discarding = listOf(record.key) },
                            )
                        }
                    },
                )
        }
        if (total > PageSize)
            settingsItem("pages") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { offset = (offset - PageSize).coerceAtLeast(0) },
                        enabled = offset > 0,
                    ) {
                        Text("上一页")
                    }
                    Text("${offset / PageSize + 1} / ${(total + PageSize - 1) / PageSize}")
                    TextButton(
                        onClick = { offset += PageSize },
                        enabled = offset + PageSize < total,
                    ) {
                        Text("下一页")
                    }
                }
            }
        settingsItem("help") {
            ExpandableHelp(
                "短信在中枢确认收到之前不会从本机删除；这里和中枢里的收件箱是分开的。号码不确定的短信不会按卡槽猜测归属；“指定号码”只在你确认后生效。“放弃上传”会从本机队列删除记录，手机短信应用中的原短信不受影响。"
            )
        }
    }
}

@Composable
private fun CountTile(label: String, value: Int, modifier: Modifier, attention: Boolean = false) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        color =
            if (attention) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "$value",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PendingRecord(
    record: LocalPendingMessage,
    state: PendingState,
    canAssign: Boolean,
    canRetry: Boolean,
    busy: Boolean,
    onAssign: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                record.sender,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatTimestamp(record.timestamp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            record.body,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(pendingStateLabel(state))
                append(" · 接收号码 ")
                append(record.receiver.ifEmpty { "待确认" })
                if (record.historical) append(" · 历史补齐")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canAssign)
                TextButton(onClick = onAssign, enabled = !busy) { Text("指定号码") }
            if (canRetry)
                TextButton(onClick = onRetry, enabled = !busy) { Text("重试") }
            TextButton(onClick = onDiscard, enabled = !busy) { Text("放弃上传") }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
