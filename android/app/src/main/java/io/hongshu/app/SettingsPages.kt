@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Opens a system settings screen from a settings action, failing with a readable message. */
private suspend fun SettingsHost.openSystem(intents: List<Intent>, failure: String) {
    withContext(Dispatchers.Main) { check(startFirstAvailable(activity, intents)) { failure } }
}

/** Big on/off card for the page's master switch (M3 "primary switch" pattern). */
@Composable
private fun MasterSwitchCard(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val container by
        animateColorAsState(
            if (checked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "master switch",
        )
    Surface(
        Modifier.fillMaxWidth()
            .padding(bottom = 4.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/**
 * Turns a setting on only after its permissions are granted; a refusal leaves it off and says
 * why, instead of showing a switch that is on but cannot work.
 */
private fun SettingsHost.enableWithPermission(
    permissions: Array<String>,
    refused: String,
    apply: suspend () -> String,
) {
    requestPermissions(permissions) { granted ->
        if (granted) action(apply) else message(refused)
    }
}

private suspend fun SettingsHost.patchDevice(field: String, value: Boolean) {
    repo.api.request("/devices/${config.deviceId}", "PATCH", JSONObject().put(field, value))
}

@Composable
fun CapturePage(host: SettingsHost) {
    val c = host.config
    val f = host.facts
    val activity = host.activity
    val busy = host.busy
    val vendor by
        produceState<String?>(null, host.refresh) {
            value = withContext(Dispatchers.IO) { vendorSmsStatus(activity) }
        }

    fun setUpload(enabled: Boolean) {
        fun apply(granted: Boolean) =
            host.action {
                check(!enabled || c.token.isNotEmpty()) { "请先连接中枢" }
                c.upload = enabled
                // The sync worker owns PATCH and versioned acknowledgement. An offline toggle
                // is durable immediately, and a late response cannot clear a newer toggle.
                if (c.uploadPatchPending) c.status = "本机采集设置已保存 · 等待中枢确认"
                syncNow(activity, manual = true)
                when {
                    !enabled -> "短信采集已关闭，未上传的短信仍保留在本机"
                    granted -> "短信采集已开启"
                    else -> "已开启，但未授予接收短信权限，新短信无法实时上传"
                }
            }
        // Capture also works through the inbox and notification paths, so a refused receive
        // permission still enables it; the row below then shows what is missing.
        if (enabled) host.requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), ::apply)
        else apply(true)
    }

    fun setRecovery(enabled: Boolean) {
        if (enabled)
            host.enableWithPermission(
                arrayOf(Manifest.permission.READ_SMS),
                "未授予读取短信权限，漏收检查没有开启",
            ) {
                c.recoveryEnabled = true
                SmsInboxObserver.ensure(activity)
                syncNow(activity, manual = true)
                "漏收检查已开启，只检查开启之后的新短信"
            }
        else
            host.action {
                c.recoveryEnabled = false
                syncNow(activity, manual = true)
                "漏收检查已关闭，已收到的短信仍会保存"
            }
    }

    fun setHistory(enabled: Boolean) {
        if (enabled)
            host.enableWithPermission(
                arrayOf(Manifest.permission.READ_SMS),
                "未授予读取短信权限，历史补齐没有开启",
            ) {
                c.historyEnabled = true
                syncNow(activity, manual = true)
                "历史补齐已开启，将在后台读取收件箱"
            }
        else
            host.action {
                c.historyEnabled = false
                syncNow(activity, manual = true)
                "历史补齐已关闭，实时采集不受影响"
            }
    }

    fun setNotificationCapture(enabled: Boolean) {
        host.action {
            check(!enabled || c.upload) { "请先开启短信采集" }
            c.notificationCapture = enabled
            if (enabled && !notificationListenerEnabled(activity))
                host.openSystem(notificationListenerIntents(activity), "无法打开通知使用权设置")
            if (enabled) "请在系统中允许鸿枢读取通知；只处理短信应用的通知" else "已停止从通知读取短信"
        }
    }

    SettingsScaffold(SettingsPage.Capture.title, host.back, busy) {
        settingsItem("master") {
            MasterSwitchCard(
                title = "在这台手机采集短信",
                summary =
                    if (!f.connected) "需要先连接中枢"
                    else if (c.upload) "收到的短信会上传到中枢，供其他设备查看"
                    else "开启后，这台手机收到的短信会上传到中枢",
                checked = c.upload,
                enabled = f.connected && !busy,
                onCheckedChange = ::setUpload,
            )
        }
        if (!f.connected)
            settingsItem("connect") {
                NoticeCard(
                    icon = Icons.Default.Dns,
                    title = "尚未连接中枢",
                    actionLabel = "去连接",
                    onAction = { host.navigate(SettingsPage.Hub) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        val sourcesEnabled = c.upload && !busy
        settingsGroup(
            "sources",
            "采集方式",
            buildList {
                add(
                    SettingsRow("realtime") {
                        SettingsItem(
                            title = "实时接收",
                            summary = "短信到达时立即保存并上传",
                            icon = Icons.Default.Sensors,
                            enabled = c.upload,
                            status =
                                if (f.receiveSms) StatusLevel.Ok to "已授权"
                                else StatusLevel.Attention to "去授权",
                            onClick =
                                if (f.receiveSms || !sourcesEnabled) null
                                else ({
                                    host.requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS)) {
                                        granted ->
                                        if (granted) syncNow(activity)
                                        else host.message("未授予接收短信权限")
                                    }
                                }),
                        )
                    }
                )
                add(
                    SettingsRow("recovery") {
                        SettingsSwitchItem(
                            title = "漏收检查",
                            summary = "系统没有把短信交给鸿枢时，从收件箱补读开启后的新短信（澎湃 OS 推荐）",
                            icon = Icons.AutoMirrored.Filled.ManageSearch,
                            checked = c.recoveryEnabled,
                            enabled = sourcesEnabled,
                            onCheckedChange = ::setRecovery,
                        )
                    }
                )
                add(
                    SettingsRow("history") {
                        SettingsSwitchItem(
                            title = "历史补齐",
                            summary =
                                if (c.historyEnabled && c.upload) historySmsStatus(activity)
                                else "把开启前已在收件箱的短信也上传一次",
                            icon = Icons.Default.History,
                            checked = c.historyEnabled,
                            enabled = sourcesEnabled,
                            onCheckedChange = ::setHistory,
                        )
                    }
                )
                if (c.upload && (c.recoveryEnabled || c.historyEnabled) && !f.readSms)
                    add(
                        SettingsRow("grant-read") {
                            SettingsItem(
                                title = "授予读取短信权限",
                                summary = "漏收检查和历史补齐需要读取收件箱",
                                icon = Icons.Default.Key,
                                status = StatusLevel.Attention to "需要授权",
                                onClick = {
                                    host.requestPermissions(arrayOf(Manifest.permission.READ_SMS)) {
                                        granted ->
                                        if (granted) syncNow(activity, manual = true)
                                        else host.message("未授予读取短信权限")
                                    }
                                },
                            )
                        }
                    )
                add(
                    SettingsRow("notification") {
                        SettingsSwitchItem(
                            title = "从通知读取",
                            summary = "广播和收件箱都读不到时（如通知类短信、5G 消息），从短信应用的通知里采集",
                            icon = Icons.Default.NotificationsActive,
                            checked = c.notificationCapture,
                            enabled = sourcesEnabled,
                            onCheckedChange = ::setNotificationCapture,
                        )
                    }
                )
                if (c.upload && c.notificationCapture && !f.notificationAccess)
                    add(
                        SettingsRow("grant-listener") {
                            SettingsItem(
                                title = "授予通知使用权",
                                summary = "提示“受限设置”时，先在应用信息右上角允许受限设置",
                                icon = Icons.Default.Key,
                                status = StatusLevel.Attention to "需要授权",
                                onClick = {
                                    host.action {
                                        host.openSystem(
                                            notificationListenerIntents(activity),
                                            "无法打开通知使用权设置",
                                        )
                                        "请允许鸿枢读取通知，返回后显示实际状态"
                                    }
                                },
                            )
                        }
                    )
                add(
                    SettingsRow("scan") {
                        SettingsItem(
                            title = "立即检查收件箱",
                            summary = "马上补读一次，不用等后台任务",
                            icon = Icons.Default.Refresh,
                            enabled = sourcesEnabled && (c.recoveryEnabled || c.historyEnabled),
                            onClick = {
                                host.action {
                                    val result =
                                        if (c.recoveryEnabled) recoverRecentInbox(activity)
                                        else catchUpInbox(activity)
                                    if (c.recoveryEnabled) c.recoveryStatus = result
                                    else c.captureStatus = result
                                    syncNow(activity, manual = true).await()
                                    result
                                }
                            },
                        )
                    }
                )
            },
        )
        settingsGroup(
            "numbers",
            "号码与队列",
            listOf(
                SettingsRow("sims") {
                    SettingsItem(
                        title = "SIM 卡与号码",
                        summary = "区分短信由哪张卡接收",
                        icon = Icons.Default.SimCard,
                        showChevron = true,
                        onClick = { host.navigate(SettingsPage.Sims) },
                    )
                },
                SettingsRow("pending") {
                    SettingsItem(
                        title = "待上传短信",
                        summary = f.pending.label(),
                        icon = Icons.Default.Outbox,
                        showChevron = true,
                        onClick = { host.navigate(SettingsPage.Pending) },
                    )
                },
            ),
        )
        vendor?.let { status ->
            settingsGroup(
                "vendor",
                "澎湃 OS",
                listOf(
                    SettingsRow("status") {
                        SettingsItem(title = "厂商短信授权", summary = status, icon = Icons.Default.PhonelinkLock)
                    },
                    SettingsRow("autostart") {
                        SettingsItem(
                            title = "允许自启动",
                            summary = "避免系统在后台拦截短信广播",
                            icon = Icons.Default.PowerSettingsNew,
                            showChevron = true,
                            onClick = {
                                host.action {
                                    host.openSystem(
                                        xiaomiAutoStartIntents(activity.packageName),
                                        "无法打开系统设置",
                                    )
                                    "请允许鸿枢自启动，返回后显示实际状态"
                                }
                            },
                        )
                    },
                    SettingsRow("sms-permission") {
                        SettingsItem(
                            title = "通知类短信与读取短信",
                            summary = "验证码等短信需要单独授权",
                            icon = Icons.Default.Key,
                            showChevron = true,
                            onClick = {
                                host.action {
                                    host.openSystem(
                                        xiaomiPermissionIntents(activity.packageName),
                                        "无法打开系统设置",
                                    )
                                    "请在权限中允许通知类短信与读取短信，返回后显示实际状态"
                                }
                            },
                        )
                    },
                ),
            )
            settingsItem("vendor-help") {
                ExpandableHelp(
                    "澎湃 OS 还需要：省电策略设为无限制；关闭短信设置中的免费网络短信与 5G 消息（这类消息不经过系统短信广播）。改完后用另一台手机发一条普通短信，在“诊断信息”里确认广播次数增加。",
                    title = "澎湃 OS 设置要点",
                )
            }
        }
        settingsItem("help") {
            ExpandableHelp(
                "实时接收只需要接收短信权限，但部分系统不会把验证码类短信交给第三方应用。漏收检查只补读开启之后的新短信；开启之前的短信需要历史补齐。漏收检查新增 0 条不代表验证码可读。从通知读取只处理短信应用的通知，不读取其他应用；授予通讯录权限可把联系人名称还原为号码。"
            )
        }
    }
}

@Composable
fun SimsPage(host: SettingsHost) {
    val activity = host.activity
    val repo = host.repo
    var automatic by remember { mutableStateOf(repo.config.automaticSim) }
    var phones by remember { mutableStateOf(listOf("", "")) }
    var candidates by remember { mutableStateOf<List<SimMapping>>(emptyList()) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var initialized by remember { mutableStateOf(false) }
    val phonePermission = hasPermission(activity, Manifest.permission.READ_PHONE_STATE)
    val simPermissions =
        arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)

    fun setPhone(slot: Int, value: String) {
        phones =
            phones.toMutableList().also { list ->
                while (list.size <= slot) list.add("")
                list[slot] = value
            }
    }

    val lifecycleState by activity.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(automatic, lifecycleState, host.refresh) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        while (true) {
            if (hasPermission(activity, Manifest.permission.READ_PHONE_STATE)) {
                val next =
                    withContext(Dispatchers.IO) { runCatching { scanSims(activity) }.getOrNull() }
                if (next != null) {
                    candidates = next
                    scanStatus = if (next.isEmpty()) "未检测到 SIM 卡" else null
                    if (!initialized) {
                        val saved = withContext(Dispatchers.IO) { repo.store.sims() }
                        phones =
                            (0..1).map { slot ->
                                next
                                    .find { it.slot == slot }
                                    ?.let { s -> saved.find { it.sub == s.sub }?.phone }
                                    .orEmpty()
                            }
                        initialized = true
                    }
                } else scanStatus = "SIM 识别失败"
            } else scanStatus = null
            // Automatic mode follows card swaps live; manual mode only needs the slot list once.
            if (!automatic) break
            delay(3000)
        }
    }

    fun chooseAutomatic() {
        host.enableWithPermission(simPermissions, "未授予电话权限，无法自动识别号码") {
            val active = scanSims(activity)
            // Numbers typed for cards the system does not report are kept with the card; a
            // blank field leaves that card unsynced rather than guessing its number.
            saveSimSelection(activity, true, automaticSimMappings(active, phones))
            withContext(Dispatchers.Main) {
                automatic = true
                candidates = active
            }
            SimMonitor.start(activity)
            syncNow(activity, manual = true)
            "已切换为自动识别，换卡或换号会自动更新"
        }
    }

    fun saveAutomaticNumbers() {
        host.action {
            val active = scanSims(activity)
            saveSimSelection(activity, true, automaticSimMappings(active, phones))
            withContext(Dispatchers.Main) { candidates = active }
            SimMonitor.start(activity)
            syncNow(activity, manual = true)
            "号码已保存，换卡或换号会自动更新"
        }
    }

    fun fillFromSystem() {
        host.enableWithPermission(simPermissions, "未授予电话权限，无法读取号码") {
            val next = scanSims(activity)
            withContext(Dispatchers.Main) {
                candidates = next
                phones = (0..1).map { slot -> next.find { it.slot == slot }?.phone.orEmpty() }
            }
            "已读取；系统没有提供的号码需要手动填写"
        }
    }

    fun saveManual() {
        host.enableWithPermission(
            arrayOf(Manifest.permission.READ_PHONE_STATE),
            "未授予电话权限，无法确认 SIM 卡",
        ) {
            val active = scanSims(activity)
            saveSimSelection(activity, false, manualSimMappings(active, phones))
            SimMonitor.start(activity)
            syncNow(activity, manual = true)
            "手机号已保存"
        }
    }

    SettingsScaffold(SettingsPage.Sims.title, host.back, host.busy) {
        settingsItem("mode") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = automatic,
                        onClick = { if (!automatic) chooseAutomatic() },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        enabled = !host.busy,
                    ) {
                        Text("自动识别")
                    }
                    SegmentedButton(
                        selected = !automatic,
                        onClick = { automatic = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        enabled = !host.busy,
                    ) {
                        Text("手动填写")
                    }
                }
                Text(
                    if (automatic) "从系统读取每张卡的号码，换卡后自动更新。系统读不到号码的卡可在下方填写。"
                    else "系统读不到号码时，手动填写每张卡的手机号，填完点保存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (!phonePermission)
            settingsItem("permission") {
                NoticeCard(
                    icon = Icons.Default.SimCard,
                    title = "需要电话权限才能识别 SIM 卡",
                    actionLabel = "授权",
                    onAction = {
                        host.requestPermissions(simPermissions) { granted ->
                            if (!granted) host.message("未授予电话权限")
                        }
                    },
                    attention = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        else
            scanStatus?.let { status ->
                settingsItem("scan-status") {
                    NoticeCard(
                        icon = Icons.Default.SimCard,
                        title = status,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        val slots = candidates.map { it.slot }.ifEmpty { listOf(0, 1) }
        settingsGroup(
            "slots",
            "SIM 卡",
            slots.map { slot ->
                val sim = candidates.find { it.slot == slot }
                val missingNumber = sim != null && sim.phone.isEmpty()
                SettingsRow("slot-$slot-$automatic") {
                    if (automatic) {
                        Column {
                            SettingsItem(
                                title = "卡 ${slot + 1}",
                                summary =
                                    when {
                                        sim == null -> "未插卡"
                                        missingNumber -> "系统没有提供号码，请在下方填写"
                                        else -> sim.phone
                                    },
                                icon = Icons.Default.SimCard,
                                status =
                                    when {
                                        sim == null -> StatusLevel.Off to "无卡"
                                        missingNumber -> StatusLevel.Attention to "未识别"
                                        else -> StatusLevel.Ok to "已识别"
                                    },
                            )
                            if (missingNumber)
                                OutlinedTextField(
                                    phones.getOrElse(slot) { "" },
                                    { setPhone(slot, it) },
                                    label = { Text("卡 ${slot + 1} 手机号（留空则不同步该卡）") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier =
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                        }
                    } else {
                        OutlinedTextField(
                            phones.getOrElse(slot) { "" },
                            { setPhone(slot, it) },
                            label = { Text("卡 ${slot + 1} 手机号") },
                            placeholder = { Text(if (sim == null) "未插卡" else "例如 13800138000") },
                            singleLine = true,
                            enabled = sim != null || candidates.isEmpty(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            },
        )
        if (!automatic)
            settingsItem("manual-actions") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = ::fillFromSystem,
                        enabled = !host.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("从系统读取")
                    }
                    Button(onClick = ::saveManual, enabled = !host.busy, modifier = Modifier.weight(1f)) {
                        Text("保存号码")
                    }
                }
            }
        else if (candidates.any { it.phone.isEmpty() })
            settingsItem("auto-save") {
                Button(
                    onClick = ::saveAutomaticNumbers,
                    enabled = !host.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Text("保存号码")
                }
            }
        settingsItem("help") {
            ExpandableHelp(
                "号码用于区分短信由哪张卡接收。号码无法确定的短信会先保留在本机，不会按卡槽猜测；设置号码后，能确定归属的记录会补传。留空表示不同步该卡。"
            )
        }
    }
}

@Composable
fun NotificationsPage(host: SettingsHost) {
    val c = host.config
    val f = host.facts
    val activity = host.activity
    val health by RuntimeHealth.connection.collectAsState()

    fun setNotify(enabled: Boolean) {
        val apply: suspend () -> String = {
            check(c.token.isNotEmpty()) { "请先连接中枢" }
            host.patchDevice("notify", enabled)
            c.notify = enabled
            syncNow(activity)
            if (enabled) "新短信通知已开启" else "新短信通知已关闭"
        }
        if (enabled && Build.VERSION.SDK_INT >= 33)
            host.enableWithPermission(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                "未授予通知权限，无法提醒新短信",
                apply,
            )
        else host.action(apply)
    }

    SettingsScaffold(SettingsPage.Notifications.title, host.back, host.busy) {
        settingsItem("master") {
            MasterSwitchCard(
                title = "新短信通知",
                summary =
                    if (!f.connected) "需要先连接中枢"
                    else "其他设备上传的短信到达中枢时，在这台手机上提醒",
                checked = c.notify,
                enabled = f.connected && !host.busy,
                onCheckedChange = ::setNotify,
            )
        }
        settingsGroup(
            "options",
            "通知增强",
            listOf(
                SettingsRow("otp_island") {
                    SettingsSwitchItem(
                        title = "验证码实时胶囊 / 灵动岛",
                        summary =
                            if (c.liveOtpIsland) "其他手机收到验证码时，在状态栏胶囊或高优先级通知常驻显示，支持快捷复制与关闭"
                            else "已关闭验证码专属实时通知",
                        icon = Icons.Default.Key,
                        checked = c.liveOtpIsland,
                        enabled = !host.busy,
                        onCheckedChange = { checked ->
                            c.liveOtpIsland = checked
                        },
                    )
                }
            ),
        )
        settingsGroup(
            "status",
            "状态",
            listOf(
                SettingsRow("notify") {
                    SettingsItem(
                        title = "通知接收",
                        summary = notificationStatus(activity, c, health),
                        icon = Icons.Default.Notifications,
                        status = notificationsStatus(f),
                    )
                },
                SettingsRow("connection") {
                    SettingsItem(
                        title = "实时连接通知",
                        summary = connectionNotificationStatus(activity),
                        icon = Icons.Default.Bolt,
                    )
                },
            ),
        )
        settingsGroup(
            "system",
            null,
            listOf(
                SettingsRow("system") {
                    SettingsItem(
                        title = "系统通知设置",
                        summary = "调整提示音、横幅和锁屏显示",
                        icon = Icons.Default.Settings,
                        showChevron = true,
                        onClick = {
                            host.action {
                                host.openSystem(
                                    listOf(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                                    ),
                                    "无法打开通知设置",
                                )
                                "请在系统中调整鸿枢的通知"
                            }
                        },
                    )
                }
            ),
        )
    }
}

@Composable
fun BackgroundPage(host: SettingsHost) {
    val c = host.config
    val f = host.facts
    val activity = host.activity
    val health by RuntimeHealth.connection.collectAsState()
    var confirmHide by remember { mutableStateOf(false) }

    fun setRealtime(enabled: Boolean) {
        host.action {
            check(!enabled || c.token.isNotEmpty()) { "请先连接中枢" }
            withContext(Dispatchers.Main) {
                c.keepAlive = enabled
                if (enabled) {
                    if (!startRealtimeIfEnabled(activity)) {
                        c.keepAlive = false
                        error("实时连接启动失败；周期同步仍在工作")
                    }
                } else activity.stopService(Intent(activity, RealtimeService::class.java))
            }
            if (enabled) "实时连接已开启" else "实时连接已关闭；周期同步仍在工作"
        }
    }

    fun setHidden(hidden: Boolean) {
        host.action {
            withContext(Dispatchers.Main) {
                check(updateRecentTaskVisibility(activity, hidden)) { "系统不支持修改当前任务显示状态" }
                c.hideFromRecents = hidden
            }
            if (hidden) "已从最近任务中隐藏，可从桌面图标或通知重新打开" else "最近任务中已恢复显示"
        }
    }

    if (confirmHide) {
        AlertDialog(
            onDismissRequest = { confirmHide = false },
            title = { Text("在最近任务中隐藏？") },
            text = {
                Text(
                    "隐藏后仍可从桌面图标或连接通知打开鸿枢。如果手机需要“任务加锁”防止被清理，请先在最近任务里加锁再隐藏。隐藏本身不会让应用更不容易被系统关闭。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmHide = false
                        setHidden(true)
                    }
                ) {
                    Text("隐藏")
                }
            },
            dismissButton = { TextButton(onClick = { confirmHide = false }) { Text("取消") } },
        )
    }

    SettingsScaffold(SettingsPage.Background.title, host.back, host.busy) {
        settingsItem("master") {
            MasterSwitchCard(
                title = "保持实时连接",
                summary =
                    if (!f.connected) "需要先连接中枢"
                    else "用一条常驻通知保持与中枢连接，新短信立即到达；关闭后仍会周期同步",
                checked = c.keepAlive,
                enabled = f.connected && !host.busy,
                onCheckedChange = { enabled ->
                    // The foreground service still runs without notification permission; the
                    // permission only makes its notification visible.
                    if (enabled && Build.VERSION.SDK_INT >= 33)
                        host.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) {
                            setRealtime(true)
                        }
                    else setRealtime(enabled)
                },
            )
        }
        settingsGroup(
            "realtime",
            "实时连接",
            listOf(
                SettingsRow("status") {
                    SettingsItem(
                        title = "连接状态",
                        summary = keepAliveStatus(activity, c, health),
                        icon = Icons.Default.Bolt,
                        status = backgroundStatus(f),
                    )
                },
                SettingsRow("boot") {
                    SettingsSwitchItem(
                        title = "开机后自动恢复",
                        summary =
                            if (c.keepAlive) "手机重启后自动重新连接，仍受系统自启动限制"
                            else "需要先开启保持实时连接",
                        icon = Icons.Default.RestartAlt,
                        checked = c.restoreRealtimeOnBoot,
                        enabled = c.keepAlive && !host.busy,
                        onCheckedChange = { enabled ->
                            host.action {
                                c.restoreRealtimeOnBoot = enabled
                                if (enabled) "开机后会自动恢复实时连接" else "开机后不再自动恢复"
                            }
                        },
                    )
                },
            ),
        )
        settingsGroup(
            "system",
            "系统限制",
            buildList {
                add(
                    SettingsRow("battery") {
                        SettingsItem(
                            title = "电池优化",
                            summary =
                                if (f.batteryUnrestricted) "已允许鸿枢在后台运行"
                                else "建议设为“不受限制”，避免连接被系统断开",
                            icon = Icons.Default.BatteryChargingFull,
                            status =
                                if (f.batteryUnrestricted) StatusLevel.Ok to "不受限制"
                                else StatusLevel.Attention to "受限制",
                            onClick = {
                                host.action {
                                    withContext(Dispatchers.Main) {
                                        check(openBackgroundSettings(activity, battery = true)) {
                                            "无法打开系统设置"
                                        }
                                    }
                                    "请在电池优化列表中允许鸿枢后台运行"
                                }
                            },
                        )
                    }
                )
                add(
                    SettingsRow("app-info") {
                        SettingsItem(
                            title = "应用信息与后台权限",
                            summary = "自启动、后台运行等厂商设置",
                            icon = Icons.Default.Settings,
                            showChevron = true,
                            onClick = {
                                host.action {
                                    withContext(Dispatchers.Main) {
                                        check(openBackgroundSettings(activity, battery = false)) {
                                            "无法打开系统设置"
                                        }
                                    }
                                    "请按手机系统配置后台运行和自启动"
                                }
                            },
                        )
                    }
                )
                add(
                    SettingsRow("hide") {
                        SettingsSwitchItem(
                            title = "在最近任务中隐藏",
                            summary = "只影响最近任务列表，不影响后台连接",
                            icon = Icons.Default.VisibilityOff,
                            checked = c.hideFromRecents,
                            enabled = !host.busy,
                            onCheckedChange = { hidden ->
                                if (hidden) confirmHide = true else setHidden(false)
                            },
                        )
                    }
                )
            },
        )
        settingsItem("help") {
            ExpandableHelp(
                "在首页返回只会退到后台，不会断开实时连接。被强制停止后需要重新打开应用。鸿枢无法检测厂商的“任务加锁”状态，请在系统中允许后台运行和自启动。"
            )
        }
    }
}
