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
import androidx.compose.material3.FilledTonalButton
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
    val hasSimNumber = sims.any { confirmedNumber(it.phone) }
    var assigning by remember { mutableStateOf<List<String>?>(null) }
    var discarding by remember { mutableStateOf<List<String>?>(null) }
    var discardingIgnored by remember { mutableStateOf(false) }
    var confirmIgnore by remember { mutableStateOf(false) }
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
    if (discardingIgnored) {
        AlertDialog(
            onDismissRequest = { discardingIgnored = false },
            title = { Text("放弃已自动忽略的短信") },
            text = {
                Text("这 ${counts.ignored} 条记录将不再上传，也不会被再次采集。手机短信应用中的原短信不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardingIgnored = false
                        host.action { "已放弃 ${repo.store.discardAutoIgnored()} 条已自动忽略记录" }
                    }
                ) { Text("放弃全部") }
            },
            dismissButton = { TextButton(onClick = { discardingIgnored = false }) { Text("取消") } },
        )
    }
    if (confirmIgnore) {
        AlertDialog(
            onDismissRequest = { confirmIgnore = false },
            title = { Text("自动忽略无法确认接收号码的短信") },
            text = {
                Text(
                    "开启后，无法解析接收号码的短信将不再出现在提示和会话列表中，也不会尝试上传；" +
                        "它们会进入下方的“已自动忽略”区，可随时恢复。手机短信应用中的原短信不受影响。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmIgnore = false
                        host.action {
                            repo.config.autoIgnoreUnassigned = true
                            val ignored = repo.store.autoIgnoreUnassigned()
                            syncNow(repo.context, manual = true)
                            if (ignored > 0) "已开启自动忽略，忽略 $ignored 条" else "已开启自动忽略"
                        }
                    }
                ) { Text("开启") }
            },
            dismissButton = { TextButton(onClick = { confirmIgnore = false }) { Text("取消") } },
        )
    }

    SettingsScaffold(SettingsPage.Pending.title, host.back, host.busy) {
        settingsItem("intro") {
            Text(
                "这里是本机已收到、但中枢还没确认收到的短信。上传成功后会自动从这里消失，并出现在会话列表中。手机短信应用里的原短信不受影响。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        if (counts.ready > 0)
            settingsItem("group-ready") {
                PendingGroupCard("等待上传 ${counts.ready} 条", uploadAttemptStatus(config, connected)) {
                    FilledTonalButton(
                        enabled = !host.busy,
                        onClick = {
                            host.action {
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
            settingsItem("group-sim") {
                PendingGroupCard(
                    "需确认接收号码 ${counts.needsSim} 条",
                    if (!hasSimNumber)
                        "系统没有告诉鸿枢这些短信是哪张卡收到的，且尚未配置任何手机号。请先填写 SIM 卡号码。"
                    else "系统没有告诉鸿枢这些短信是哪张卡收到的。确认后为它们选择接收号码即可上传。",
                ) {
                    if (!hasSimNumber)
                        FilledTonalButton(onClick = { host.navigate(SettingsPage.Sims) }) {
                            Text("填写 SIM 卡号码")
                        }
                    else
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                enabled = !host.busy,
                                onClick = {
                                    host.action {
                                        val keys = withContext(Dispatchers.IO) { repo.store.unresolvedKeys() }
                                        withContext(Dispatchers.Main) {
                                            assigning = keys.takeIf { it.isNotEmpty() }
                                        }
                                        if (keys.isEmpty()) "没有需要指定号码的短信" else "请选择接收这些短信的号码"
                                    }
                                },
                            ) {
                                Text("指定接收号码")
                            }
                            TextButton(
                                enabled = !host.busy,
                                onClick = { confirmIgnore = true },
                            ) {
                                Text("自动忽略此类")
                            }
                        }
                }
            }
        if (counts.invalid > 0)
            settingsItem("group-invalid") {
                PendingGroupCard(
                    "无法上传 ${counts.invalid} 条",
                    "内容或格式被本机校验或中枢拒绝。可以重试；确认不需要时，在下方逐条放弃上传。",
                ) {
                    OutlinedButton(
                        enabled = !host.busy,
                        onClick = {
                            host.action {
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
        if (counts.collisions > 0)
            settingsItem("group-notification-collision") {
                PendingGroupCard(
                    "通知身份碰撞 ${counts.collisions} 条，需处理",
                    "多个通知观察对应相同上传指纹，中枢不能分别保存。记录已保留；请核实下方接收号码，或明确放弃。普通重试不会解除碰撞。",
                ) {}
            }
        if (counts.ignored > 0)
            settingsItem("group-auto-ignored") {
                PendingGroupCard(
                    "已自动忽略 ${counts.ignored} 条",
                    "这些记录没有可靠的接收号码，已不再提示，也不会尝试上传。手机短信应用中的原短信不受影响；出现新的广播/收件箱副本或保存有效号码后会自动恢复。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            enabled = !host.busy,
                            onClick = {
                                host.action {
                                    repo.config.autoIgnoreUnassigned = false
                                    val restored = repo.store.restoreAutoIgnored()
                                    syncNow(repo.context, manual = true)
                                    if (restored > 0) "已恢复 $restored 条，并关闭自动忽略"
                                    else "已关闭自动忽略"
                                }
                            },
                        ) {
                            Text("恢复并关闭")
                        }
                        OutlinedButton(
                            enabled = !host.busy,
                            onClick = { discardingIgnored = true },
                        ) {
                            Text("放弃全部")
                        }
                    }
                }
            }
        settingsItem("sync") {
            Text(
                "中枢同步：${smsSyncStatus(repo.context, repo)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
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
                        Text(
                            if (counts.ignored > 0) "没有需要处理的未上传短信" else "没有未上传的短信",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            else ->
                settingsGroup(
                    "records",
                    "未上传的短信",
                    list.map { record ->
                        val state =
                            pendingState(
                                record.receiver,
                                record.blocked,
                                config.upload,
                                config.serverUploadAllowed,
                                connected,
                                authorized,
                                record.notificationOnly,
                                record.notificationHeld,
                                record.awaitingHub,
                            )
                        SettingsRow(record.key) {
                            PendingRecord(
                                record = record,
                                state = state,
                                canAssign = state in setOf(PendingState.NEEDS_SIM, PendingState.NOTIFICATION_ONLY, PendingState.NOTIFICATION_COLLISION) && sims.isNotEmpty(),
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
                "短信在中枢确认收到之前不会从本机删除；这里和中枢里的收件箱是分开的。号码不确定的短信不会按卡槽猜测归属；“指定接收号码”只在你确认后生效。“放弃上传”会从本机队列删除记录，手机短信应用中的原短信不受影响。自动忽略的记录保留判重账本，不提示也不上传，可在上方恢复或放弃。"
            )
        }
    }
}

@Composable
private fun PendingGroupCard(
    title: String,
    detail: String,
    action: @Composable () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
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
                if (record.outgoing) "发给 ${record.sender}" else record.sender,
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
                append(if (record.outgoing) " · 发送号码 " else " · 接收号码 ")
                append(record.receiver.ifEmpty { "待确认" })
                if (record.historical) append(" · 历史补齐")
                if (record.notificationUncertain) append(" · 通知身份或跨源关联不确定，已保留")
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
