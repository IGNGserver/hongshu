package io.hongshu.app

import android.Manifest
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

abstract class RealtimeCaptureContract {
    protected open fun providerResolver(provider: ContentProvider): ContentResolver =
        ContentResolver.wrap(provider)

    @Test
    fun uploadSelectionAndRealtimeCaptureNeverWaitForSimService() =
        fixture { context, config, name ->
            config.singleSimSubscription = 7
            val noSim =
                object : ContextWrapper(context) {
                    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                        check(permission != Manifest.permission.READ_PHONE_STATE) {
                            "SIM permission check on short path"
                        }
                        return super.checkPermission(permission, pid, uid)
                    }

                    override fun getSystemService(name: String): Any? {
                        check(name != Context.TELEPHONY_SUBSCRIPTION_SERVICE) {
                            "SIM service on short path"
                        }
                        return super.getSystemService(name)
                    }
                }
            Store(noSim, name).use { it.saveSim(SimMapping(7, 0, "+1234567890", "SIM1")) }
            assertTrue(persistIncomingSms(noSim, message(), config.captureGeneration, 100, name))
            assertTrue(persistIncomingSms(noSim, message(-1), config.captureGeneration, 100, name))
            Store(noSim, name).use { store ->
                store.resolvePendingFallback() // No legacy rows; unknown broadcasts remain unknown.
                assertEquals(2, store.pendingCount())
                assertEquals(1, store.pending(allowFallback = false).size)
                assertEquals(
                    "+1234567890",
                    store.pending(allowFallback = false).single().second.receiver,
                )
            }
        }

    @Test
    fun actualMultipartPdusAreParsedInOrderWithoutReadingHistory() {
        fun pdu(text: String, part: Int): ByteArray {
            val payload =
                byteArrayOf(5, 0, 3, 42, 2, part.toByte()) + text.toByteArray(Charsets.UTF_16BE)
            // Synthetic SMS-DELIVER, UCS2, 10086, concatenation UDH; no personal data.
            return byteArrayOf(
                0,
                0x40,
                5,
                0x81.toByte(),
                0x01,
                0x80.toByte(),
                0xf6.toByte(),
                0,
                8,
                0x62,
                0x01,
                0x31,
                0x21,
                0x43,
                0x65,
                0,
                payload.size.toByte(),
            ) + payload
        }
        val intent =
            Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                .putExtra("pdus", arrayOf(pdu("synthetic 长", 1), pdu("短信😀", 2)))
                .putExtra("format", "3gpp")
                .putExtra("subscription", 7)
                .putExtra("slot", 0)
        val parsed = parseIncomingSms(intent)!!
        assertEquals("10086", parsed.sender)
        assertEquals("synthetic 长短信😀", parsed.body)
        assertEquals(7, parsed.subscription)
        assertEquals(0, parsed.slot)
    }

    @Test
    fun snapshotSurvivesSimChangeAndAckLedgerPreventsDuplicateRequeue() =
        fixture { context, config, name ->
            Store(context, name).use { it.saveSim(SimMapping(7, 0, "+1234567890", "SIM1")) }
            assertTrue(persistIncomingSms(context, message(), config.captureGeneration, 100, name))
            Store(context, name).use { store ->
                store.saveSim(SimMapping(7, 0, "+9876543210", "changed"))
                assertEquals("+1234567890", store.localPending().single().receiver)
                val queued = store.pending().single()
                // Without an ACK the row remains across reopen/retry.
                assertEquals(1, store.pendingCount())
                store.ack(listOf(queued.first))
            }
            assertTrue(persistIncomingSms(context, message(), config.captureGeneration, 101, name))
            Store(context, name).use { store ->
                assertEquals(0, store.pendingCount())
                assertEquals(1L, store.captureDiagnostics().duplicates)
                assertFalse(
                    store.captureProvider(
                        500,
                        SmsRecord(
                            "+1234567890",
                            "10086",
                            message().body,
                            message().timestamp + 30000,
                            7,
                        ),
                        true,
                    )
                )
                assertEquals(500L, store.providerWatermark())
            }
        }

    @Test
    fun storageFailureRollsBackQueueLedgerAndDiagnosticsTogether() = fixture { context, _, name ->
        Store(context, name).use { store ->
            store.writableDatabase.execSQL(
                "CREATE TRIGGER fail_queue BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END"
            )
            try {
                store.queueRealtime(SmsRecord("", "10086", "synthetic", 100, 7), 0, 100)
                fail("Failed storage must not report success")
            } catch (_: android.database.sqlite.SQLiteException) {}
            assertEquals(0, store.pendingCount())
            assertEquals(CaptureDiagnostics(), store.captureDiagnostics())
            store.writableDatabase.execSQL("DROP TRIGGER fail_queue")
            assertTrue(store.queueRealtime(SmsRecord("", "10086", "synthetic", 100, 7), 0, 100))
        }
    }

    private class Inbox(val queryRows: (String?, Array<out String>?) -> Cursor) :
        ContentProvider() {
        override fun onCreate() = true

        override fun getType(uri: Uri) = "vnd.android.cursor.dir/sms"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ) = queryRows(selection, selectionArgs)
    }

    private fun fixture(
        read: Boolean = false,
        receive: Boolean = true,
        query: () -> Cursor = { error("Realtime capture must not query provider") },
        queryRequest: ((String?, Array<out String>?) -> Cursor)? = null,
        test: (Context, Config, String) -> Unit,
    ) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefs = base.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
        val saved = prefs.all
        val name = "realtime-${java.util.UUID.randomUUID()}.db"
        val provider =
            Inbox(queryRequest ?: { _, _ -> query() }).apply {
                attachInfo(base, ProviderInfo().apply { authority = "sms" })
            }
        val resolver = providerResolver(provider)
        val context =
            object : ContextWrapper(base) {
                override fun getContentResolver() = resolver

                override fun getApplicationContext(): Context = this

                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    when (permission) {
                        Manifest.permission.READ_SMS ->
                            if (read) PackageManager.PERMISSION_GRANTED
                            else PackageManager.PERMISSION_DENIED
                        Manifest.permission.RECEIVE_SMS ->
                            if (receive) PackageManager.PERMISSION_GRANTED
                            else PackageManager.PERMISSION_DENIED
                        Manifest.permission.READ_PHONE_STATE -> PackageManager.PERMISSION_DENIED
                        else -> super.checkPermission(permission, pid, uid)
                    }
            }
        try {
            prefs.edit().clear().commit()
            val config = Config(context)
            config.upload = true
            config.historyEnabled = true
            test(context, config, name)
        } finally {
            base.deleteDatabase(name)
            val edit = prefs.edit().clear()
            for ((key, value) in saved) when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
            }
            edit.commit()
        }
    }

    private fun message(sub: Int = 7) =
        IncomingSms("10086", combineParts(listOf("synthetic long ", "短信分段")), 1700000000000, sub, 0)

    @Test
    fun recoveryNeedsExplicitConsentAndDoesNotEnableHistory() =
        fixture(read = true) { context, config, name ->
            config.historyEnabled = false
            assertFalse(config.recoveryEnabled)
            assertTrue(recoverRecentInbox(context, name).contains("已关闭"))
            config.recoveryEnabled = true
            assertFalse(config.historyEnabled)
            assertTrue(config.recoverySince > 0)
            config.upload = false
            assertTrue(recoverRecentInbox(context, name).contains("已关闭"))
        }

    @Test
    fun recoveryPermissionFailureIsVisibleWithoutBlockingBroadcasts() =
        fixture { context, config, name ->
            config.historyEnabled = false
            config.recoveryEnabled = true
            assertTrue(recoverRecentInbox(context, name).contains("权限"))
            assertEquals(HistoryState.NO_PERMISSION, config.recoveryState)
            assertTrue(persistIncomingSms(context, message(), config.captureGeneration, 100, name))
            Store(context, name).use { assertEquals(1, it.pendingCount()) }
        }

    @Test
    fun missedBroadcastIsRecoveredWithoutImportingOlderHistoryOrAdvancingHistoryWatermark() {
        var queries = 0
        fixture(
            read = true,
            receive = false,
            queryRequest = { selection, args ->
                queries++
                assertEquals("_id>? AND date>=?", selection)
                val after = args!![0].toLong()
                val since = args[1].toLong()
                MatrixCursor(arrayOf("_id", "address", "body", "date", "sub_id")).apply {
                    for (id in 1L..2L) {
                        val date = if (id == 1L) since - 1 else since + 1
                        if (id > after && date >= since)
                            addRow(arrayOf<Any>(id, "10086", "synthetic $id", date, 7))
                    }
                }
            },
        ) { context, config, name ->
            config.historyEnabled = false
            config.recoveryEnabled = true
            assertTrue(recoverRecentInbox(context, name).contains("新增 1 条"))
            Store(context, name).use { store ->
                assertEquals(1, store.pendingCount())
                assertEquals("synthetic 2", store.localPending().single().body)
                assertFalse(store.localPending().single().historical)
                assertEquals(2L, store.recoveryWatermark())
                assertEquals(0L, store.providerWatermark())
                assertEquals(CaptureDiagnostics(), store.captureDiagnostics())
            }
            assertTrue(recoverRecentInbox(context, name).contains("新增 0 条"))
            assertEquals(2, queries)
        }
    }

    @Test
    fun recoveryResumesInPagesAndLaterHistoryStillImportsOldRows() {
        fixture(
            read = true,
            queryRequest = { selection, args ->
                val after = args!![0].toLong()
                val since =
                    Config(ApplicationProvider.getApplicationContext<Context>()).recoverySince
                MatrixCursor(arrayOf("_id", "address", "body", "date", "sub_id")).apply {
                    for (id in 1L..1206L) {
                        val date = if (id == 1L) since - 1 else since + id
                        if (id > after && (selection == "_id>?" || date >= args[1].toLong()))
                            addRow(arrayOf<Any>(id, "10086", "synthetic $id", date, 7))
                    }
                }
            },
        ) { context, config, name ->
            config.historyEnabled = false
            config.recoveryEnabled = true
            recoverRecentInbox(context, name)
            assertTrue(config.recoveryPending)
            Store(context, name).use { assertEquals(1000, it.pendingCount()) }
            recoverRecentInbox(context, name)
            assertFalse(config.recoveryPending)
            Store(context, name).use { assertEquals(1205, it.pendingCount()) }
            config.historyEnabled = true
            catchUpInbox(context, name)
            assertTrue(config.inboxScanPending)
            catchUpInbox(context, name)
            Store(context, name).use { assertEquals(1206, it.pendingCount()) }
        }
    }

    @Test
    fun providerRowThatWasEmptyWhenFirstScannedIsRetriedByItsId() {
        var scans = 0
        var retrySelection: String? = null
        fixture(
            read = true,
            queryRequest = { selection, _ ->
                scans++
                if (scans == 2) retrySelection = selection
                MatrixCursor(arrayOf("_id", "address", "body", "date", "sub_id")).apply {
                    if (scans <= 2)
                        addRow(arrayOf<Any>(1L, "10086", if (scans == 1) "" else "synthetic recovered", 100L, 7))
                }
            },
        ) { context, config, name ->
            config.historyEnabled = true
            assertTrue(catchUpInbox(context, name).contains("新增 0 条"))
            Store(context, name).use {
                assertEquals(0, it.pendingCount())
                assertEquals(1L, it.providerWatermark())
            }
            assertTrue(catchUpInbox(context, name).contains("新增 1 条"))
            assertTrue("retry must revisit the incomplete id: $retrySelection", retrySelection!!.contains("_id IN (1)"))
            Store(context, name).use {
                assertEquals("synthetic recovered", it.localPending().single().body)
                // A captured row is no longer retried.
                assertTrue(it.incompleteProviderIds().isEmpty())
            }
        }
    }

    @Test
    fun providerHeadRegressionResetsTheScanWatermark() {
        fixture { context, _, name ->
            Store(context, name).use { store ->
                store.captureProvider(50, SmsRecord("+1234567890", "10086", "synthetic", 100, 7), advance = true)
                assertEquals(50L, store.providerWatermark())
                store.observeProviderHead(9)
                assertEquals(0L, store.providerWatermark())
                store.captureProvider(60, SmsRecord("+1234567890", "10086", "synthetic two", 200, 7), advance = true)
                assertEquals(60L, store.providerWatermark())
                store.observeProviderHead(60)
                assertEquals(60L, store.providerWatermark())
            }
        }
    }

    @Test
    fun recoveryAndBroadcastShareDedupLedgerAfterAck() {
        val timestamp = System.currentTimeMillis() + 1000
        fixture(
            read = true,
            queryRequest = { _, _ ->
                MatrixCursor(arrayOf("_id", "address", "body", "date", "sub_id")).apply {
                    addRow(arrayOf<Any>(1, "10086", message().body, timestamp, 7))
                }
            },
        ) { context, config, name ->
            config.historyEnabled = false
            config.recoveryEnabled = true
            Store(context, name).use { it.saveSim(SimMapping(7, 0, "+1234567890", "SIM1")) }
            recoverRecentInbox(context, name)
            Store(context, name).use { it.ack(it.localPending().map { row -> row.key }) }
            assertTrue(
                persistIncomingSms(
                    context,
                    message().copy(timestamp = timestamp),
                    config.captureGeneration,
                    timestamp,
                    name,
                )
            )
            Store(context, name).use {
                assertEquals(0, it.pendingCount())
                assertEquals(1L, it.captureDiagnostics().duplicates)
            }
        }
    }

    @Test
    fun recoveryFailureDoesNotClaimEmptyInboxOrLoseQueuedBroadcast() =
        fixture(read = true, query = { throw SecurityException("synthetic") }) {
            context,
            config,
            name ->
            config.recoveryEnabled = true
            assertTrue(recoverRecentInbox(context, name).contains("失败"))
            assertEquals(HistoryState.FAILED, config.recoveryState)
            assertTrue(persistIncomingSms(context, message(), config.captureGeneration, 100, name))
        }

    @Test
    fun changingRecoveryConsentCancelsInFlightProviderCommit() {
        fixture(
            read = true,
            query = {
                val c = Config(ApplicationProvider.getApplicationContext<Context>())
                c.recoveryEnabled = false
                c.recoveryEnabled = true
                MatrixCursor(arrayOf("_id", "address", "body", "date", "sub_id")).apply {
                    addRow(arrayOf<Any>(1, "10086", "synthetic", System.currentTimeMillis(), 7))
                }
            },
        ) { context, config, name ->
            config.recoveryEnabled = true
            assertTrue(recoverRecentInbox(context, name).contains("取消"))
            Store(context, name).use {
                assertEquals(0, it.pendingCount())
                assertEquals(0L, it.recoveryWatermark())
            }
        }
    }

    @Test
    fun broadcastCompletionWaitsForDurableEnqueueAndSchedulingFailureKeepsSms() =
        fixture { context, config, name ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val outcome = AtomicReference<Throwable?>()
            val worker = thread {
                try {
                    runBlocking {
                        saveAndScheduleIncomingSms(
                            context,
                            message(),
                            config.captureGeneration,
                            100,
                            name,
                        ) {
                            Store(context, name).use { assertEquals(1, it.pendingCount()) }
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            throw IllegalStateException("synthetic scheduling failure")
                        }
                    }
                } catch (e: Throwable) {
                    outcome.set(e)
                } finally {
                    completed.countDown()
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(
                    "Broadcast finished before enqueue",
                    completed.await(100, TimeUnit.MILLISECONDS),
                )
            } finally {
                release.countDown()
                worker.join(10000)
            }
            assertFalse(worker.isAlive)
            outcome.get()?.let { throw it }
            assertEquals(CaptureFailure.SCHEDULING, config.captureFailure)
            Store(context, name).use { assertEquals(1, it.pendingCount()) }
        }

    @Test
    fun reopeningMasterCaptureDoesNotRecoverDisabledInterval() =
        fixture(read = true) { context, config, name ->
            config.recoveryEnabled = true
            val prefs = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
            prefs.edit().putLong("recovery_since", 1).commit()
            val oldGeneration = config.recoveryGeneration
            config.upload = false
            config.upload = true
            assertTrue(config.recoverySince > 1)
            assertTrue(config.recoveryGeneration > oldGeneration)
            assertEquals(HistoryState.NOT_SCANNED, config.recoveryState)
            Store(context, name).use { assertEquals(0, it.pendingCount()) }
        }

    @Test
    fun failedPersistenceDoesNotEnqueueOrMisreportSchedulingFailure() =
        fixture { context, config, name ->
            Store(context, name).use {
                it.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_queue BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'synthetic'); END"
                )
            }
            runBlocking {
                saveAndScheduleIncomingSms(
                    context,
                    message(),
                    config.captureGeneration,
                    100,
                    name,
                ) {
                    fail("Must not enqueue after failed storage")
                }
            }
            assertEquals(CaptureFailure.STORAGE, config.captureFailure)
            Store(context, name).use { assertEquals(0, it.pendingCount()) }
        }

    @Test
    fun malformedBroadcastEntryRemainsVisibleBeforeSuccessfulPersistence() =
        fixture { context, config, name ->
            SmsReceiver().onReceive(context, Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION))
            Store(context, name).use {
                assertEquals(CaptureDiagnostics(), it.captureDiagnostics())
                assertEquals(1L, config.receivedBroadcasts)
                assertTrue(config.lastReceivedBroadcast > 0)
                assertTrue(realtimeSmsStatus(context, it.captureDiagnostics()).contains("解析失败"))
            }
        }

    @Test
    fun receiveOnlySavesOfflineAndSurvivesRestartWithoutProviderOrSimCalls() =
        fixture { context, config, name ->
            assertTrue(catchUpInbox(context, name).contains("权限"))
            assertEquals(HistoryState.NO_PERMISSION, config.historyState)
            Store(context, name).use { it.saveSim(SimMapping(7, 0, "+1234567890", "SIM1")) }
            assertTrue(persistIncomingSms(context, message(), config.captureGeneration, 200, name))
            assertTrue(persistIncomingSms(context, message(), config.captureGeneration, 201, name))
            Store(context, name).use { store ->
                assertEquals(1, store.pendingCount())
                assertEquals(message().body, store.localPending().single().body)
                assertEquals("+1234567890", store.localPending().single().receiver)
                assertEquals(CaptureDiagnostics(2, 1, 1, 201, 200), store.captureDiagnostics())
            }
            assertTrue(
                realtimeSmsStatus(context, Store(context, name).use { it.captureDiagnostics() })
                    .contains("已保存")
            )
        }

    @Test
    fun historyFailureDoesNotBlockRealtimeOrEraseExistingQueue() {
        var queried = false
        fixture(
            read = true,
            query = {
                queried = true
                throw SecurityException("synthetic denial")
            },
        ) { context, config, name ->
            assertTrue(catchUpInbox(context, name).contains("失败"))
            assertTrue("Provider failure must be exercised, not a fixture setup failure", queried)
            assertEquals(HistoryState.FAILED, config.historyState)
            assertTrue(
                persistIncomingSms(context, message(-1), config.captureGeneration, 100, name)
            )
            Store(context, name).use { store ->
                assertEquals(1, store.localPending().size)
                assertEquals("", store.localPending().single().receiver)
                assertEquals(1, store.unresolvedCount())
                assertTrue(store.pending().isEmpty())
                store.saveSim(SimMapping(8, 0, "+9876543210", "replacement"))
                assertTrue(store.pending().isEmpty())
                assertEquals(message().body, store.localPending().single().body)
            }
        }
    }

    @Test
    fun revokedReceivePermissionDoesNotAllowSavingEvenWithReadPermission() =
        fixture(read = true, receive = false) { context, config, name ->
            assertFalse(persistIncomingSms(context, message(), config.captureGeneration, 100, name))
            Store(context, name).use { assertEquals(0, it.pendingCount()) }
            assertTrue(realtimeSmsStatus(context, CaptureDiagnostics()).contains("缺少接收权限"))
        }

    @Test
    fun bothDeniedAndDisabledHistoryDoNotQueryProvider() {
        fixture(receive = false) { context, config, name ->
            assertTrue(catchUpInbox(context, name).contains("权限"))
            assertFalse(persistIncomingSms(context, message(), config.captureGeneration, 100, name))
            Store(context, name).use { assertEquals(0, it.pendingCount()) }
        }
        fixture(read = true) { context, config, name ->
            config.historyEnabled = false
            assertEquals("历史读取已关闭", catchUpInbox(context, name))
            assertTrue(persistIncomingSms(context, message(), config.captureGeneration, 100, name))
            config.upload = false
            assertEquals("未启用本机采集", catchUpInbox(context, name))
            Store(context, name).use { assertEquals(1, it.pendingCount()) }
        }
    }

    @Test
    fun newInstallEnablingRealtimeDoesNotSilentlyEnableHistory() = fixture { context, _, _ ->
        val prefs = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
        prefs.edit().remove("history_enabled").putBoolean("upload", false).commit()
        val fresh = Config(context)
        assertFalse(fresh.historyEnabled)
        fresh.upload = true
        assertFalse(fresh.historyEnabled)
    }

    @Test
    fun disableReenableAndIdentityChangesRejectStaleBroadcasts() =
        fixture { context, config, name ->
            val old = config.captureGeneration
            config.upload = false
            assertFalse(persistIncomingSms(context, message(), old, 100, name))
            config.upload = true
            assertFalse(persistIncomingSms(context, message(), old, 100, name))
            val previousIdentity = config.captureGeneration
            config.invalidateCaptureSession()
            assertFalse(persistIncomingSms(context, message(), previousIdentity, 100, name))
            assertTrue(persistIncomingSms(context, message(), config.captureGeneration, 100, name))
            Store(context, name).use { assertEquals(1, it.pendingCount()) }
        }

    @Test
    fun blockedHistoryProviderCannotHoldIdentityLockAndCancelledHistoryCannotCommit() {
        val queried = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        fixture(
            read = true,
            query = {
                queried.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                MatrixCursor(arrayOf("_id", "address", "body", "date", "sub_id")).apply {
                    addRow(arrayOf<Any>(100, "10086", "synthetic history", 100, 7))
                }
            },
        ) { context, config, name ->
            val scanner = thread {
                try {
                    catchUpInbox(context, name)
                } catch (e: Throwable) {
                    outcome.set(e)
                }
            }
            var captureThread: Thread? = null
            try {
                assertTrue(queried.await(5, TimeUnit.SECONDS))
                val captured = CountDownLatch(1)
                captureThread = thread {
                    try {
                        assertTrue(
                            persistIncomingSms(
                                context,
                                message(),
                                config.captureGeneration,
                                100,
                                name,
                            )
                        )
                    } catch (e: Throwable) {
                        outcome.set(e)
                    } finally {
                        captured.countDown()
                    }
                }
                assertTrue(
                    "Provider query blocked realtime persistence",
                    captured.await(3, TimeUnit.SECONDS),
                )
                captureThread.join(3000)
                config.historyEnabled = false
                config.historyEnabled = true // old scan must remain cancelled
            } finally {
                release.countDown()
                scanner.join(10000)
                captureThread?.join(10000)
            }
            assertFalse(scanner.isAlive)
            outcome.get()?.let { throw it }
            Store(context, name).use {
                assertEquals(1, it.pendingCount())
                assertEquals(0L, it.providerWatermark())
            }
        }
    }

    @Test
    fun versionFiveMigrationPreservesWatermarkLedgerUnknownAndBlockedRecords() =
        fixture { context, _, name ->
            val known = SmsRecord("+1234567890", "10086", "synthetic known", 100, 7)
            Store(context, name).use { store ->
                store.captureProvider(50, known, true)
                store.queue(
                    known.copy(receiver = "", subscriptionId = -1, body = "synthetic unknown")
                )
                store.block(
                    store.localPending().first { it.body == "synthetic known" }.key,
                    "invalid_message",
                )
                store.saveSim(SimMapping(7, 0, known.receiver, "SIM1"))
                store.writableDatabase.execSQL(
                    "DELETE FROM meta WHERE key LIKE 'realtime_%' OR key IN ('last_broadcast','last_capture')"
                )
                // Reconstruct v5 rather than merely relabeling the current schema.
                store.writableDatabase.execSQL("DROP TABLE message_analysis")
                store.writableDatabase.execSQL("DELETE FROM meta WHERE key IN ('analysis_cursor','ai_enabled','local_rule_cursor')")
                store.writableDatabase.version = 5
                store.writableDatabase.execSQL("DROP TABLE notification_receipts")
                store.writableDatabase.execSQL("DROP TABLE upload_fingerprints")
            }
            Store(context, name).use { store ->
                assertEquals(17, store.readableDatabase.version)
                assertEquals(0L, store.analysisCursor())
                assertFalse(store.aiEnabled())
                assertEquals(50L, store.providerWatermark())
                assertEquals(2, store.localPending().size)
                assertEquals(1, store.blockedCount())
                assertEquals(1, store.unresolvedCount())
                assertEquals(1, store.sims().size)
                assertFalse(store.captureProvider(50, known))
                assertEquals(CaptureDiagnostics(), store.captureDiagnostics())
            }
        }

    @Test
    fun queuePaginationAndAckKeepUnknownAndBlockedRowsVisible() = fixture { context, config, name ->
        Store(context, name).use { store ->
            repeat(205) { index ->
                store.queue(SmsRecord("+1234567890", "10086", "synthetic $index", 100L + index, 7))
            }
            assertEquals(100, store.localPending(100).size)
            assertEquals(100, store.localPending(100, 100).size)
            assertEquals(5, store.localPending(100, 200).size)
            val pending = store.localPending().first()
            store.block(pending.key, "invalid_message")
            assertEquals("invalid_message", store.localPending().first().blocked)
            config.serverUploadAllowed = false
            assertEquals(205, store.localPending(200).size + store.localPending(100, 200).size)
            store.ack(listOf(pending.key))
            assertEquals(204, store.pendingCount())
        }
    }

    @Test
    fun legacyHistoryIntentIsMigratedBeforeMasterSwitchIsDisabled() =
        fixture { context, config, _ ->
            val prefs = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
            prefs.edit().remove("history_enabled").commit()
            val upgraded = Config(context)
            assertTrue(upgraded.historyEnabled)
            upgraded.upload = false
            assertTrue(upgraded.historyEnabled)
            assertFalse(upgraded.upload)
            assertFalse(config.upload)
        }
}
