@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
    var offset by remember { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf(PendingCounts()) }
    var records by remember { mutableStateOf<List<LocalPendingMessage>?>(null) }
    val config = repo.config
    val connected = remember(host.refresh) { networkAvailable(repo.context) }
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
            settingsItem("sims") {
                NoticeCard(
                    icon = Icons.Default.SimCard,
                    title = "${counts.needsSim} 条短信不确定是哪张卡收到的",
                    text = "设置 SIM 卡号码后，能确定归属的短信会自动补传",
                    actionLabel = "设置号码",
                    onAction = { host.navigate(SettingsPage.Sims) },
                    modifier = Modifier.padding(bottom = 12.dp),
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
                        androidx.compose.material3.Icon(
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
                        SettingsRow(record.key) {
                            PendingRecord(
                                record,
                                pendingStateLabel(
                                    pendingState(
                                        record.receiver,
                                        record.blocked,
                                        config.upload,
                                        config.serverUploadAllowed,
                                        connected,
                                        config.token.isNotEmpty(),
                                    )
                                ),
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
                    TextButton(onClick = { offset += PageSize }, enabled = offset + PageSize < total) {
                        Text("下一页")
                    }
                }
            }
        settingsItem("help") {
            ExpandableHelp(
                "短信在中枢确认收到之前不会从本机删除；这里和中枢里的收件箱是分开的。号码不确定的短信不会按卡槽猜测归属。"
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
            Text("$value", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PendingRecord(record: LocalPendingMessage, state: String) {
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
                append(state)
                append(" · 接收号码 ")
                append(record.receiver.ifEmpty { "待确认" })
                if (record.historical) append(" · 历史补齐")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
