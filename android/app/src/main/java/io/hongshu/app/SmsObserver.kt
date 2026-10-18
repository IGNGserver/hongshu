package io.hongshu.app

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony

internal const val INBOX_CHANGE_DEBOUNCE_MS = 3000L

internal fun inboxChangeTriggersScan(upload: Boolean, recovery: Boolean, history: Boolean) =
    upload && (recovery || history)

// While the process is alive, a provider change runs the inbox catch-up within seconds
// instead of waiting for the periodic worker. It does not depend on SMS_RECEIVED, so it
// covers ROMs that withhold the broadcast but still expose the inbox row.
internal object SmsInboxObserver {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var registered = false

    private val scan = Runnable {
        val context = app ?: return@Runnable
        val config = Config(context)
        if (inboxChangeTriggersScan(config.upload, config.recoveryEnabled, config.historyEnabled))
            requestCapture(context)
    }

    private val observer by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(scan)
                handler.postDelayed(scan, INBOX_CHANGE_DEBOUNCE_MS)
            }
        }
    }

    // Idempotent; registration needs READ_SMS, so callers retry after a grant.
    fun ensure(context: Context) =
        synchronized(this) {
            app = context.applicationContext
            if (registered || !hasPermission(context, Manifest.permission.READ_SMS))
                return@synchronized
            registered =
                runCatching {
                        context.applicationContext.contentResolver.registerContentObserver(
                            Telephony.Sms.CONTENT_URI,
                            true,
                            observer,
                        )
                        true
                    }
                    .getOrDefault(false)
        }
}
