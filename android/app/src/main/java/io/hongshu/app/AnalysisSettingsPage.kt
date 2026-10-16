package io.hongshu.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AnalysisSettingsPage(host: SettingsHost) {
    val status by produceState("正在读取中枢分析状态…", host.refresh) {
        value = withContext(Dispatchers.IO) {
            try {
                val settings = host.repo.api.request("/analysis/settings")
                val counts = host.repo.api.request("/analysis/counts").getJSONObject("counts")
                buildString {
                    append(if (settings.optBoolean("enabled")) "AI 已开启" else "AI 已关闭")
                    if (settings.optBoolean("paused")) append(" · 队列已暂停")
                    append("\n已分类 ${counts.optInt("personal") + counts.optInt("notification") + counts.optInt("promotion") + counts.optInt("spam")} 条 · 待分析 ${counts.optInt("pending")} 条")
                    append("\n今日调用 ${settings.optInt("usage_count")}/${settings.optInt("daily_limit")}")
                    settings.optJSONObject("queue")?.let { queue ->
                        append("\n等待 ${queue.optInt("pending")} · 处理中 ${queue.optInt("processing")} · 失败/待确认 ${queue.optInt("failed")} · 规则待回填 ${queue.optInt("backfill")}")
                    }
                    settings.optString("last_error").takeIf { it.isNotEmpty() }?.let { append("\n服务异常：$it，请在 Web 设置处理") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { "无法读取 AI 状态；短信接收与同步不受影响，可刷新重试" }
        }
    }
    SettingsScaffold(SettingsPage.Analysis.title, host.back, host.busy) {
        settingsItem("status") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status)
                Text("关闭 AI 时仅分验证码和其他短信。开启后，历史和新增非验证码正文将发往管理员配置的服务；脱敏不能保证匿名。垃圾分组不删除短信。")
                Text("AI 配置、暂停、恢复和批量重试由 Web 管理员操作；本机可在短信详情调整分类或重试单条失败消息。")
                TextButton(onClick = { host.navigate(SettingsPage.Hub) }) { Text("同步与连接") }
                if (host.config.url.isNotEmpty()) TextButton(onClick = {
                    runCatching { host.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(host.config.url))) }
                        .onFailure { host.message("无法打开浏览器，请手动访问中枢 Web 设置") }
                }) { Text("打开中枢 Web 管理") }
            }
        }
    }
}
