package io.hongshu.app

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

// Opt-in fallback for ROMs (HyperOS "notification SMS", 5G/RCS, free network SMS) that never
// deliver SMS_RECEIVED nor expose the provider row. Only notifications posted by SMS apps
// are inspected; nothing else is read, stored or logged.

internal val KNOWN_SMS_PACKAGES =
    setOf(
        "com.android.mms", // Xiaomi/HyperOS Messages
        "com.google.android.apps.messaging",
        "com.android.messaging",
        "com.samsung.android.messaging",
    )

internal data class NotificationLine(val sender: String?, val text: String?, val timestamp: Long)

internal data class NotificationSnapshot(
    val packageName: String,
    val groupSummary: Boolean,
    val ongoing: Boolean,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val conversationTitle: String?,
    val lines: List<NotificationLine>,
    val postTime: Long,
    val whenTime: Long,
    val key: String = "",
)

internal data class SmsNotification(
    val sender: String,
    val body: String,
    val timestamp: Long,
    val identity: String = "",
    val uncertain: Boolean = true,
)

internal data class NotificationCaptureInput(
    val message: SmsRecord,
    val identity: String = "",
    val uncertain: Boolean = false,
)

private val summaryText =
    Regex("^(\\d+\\s*条(新|未读)?(短信|信息|消息).*|\\d+\\s+(new|unread)\\s+messages?.*)$", RegexOption.IGNORE_CASE)

internal fun parseSmsNotification(
    snapshot: NotificationSnapshot,
    smsPackages: Set<String>,
    since: Long,
): List<SmsNotification> {
    if (snapshot.packageName !in smsPackages || snapshot.groupSummary || snapshot.ongoing)
        return emptyList()
    if (snapshot.lines.isNotEmpty()) {
        // MessagingStyle: a null sender is the device owner's own reply.
        val occurrences = mutableMapOf<String, Int>()
        val lines = snapshot.lines.mapNotNull { line ->
            val body = line.text?.trim().orEmpty()
            val sender = line.sender?.trim().orEmpty()
            if (body.isEmpty() || sender.isEmpty() || line.timestamp < since) null
            else Triple(sender, body, line.timestamp)
        }
        val counts = lines.groupingBy { it }.eachCount()
        return lines.map { line ->
            val tuple = JSONArray(listOf(line.first, line.second, line.third)).toString()
            val occurrence = occurrences.getOrDefault(tuple, 0)
            occurrences[tuple] = occurrence + 1
            SmsNotification(
                line.first, line.second, line.third,
                sha256(JSONArray(listOf(snapshot.packageName, snapshot.key, "message", tuple, occurrence)).toString()),
                counts.getValue(line) > 1 || snapshot.key.isEmpty() || line.third <= 0,
            )
        }
    }
    val body = (snapshot.bigText ?: snapshot.text)?.trim().orEmpty()
    val sender = (snapshot.title ?: snapshot.conversationTitle)?.trim().orEmpty()
    val timestamp = if (snapshot.whenTime > 0) snapshot.whenTime else snapshot.postTime
    if (body.isEmpty() || sender.isEmpty() || timestamp < since || summaryText.matches(body))
        return emptyList()
    return listOf(SmsNotification(sender, body, timestamp,
        sha256(JSONArray(listOf(snapshot.packageName, snapshot.key, "plain", sender, body, timestamp)).toString())))
}

internal fun notificationSnapshot(sbn: StatusBarNotification): NotificationSnapshot {
    val notification = sbn.notification
    val extras = notification.extras
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    return NotificationSnapshot(
        sbn.packageName,
        notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        sbn.isOngoing,
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        style?.conversationTitle?.toString(),
        style?.messages.orEmpty().map {
            NotificationLine(it.person?.name?.toString(), it.text?.toString(), it.timestamp)
        },
        sbn.postTime,
        notification.`when`,
        sbn.key,
    )
}

internal fun smsPackages(context: Context): Set<String> =
    KNOWN_SMS_PACKAGES +
        listOfNotNull(runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull())

// Titles are usually the contact name. Resolve to a number only when it is unambiguous.
internal fun notificationSender(context: Context, title: String): String {
    val compact = normalizeSimNumber(title)
    if (confirmedNumber(compact)) return compact
    if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return title
    return runCatching {
            context.contentResolver
                .query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}=?",
                    arrayOf(title),
                    null,
                )
                ?.use { c ->
                    buildSet {
                            while (c.moveToNext())
                                c.getString(0)?.let { add(normalizeSimNumber(it)) }
                        }
                        .singleOrNull()
                }
        }
        .getOrNull() ?: title
}

internal fun persistNotificationSms(
    context: Context,
    messages: List<SmsNotification>,
    generation: Long,
    databaseName: String = "hongshu.db",
    notificationGeneration: Long,
): Int =
    synchronized(identityLock) {
        val config = Config(context)
        if (
            !config.upload ||
                !config.notificationCapture ||
                config.captureGeneration != generation ||
                config.notificationGeneration != notificationGeneration
        )
            return@synchronized 0
        Store(context, databaseName).use { store ->
            store.queueNotifications(messages.filter { it.timestamp >= config.notificationSince }.map {
                NotificationCaptureInput(SmsRecord("", it.sender, it.body, it.timestamp, -1), it.identity, it.uncertain)
            })
        }
    }

internal fun notificationListenerEnabled(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

internal fun notificationListenerIntents(context: Context): List<Intent> =
    buildList {
        if (Build.VERSION.SDK_INT >= 30)
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, SmsNotificationListener::class.java)
                            .flattenToString(),
                    )
            )
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        add(appDetailsIntent(context.packageName))
    }

class SmsNotificationListener : NotificationListenerService() {
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onListenerConnected() {
        // Messages already in the shade are still bounded by the consent time.
        runCatching { activeNotifications }.getOrNull()?.forEach(::handle)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        val app = applicationContext
        val packages = smsPackages(app)
        if (sbn.packageName !in packages) return
        val config = Config(app)
        if (!config.upload || !config.notificationCapture) return
        config.noteReceivedNotification(System.currentTimeMillis())
        val generation = config.captureGeneration
        val notificationGeneration = config.notificationGeneration
        val messages =
            runCatching { parseSmsNotification(notificationSnapshot(sbn), packages, config.notificationSince) }
                .getOrElse {
                    config.notificationFailure = "通知解析失败"
                    emptyList()
                }
        if (messages.isEmpty()) return
        executor.execute {
            try {
                val resolved = messages.map { it.copy(sender = notificationSender(app, it.sender)) }
                if (persistNotificationSms(app, resolved, generation,
                        notificationGeneration = notificationGeneration) > 0) {
                    config.notificationFailure = ""
                    // Await the durable enqueue result: a scheduling failure must be visible
                    // instead of silently losing the only copy of a notification-only SMS.
                    try {
                        runBlocking { syncNow(app, manual = true).await() }
                    } catch (_: Exception) {
                        config.notificationFailure = "通知已保存，调度失败，等待周期补传"
                    }
                }
            } catch (_: Exception) {
                config.notificationFailure = "通知保存失败，请检查本机存储"
            }
        }
    }
}
