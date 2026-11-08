package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

/**
 * Executable inventory of who owns which table in [Store].
 *
 * `Store.kt` is being split by transaction ownership. Before any code moves, the
 * ownership decision has to be written down somewhere a machine can check, otherwise a
 * table silently ends up owned by two repositories (or by none) and the split quietly
 * changes locking. `docs/ANDROID_STORE_TRANSACTIONS.md` explains the reasoning; this test
 * is the copy that fails when reality drifts from it.
 *
 * When you add a table to the schema you must assign it an owner here, and when you move
 * a table to another repository you must move it here in the same change.
 */
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoreTableOwnershipTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Tables SQLite manages itself, not part of the application schema. */
    private val systemTables = setOf("android_metadata", "sqlite_sequence")

    private fun schemaTables(): Set<String> {
        val name = "ownership-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        return Store(context, name).use { store ->
            val tables = mutableSetOf<String>()
            store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { rows ->
                while (rows.moveToNext()) tables.add(rows.getString(0))
            }
            tables
        } - systemTables
    }

    @Test fun everySchemaTableHasExactlyOneOwningRepository() {
        val schema = schemaTables()
        val inventory = StoreTableOwnership.inventory
        val unowned = (schema - inventory.keys).sorted()
        val stale = (inventory.keys - schema).sorted()
        assertEquals("tables with no assigned owner: they must be added to the inventory", emptyList<String>(), unowned)
        assertEquals("inventory lists tables the schema no longer creates", emptyList<String>(), stale)
    }

    @Test fun inventoryCoversEveryDocumentedRepository() {
        val owners = StoreTableOwnership.inventory.values.toSet()
        assertEquals(
            "each split target needs at least one table, or the split has no reason to exist",
            StoreTableOwnership.repositories.toSet(),
            owners,
        )
    }

    @Test fun sharedKeyValueTableIsListedPerDomain() {
        // meta is one physical table but each key belongs to one domain; the split must not
        // hand the whole table to a single repository and let others write into it.
        assertEquals(
            StoreTableOwnership.Repository.Shared,
            StoreTableOwnership.inventory["meta"],
        )
        assertTrue(
            "shared ownership must name the domains that use it",
            StoreTableOwnership.sharedKeys.size >= 4,
        )
    }
}

/** Single source of truth for the split, mirrored in docs/ANDROID_STORE_TRANSACTIONS.md. */
internal object StoreTableOwnership {
    enum class Repository { Capture, UploadOutbox, ReadReceipt, InboxProjection, Notifications, Contacts, Calls, Shared }

    val repositories = listOf(
        Repository.Capture,
        Repository.UploadOutbox,
        Repository.ReadReceipt,
        Repository.InboxProjection,
        Repository.Notifications,
        Repository.Contacts,
        Repository.Calls,
        Repository.Shared,
    )

    val inventory: Map<String, Repository> = mapOf(
        // Capture: what the phone observed, before anything is uploaded.
        "broadcast_inbox" to Repository.Capture,
        "captures" to Repository.Capture,
        "capture_events" to Repository.Capture,
        "incomplete_provider" to Repository.Capture,
        // Upload outbox: what still has to reach the hub.
        "outbox" to Repository.UploadOutbox,
        "upload_fingerprints" to Repository.UploadOutbox,
        "incomplete_sent" to Repository.UploadOutbox,
        // Read receipts: durable "marked read" queue and its watermark.
        "read_outbox" to Repository.ReadReceipt,
        "conversation_reads" to Repository.ReadReceipt,
        // Inbox projection: the read model rendered by the UI.
        "inbox" to Repository.InboxProjection,
        "preview_messages" to Repository.InboxProjection,
        "message_analysis" to Repository.InboxProjection,
        "local_conversation_heads" to Repository.InboxProjection,
        // Notifications: what was already surfaced, and pending OTP actions.
        "notification_receipts" to Repository.Notifications,
        "otp_intents" to Repository.Notifications,
        // Contacts: the locally resolved name snapshot.
        "contact_snapshot" to Repository.Contacts,
        // Calls: its own capture -> outbox -> projection chain, created in CallLog.kt and
        // CallView.kt rather than Store.kt, which is why it is easy to overlook.
        "calls" to Repository.Calls,
        "call_outbox" to Repository.Calls,
        "call_ledger" to Repository.Calls,
        // Device registration mirrored from the hub.
        "sims" to Repository.Shared,
        // Key/value settings and cursors, partitioned by key prefix.
        "meta" to Repository.Shared,
    )

    /** Domains that own slices of [inventory]'s shared tables. */
    val sharedKeys = listOf("capture", "upload", "read", "sync")
}
