package io.hongshu.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun AppUpdateSettings(host: SettingsHost) {
    val updates = remember { AppUpdates.get(host.activity) }
    val state by updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("应用更新", style = MaterialTheme.typography.titleMedium)
        SettingsSwitchItem("接收预发布版本", state.preview, { updates.setPreview(it) },
            summary = if (state.preview) "预发布通道：接收正式版及预发布版" else "正式通道：只接收正式版", enabled = !state.busy)
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { scope.launch { updates.check(manual = true) } }, enabled = !state.busy) {
            Text("检查更新")
        }
        state.candidate?.let { candidate ->
            Text("可升级至 ${candidate.version}", style = MaterialTheme.typography.titleSmall)
            if (candidate.notes.isNotBlank()) Text(candidate.notes.take(6000), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { scope.launch { updates.downloadAndInstall() } }, enabled = !state.busy) { Text("下载并安装") }
        }
    }
}
