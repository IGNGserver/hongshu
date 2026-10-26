package io.hongshu.app

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RealtimeInboxTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val config get() = Config(context)

    private class Inbox(private val rows: List<Array<Any>>, private val onQuery: () -> Unit = {}) : ContentProvider() {
        var order: String? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): MatrixCursor {
            order = sort
            onQuery()
            return MatrixCursor(arrayOf("_id", "address", "body", "date", "sub_id")).apply {
                rows.forEach { addRow(it) }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Before fun setup() {
        context.getSharedPreferences("hongshu", 0).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.READ_SMS)
        config.upload = true
        config.recoveryEnabled = true
    }

    private fun withStore(block: (String, Store) -> Unit) {
        val name = "realtime-inbox-${java.util.UUID.randomUUID()}.db"
        try { Store(context, name).use { block(name, it) } } finally { context.deleteDatabase(name) }
    }

    @Test fun newestHundredAreCapturedWithoutConsumingForwardWatermarks() = withStore { name, store ->
        store.saveSim(SimMapping(1, 0, "+123456789", "synthetic"))
        val at = System.currentTimeMillis() + 1000
        val inbox = Inbox((205L downTo 1L).map { arrayOf<Any>(it, "synthetic", "fixture-$it", at, 1) })
        ShadowContentResolver.registerProviderInternal("sms", inbox)
        assertEquals(100, captureRecentInbox(context, CancellationSignal(), name))
        assertEquals("_id DESC", inbox.order)
        assertEquals(100, store.pendingCount())
        assertEquals(0L, store.providerWatermark())
        assertEquals(0L, store.recoveryWatermark())
        assertEquals(0, captureRecentInbox(context, CancellationSignal(), name))
        assertFalse(store.captureProvider(205, SmsRecord("+123456789", "synthetic", "fixture-205", at, 1)))
        assertEquals(100, store.pendingCount())
    }

    @Test fun consentIsEnforcedEvenIfAProviderIgnoresSelection() = withStore { name, store ->
        val at = System.currentTimeMillis() + 1000
        ShadowContentResolver.registerProviderInternal("sms", Inbox(listOf(
            arrayOf<Any>(2L, "synthetic", "fresh", at, -1),
            arrayOf<Any>(1L, "synthetic", "old", 1L, -1),
        )))
        assertEquals(1, captureRecentInbox(context, CancellationSignal(), name))
        val pending = store.localMessages(sender = "synthetic").single()
        assertEquals("fresh", pending.body)
        assertEquals("", pending.receiver)
        assertFalse(pending.historical)
        assertEquals(0L, store.recoveryWatermark())
    }

    @Test fun disablingCaptureDuringQueryCannotCommitItsResults() = withStore { name, store ->
        val at = System.currentTimeMillis() + 1000
        ShadowContentResolver.registerProviderInternal("sms", Inbox(
            listOf(arrayOf<Any>(1L, "synthetic", "fixture", at, 1)),
            onQuery = { config.upload = false },
        ))
        assertEquals(0, captureRecentInbox(context, CancellationSignal(), name))
        assertEquals(0, store.pendingCount())
    }

    @Test fun revokedPermissionAndDisabledRecoveryNeverQueryProvider() = withStore { name, _ ->
        ShadowContentResolver.registerProviderInternal("sms", Inbox(emptyList()) { fail("unexpected query") })
        config.recoveryEnabled = false
        assertEquals(0, captureRecentInbox(context, CancellationSignal(), name))
        config.recoveryEnabled = true
        shadowOf(context).denyPermissions(Manifest.permission.READ_SMS)
        assertEquals(0, captureRecentInbox(context, CancellationSignal(), name))
    }
}
