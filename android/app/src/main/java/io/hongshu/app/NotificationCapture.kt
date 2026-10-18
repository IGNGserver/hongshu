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
)

internal data class SmsNotification(val sender: String, val body: String, val timestamp: Long)

private val summaryText =
    Regex("^(\\d+\\s*条(新|未读)?(短信|信息|消息).*|\\d+\\s+(new|unread)\\s+messages?.*)$", RegexOption.IGNORE_CASE)

internal fun parseSmsNotification(
    snapshot: NotificationSnapshot,
    smsPackages: Set<String>,
    since: Long,
): List<SmsNotification> {
    if (snapshot.packageName !in smsPackages || snapshot.groupSummary || snapshot.ongoing)
        return emptyList()
    if (snapshot.lines.isNotEmpty())
        // MessagingStyle: a null sender is the device owner's own reply.
        return snapshot.lines.mapNotNull { line ->
            val body = line.text?.trim().orEmpty()
            val sender = line.sender?.trim().orEmpty()
            if (body.isEmpty() || sender.isEmpty() || line.timestamp < since) null
            else SmsNotification(sender, body, line.timestamp)
        }
    val body = (snapshot.bigText ?: snapshot.text)?.trim().orEmpty()
    val sender = (snapshot.title ?: snapshot.conversationTitle)?.trim().orEmpty()
    val timestamp = if (snapshot.whenTime > 0) snapshot.whenTime else snapshot.postTime
    if (body.isEmpty() || sender.isEmpty() || timestamp < since || summaryText.matches(body))
        return emptyList()
    return listOf(SmsNotification(sender, body, timestamp))
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
            messages.filter { it.timestamp >= config.notificationSince }.count {
                store.queueNotification(SmsRecord("", it.sender, it.body, it.timestamp, -1))
            }
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
                .getOrDefault(emptyList())
        if (messages.isEmpty()) return
        executor.execute {
            runCatching {
                val resolved = messages.map { it.copy(sender = notificationSender(app, it.sender)) }
                if (persistNotificationSms(app, resolved, generation,
                        notificationGeneration = notificationGeneration) > 0)
                    syncNow(app, manual = true)
            }
        }
    }
}
