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

// The re-read runs this long before a held row is released, so the release finds it paired.
internal const val NOTIFICATION_REREAD_MARGIN = 8_000L

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
    val foregroundService: Boolean = false,
    val category: String? = null,
)

internal enum class NotificationClass {
    // Not an SMS notification: another app, or an SMS app's service/system notification.
    IGNORED,
    // An SMS app summarising several messages ("[3条]…"): its text is not any single SMS.
    AGGREGATE,
    // Carries individually identifiable messages.
    MESSAGES,
}

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

// HyperOS Messages folds several SMS from one sender into "[3条]<latest body>"; other ROMs put
// "(3条)" or "(3 messages)" next to the title. The count marker sits in brackets.
private const val COUNT_MARKER =
    "[\\[［(（]\\s*\\d+\\s*(条|new messages?|messages?)[^\\]］)）]{0,8}[\\]］)）]"
private val countPrefix = Regex("^\\s*$COUNT_MARKER", RegexOption.IGNORE_CASE)
private val countSuffix = Regex("$COUNT_MARKER\\s*$", RegexOption.IGNORE_CASE)

// The framework's placeholder for a foreground service without its own notification is posted
// under the service's package ("短信正在运行 · 点按即可了解详情或停止应用"). Flags identify it
// on AOSP; the wording is the fallback for ROMs that repost it without them.
private val runningTitle = Regex("(正在(后台)?运行|is running( in the background)?)$", RegexOption.IGNORE_CASE)
private val runningText =
    Regex("^((点按|点击|轻触)即可了解详情|tap for (more )?(details|info|information))", RegexOption.IGNORE_CASE)

// Categories no SMS app uses for an incoming message. A missing category is common and allowed.
private val nonMessageCategories =
    setOf(
        "service", "sys", "status", "progress", "err", "transport", "navigation", "call",
        "missed_call", "alarm", "stopwatch", "workout", "location_sharing",
    )

internal fun aggregateNotificationText(title: String, body: String): Boolean =
    summaryText.matches(body) || countPrefix.containsMatchIn(body) ||
        countPrefix.containsMatchIn(title) || countSuffix.containsMatchIn(title)

internal fun systemNotificationText(title: String, body: String): Boolean =
    runningTitle.containsMatchIn(title.trim()) || runningText.containsMatchIn(body.trim())

// True for a stored notification capture that never was a single SMS. Used to purge rows
// captured before these notifications were filtered.
internal fun notificationNoise(sender: String, body: String): Boolean =
    systemNotificationText(sender, body) || aggregateNotificationText(sender, body)

internal fun classifySmsNotification(
    snapshot: NotificationSnapshot,
    smsPackages: Set<String>,
): NotificationClass {
    if (snapshot.packageName !in smsPackages || snapshot.ongoing || snapshot.foregroundService ||
        snapshot.category?.lowercase() in nonMessageCategories)
        return NotificationClass.IGNORED
    val title = (snapshot.title ?: snapshot.conversationTitle).orEmpty()
    val body = (snapshot.bigText ?: snapshot.text).orEmpty()
    if (systemNotificationText(title, body)) return NotificationClass.IGNORED
    if (snapshot.groupSummary) return NotificationClass.AGGREGATE
    if (snapshot.lines.isNotEmpty()) return NotificationClass.MESSAGES
    return if (aggregateNotificationText(title, body)) NotificationClass.AGGREGATE
    else NotificationClass.MESSAGES
}

internal fun parseSmsNotification(
    snapshot: NotificationSnapshot,
    smsPackages: Set<String>,
    since: Long,
): List<SmsNotification> {
    if (classifySmsNotification(snapshot, smsPackages) != NotificationClass.MESSAGES)
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
    if (body.isEmpty() || sender.isEmpty() || timestamp < since) return emptyList()
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
        notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
        notification.category,
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
            }).also { store.recordCaptureEvent(CaptureSource.NOTIFICATION, "queued", it) }
        }
    }

// A notification proves an SMS arrived but cannot carry each message: HyperOS folds several into
// "[3条]…", and none has a SIM. The inbox read is what fetches them, so the fallback turns on
// new-SMS recovery once per consent. Needs READ_SMS; callers check it.
internal fun linkRecoveryToNotificationCapture(context: Context): Boolean =
    synchronized(identityLock) {
        val config = Config(context)
        if (!config.upload || !config.notificationCapture || config.recoveryLinked ||
            !hasPermission(context, Manifest.permission.READ_SMS))
            return@synchronized false
        config.recoveryLinked = true
        if (config.recoveryEnabled) return@synchronized false
        config.recoveryEnabled = true
        true
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
        val snapshot =
            runCatching { notificationSnapshot(sbn) }.getOrElse {
                config.notificationFailure = "通知解析失败"
                return
            }
        when (classifySmsNotification(snapshot, packages)) {
            NotificationClass.IGNORED -> {
                executor.execute { record(app, "ignored") }
                return
            }
            // A summary names no single message: read them from the inbox instead.
            NotificationClass.AGGREGATE -> {
                if (snapshot.postTime >= config.notificationSince) executor.execute {
                    record(app, "aggregate")
                    requestInboxRead(app)
                }
                return
            }
            NotificationClass.MESSAGES -> Unit
        }
        val messages = parseSmsNotification(snapshot, packages, config.notificationSince)
        if (messages.isEmpty()) return
        executor.execute {
            try {
                val resolved = messages.map { it.copy(sender = notificationSender(app, it.sender)) }
                if (persistNotificationSms(app, resolved, generation,
                        notificationGeneration = notificationGeneration) > 0) {
                    config.notificationFailure = ""
                    val hold = if (resolved.all { confirmedNumber(normalizeSimNumber(it.sender)) })
                        NOTIFICATION_NUMBER_HOLD else NOTIFICATION_NAME_HOLD
                    runCatching { scheduleNotificationReread(app, hold - NOTIFICATION_REREAD_MARGIN) }
                    // Await the durable enqueue result: a scheduling failure must be visible
                    // instead of silently losing the only copy of a notification-only SMS.
                    try {
                        runBlocking { requestCapture(app).await() }
                    } catch (_: Exception) {
                        config.notificationFailure = "通知已保存，调度失败，等待周期补传"
                    }
                }
            } catch (_: Exception) {
                config.notificationFailure = "通知保存失败，请检查本机存储"
            }
        }
    }

    // Diagnostics only; a failure here must never affect capture.
    private fun record(app: Context, outcome: String) {
        runCatching { Store(app).use { it.recordCaptureEvent(CaptureSource.NOTIFICATION, outcome) } }
    }

    private fun requestInboxRead(app: Context) {
        try {
            runBlocking { requestCapture(app).await() }
        } catch (_: Exception) {
            Config(app).notificationFailure = "收件箱补读调度失败，等待周期补读"
        }
    }
}
