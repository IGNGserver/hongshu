@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package io.hongshu.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// Call log viewing (docs/CALL_LOG.md): the hub's call stream, cached locally through
// GET /api/calls/sync and shown as a read-only list and single-record detail.

enum class HomeView { SMS, CALLS }

data class CachedCall(
    val id: Long,
    val deviceId: String,
    val receiver: String,
    val number: String,
    val contact: String,
    val type: String,
    val timestamp: Long,
    val duration: Long,
    val subscriptionId: Int,
    val video: Boolean,
    val presentation: String,
    val location: String,
    val historical: Boolean,
)

// ---- Labels shared in spirit with web/app.js ----

internal val callTypeLabels =
    mapOf(
        "incoming" to "呼入",
        "outgoing" to "呼出",
        "missed" to "未接来电",
        "rejected" to "已拒接",
        "blocked" to "已拦截",
        "voicemail" to "语音信箱",
        "answered_externally" to "其他设备已接听",
        "unknown" to "通话",
    )
internal val callPresentationLabels = mapOf("restricted" to "隐藏号码", "unknown" to "未知号码", "payphone" to "公用电话")

internal fun callTypeLabel(type: String): String = callTypeLabels[type] ?: callTypeLabels.getValue("unknown")

internal fun isMissedCall(type: String): Boolean = type == "missed" || type == "rejected" || type == "blocked"

internal fun formatCallDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return when {
        h > 0 -> "$h 小时 $m 分 $s 秒"
        m > 0 -> "$m 分 $s 秒"
        else -> "$s 秒"
    }
}

internal fun callSummary(call: CachedCall): String {
    val parts = mutableListOf(callTypeLabel(call.type))
    if (call.video) parts += "视频"
    if (!isMissedCall(call.type)) {
        when {
            call.duration > 0 -> parts += formatCallDuration(call.duration)
            call.type == "incoming" || call.type == "outgoing" -> parts += "未接通"
        }
    }
    return parts.joinToString(" · ")
}

internal fun callParty(call: CachedCall, name: String = ""): String =
    name.ifEmpty { call.number.ifEmpty { callPresentationLabels[call.presentation] ?: "未知号码" } }

internal fun callFacts(call: CachedCall, line: String, device: String): List<Pair<String, String>> =
    listOf(
        "类型" to "${callTypeLabel(call.type)} · ${if (call.video) "视频通话" else "语音通话"}",
        "时间" to SimpleDateFormat("yyyy年M月d日 EEE HH:mm:ss", Locale.CHINA).format(Date(call.timestamp)),
        "通话时长" to if (call.duration > 0) formatCallDuration(call.duration) else "未接通",
        "号码" to call.number.ifEmpty { callPresentationLabels[call.presentation] ?: "未知号码" },
        "本机线路" to line.ifEmpty { "未知线路" },
        "来源手机" to device,
        "归属地" to call.location,
        "记录来源" to if (call.historical) "开启同步前的历史记录" else "",
    ).filter { it.second.isNotEmpty() }

// ---- Local cache (schema v17) ----

internal fun createCallCache(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS calls (id INTEGER PRIMARY KEY,device TEXT NOT NULL,receiver TEXT NOT NULL,number TEXT NOT NULL,type TEXT NOT NULL,timestamp INTEGER NOT NULL,duration INTEGER NOT NULL,sub INTEGER NOT NULL,video INTEGER NOT NULL DEFAULT 0,presentation TEXT NOT NULL,location TEXT NOT NULL DEFAULT '',historical INTEGER NOT NULL DEFAULT 0)"
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS calls_timeline ON calls(timestamp,id)")
    db.execSQL("INSERT OR IGNORE INTO meta VALUES ('call_cursor',0)")
}

internal fun Store.callCursor(): Long =
    readableDatabase.rawQuery("SELECT value FROM meta WHERE key='call_cursor'", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

// One /calls/sync page and its cursor in one transaction, only for the epoch already recorded.
internal fun Store.applyCallPage(data: JSONObject): Boolean {
    if (!epochSeen() || epoch() != data.getLong("epoch")) return false
    val calls = data.getJSONArray("calls")
    val cursor = data.getLong("cursor")
    check(cursor >= callCursor()) { "Call cursor moved backwards" }
    val db = writableDatabase
    db.beginTransaction()
    try {
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val id = c.getLong("id")
            check(id in 1..cursor) { "Call outside the page" }
            db.execSQL(
                "INSERT OR REPLACE INTO calls (id,device,receiver,number,type,timestamp,duration,sub,video,presentation,location,historical) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(
                    id,
                    c.getString("device_id"),
                    c.getString("receiver"),
                    c.getString("number"),
                    c.getString("type"),
                    c.getLong("timestamp"),
                    c.getLong("duration"),
                    c.getInt("subscription_id"),
                    if (c.getBoolean("video")) 1 else 0,
                    c.getString("presentation"),
                    c.optString("location"),
                    if (c.getBoolean("historical")) 1 else 0,
                ),
            )
        }
        db.execSQL("UPDATE meta SET value=? WHERE key='call_cursor'", arrayOf(cursor))
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    if (calls.length() > 0) Store.changes.value++
    return true
}

internal fun Store.resetCallCache() {
    val db = writableDatabase
    db.beginTransaction()
    try {
        db.delete("calls", null, null)
        db.execSQL("UPDATE meta SET value=0 WHERE key='call_cursor'")
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    Store.changes.value++
}

private const val CALL_COLUMNS =
    "k.id,k.device,k.receiver,k.number,COALESCE(s.name,''),k.type,k.timestamp,k.duration,k.sub,k.video,k.presentation,k.location,k.historical"
private const val CALL_JOIN = " LEFT JOIN contact_snapshot s ON k.number<>'' AND s.phone=k.number"

private fun android.database.Cursor.call() =
    CachedCall(
        id = getLong(0),
        deviceId = getString(1),
        receiver = getString(2),
        number = getString(3),
        contact = getString(4),
        type = getString(5),
        timestamp = getLong(6),
        duration = getLong(7),
        subscriptionId = getInt(8),
        video = getInt(9) != 0,
        presentation = getString(10),
        location = getString(11),
        historical = getInt(12) != 0,
    )

internal fun Store.cachedCalls(limit: Int): List<CachedCall> =
    readableDatabase.rawQuery(
        "SELECT $CALL_COLUMNS FROM calls k$CALL_JOIN ORDER BY k.timestamp DESC,k.id DESC LIMIT ?",
        arrayOf(limit.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.call()) } }

internal fun Store.cachedCall(id: Long): CachedCall? =
    readableDatabase.rawQuery("SELECT $CALL_COLUMNS FROM calls k$CALL_JOIN WHERE k.id=?", arrayOf(id.toString()))
        .use { c -> if (c.moveToFirst()) c.call() else null }

// Pages /calls/sync after the message sync. Returns true when a bounded pass left more pages.
internal fun syncCallLog(store: Store, request: (String) -> JSONObject, maxPages: Int = 5): Boolean {
    if (!store.hubSupportsCallLog()) return false
    repeat(maxPages) {
        val data =
            try {
                request("/calls/sync?after=${store.callCursor()}&limit=200")
            } catch (e: ApiException) {
                // The cursor is past the hub's call head with an unchanged epoch: start over.
                if (e.code != "epoch_changed") throw e
                store.resetCallCache()
                return true
            }
        if (!store.applyCallPage(data)) return false
        if (!data.getBoolean("more")) return false
    }
    return true
}

// ---- UI ----

@Composable
internal fun HomeViewSwitch(view: HomeView, onChange: (HomeView) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(Triple(HomeView.SMS, "短信", Icons.Default.Sms), Triple(HomeView.CALLS, "通话", Icons.Default.Call))
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEachIndexed { index, (value, label, icon) ->
            SegmentedButton(
                selected = view == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            ) {
                Text(label)
            }
        }
    }
}

internal fun callIcon(call: CachedCall): ImageVector =
    when (call.type) {
        "outgoing" -> Icons.AutoMirrored.Filled.CallMade
        "missed", "rejected" -> Icons.AutoMirrored.Filled.CallMissed
        "blocked" -> Icons.Default.Block
        "voicemail" -> Icons.Default.Voicemail
        else -> Icons.AutoMirrored.Filled.CallReceived
    }

private fun dayLabel(timestamp: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(timestamp))

private fun clock(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
internal fun CallListPane(
    model: HomeModel,
    actions: HomeActions,
    selected: Long?,
    compactHeight: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.statusBarsPadding()
                .padding(start = 24.dp, end = 8.dp, top = if (compactHeight) 4.dp else 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("通话记录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.onOpenSettings(SettingsPage.Overview) }) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }
        HomeViewSwitch(model.view, actions.onViewChange)
        PullToRefreshBox(
            isRefreshing = model.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            val calls = model.calls
            when {
                calls == null -> Box(Modifier.fillMaxSize())
                calls.isEmpty() -> CallEmptyState(Icons.Default.Call, "暂无通话记录", "在 Android 手机的“采集方式”中开启“通话记录”后，通话会出现在这里")
                else -> {
                    val state = rememberLazyListState()
                    val nearEnd by remember {
                        derivedStateOf {
                            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            last >= state.layoutInfo.totalItemsCount - 10
                        }
                    }
                    LaunchedEffect(nearEnd, calls.size) {
                        if (nearEnd && calls.size >= model.callLimit) actions.onLoadMoreCalls()
                    }
                    val rows = remember(calls) {
                        buildList<Any> {
                            var day = ""
                            for (call in calls) {
                                val label = dayLabel(call.timestamp)
                                if (label != day) {
                                    add(label)
                                    day = label
                                }
                                add(call)
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = state) {
                        items(rows, key = { if (it is CachedCall) "call:${it.id}" else "day:$it" }) { row ->
                            if (row is CachedCall)
                                CallRow(
                                    row,
                                    receiverLabel = model.sims.find { it.phone == row.receiver }?.label,
                                    isSelected = selected == row.id,
                                    onClick = { actions.onSelectCall(row.id) },
                                )
                            else
                                Text(
                                    row as String,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                                )
                        }
                        item { Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallRow(call: CachedCall, receiverLabel: String?, isSelected: Boolean, onClick: () -> Unit) {
    val local = if (call.number.isEmpty()) null else rememberLocalContact(call.number)
    val name = local?.name ?: call.contact
    val missed = isMissedCall(call.type)
    val accent = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(name, call.number, 48.dp, local?.photo)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    callParty(call, name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (missed && !isSelected) MaterialTheme.colorScheme.error else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(clock(call.timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(callIcon(call), contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Text(
                    listOfNotNull(callSummary(call), receiverLabel).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CallDetailPane(
    id: Long,
    model: HomeModel,
    showBack: Boolean,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    maxContentWidth: androidx.compose.ui.unit.Dp,
) {
    val listed = model.calls?.find { it.id == id }
    val call by androidx.compose.runtime.produceState(listed, id, model.changes) {
        if (value == null)
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                model.repo.store.readUnlessClosed(null) { cachedCall(id) }
            }
    }
    val shown = listed ?: call
    Column(Modifier.fillMaxSize()) {
        val local = shown?.number?.takeIf { it.isNotEmpty() }?.let { rememberLocalContact(it) }
        val name = local?.name ?: shown?.contact.orEmpty()
        Row(
            Modifier.then(if (showBack) Modifier.statusBarsPadding() else Modifier)
                .padding(start = if (showBack) 4.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showBack)
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            if (shown != null) {
                Avatar(name, shown.number, 40.dp, local?.photo)
                Column(Modifier.weight(1f)) {
                    Text(callParty(shown, name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (name.isNotEmpty()) shown.number else callTypeLabel(shown.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (shown.number.isNotEmpty()) {
                    val context = LocalContext.current
                    IconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("号码", shown.number))
                        onMessage("已复制号码 ${shown.number}")
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制号码")
                    }
                }
            } else Spacer(Modifier.weight(1f))
        }
        if (shown == null) {
            CallEmptyState(Icons.Default.Call, "该通话记录已不存在", "数据库可能已回档，返回列表即可")
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val missed = isMissedCall(shown.type)
            Surface(
                shape = RoundedCornerShape(50),
                color = if (missed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (missed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(if (shown.video) Icons.Default.Videocam else callIcon(shown), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(callSummary(shown), style = MaterialTheme.typography.labelLarge)
                }
            }
            val line =
                model.sims.find { it.phone == shown.receiver }?.let { "${it.label} ${it.phone}" } ?: shown.receiver
            // Android keeps no names for other devices; say whether it was this phone.
            val device = if (shown.deviceId == model.repo.config.deviceId) model.repo.config.deviceName.ifEmpty { "本机" } else "另一台手机"
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = maxContentWidth).fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((term, value) in callFacts(shown, line, device)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(term, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CallEmptyState(icon: ImageVector, title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CallPlaceholder() = CallEmptyState(Icons.Default.Call, "选择一条通话记录", "查看时间、时长、线路与来源手机")
