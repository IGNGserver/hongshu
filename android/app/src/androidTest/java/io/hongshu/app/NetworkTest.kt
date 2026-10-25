package io.hongshu.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkTest {
    private fun emptyReadState() = JSONObject().put("epoch", 1).put("revision", 0).put("head", 0)
        .put("global_watermark_id", 0).put("items", JSONArray()).put("more", false).put("next", "")

    private class Hub(private val handler: (String, JSONObject) -> Pair<Int, JSONObject>) :
        AutoCloseable {
        val socket = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val failures = LinkedBlockingQueue<Throwable>()
        val worker = thread {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    val input = client.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) {
                            val b = input.read()
                            check(b >= 0)
                            if (b == 10) break
                            if (b != 13) bytes.write(b)
                        }
                        return bytes.toString("UTF-8")
                    }
                    val path = line().split(" ")[1]
                    var length = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true))
                            length = header.substringAfter(':').trim().toInt()
                    }
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        check(n > 0)
                        read += n
                    }
                    val (status, json) =
                        handler(
                            path,
                            if (length == 0) JSONObject()
                            else JSONObject(String(body, Charsets.UTF_8)),
                        )
                    val bytes = json.toString().toByteArray()
                    client.getOutputStream().apply {
                        write(
                            "HTTP/1.1 $status Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                        write(bytes)
                        flush()
                    }
                }
            } catch (e: Exception) {
                if (!socket.isClosed) failures.add(e)
            }
        }

        override fun close() {
            socket.close()
            worker.join(3000)
            assertTrue(failures.toString(), failures.isEmpty())
        }
    }

    private fun cancelSyncWork(context: Context) {
        val manager = WorkManager.getInstance(context)
        // The application's periodic sync starts immediately on a fresh emulator.
        // It must not drain a test's queue concurrently with its explicit sync calls.
        for (name in listOf("hongshu-sync", "hongshu-periodic", "hongshu-capture", "hongshu-capture-periodic"))
            manager.cancelUniqueWork(name).result.get()
    }

    private fun withConfig(test: (Context, Config) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
        val saved = prefs.all
        try {
            cancelSyncWork(context)
            prefs.edit().clear().commit()
            test(context, Config(context))
        } finally {
            cancelSyncWork(context)
            val editor = prefs.edit().clear()
            for ((key, value) in saved) when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            }
            editor.commit()
        }
    }

    @Test
    fun renameKeepsIdentityAndFailedReauthenticationKeepsCredentials() =
        withConfig { context, config ->
            val requests = LinkedBlockingQueue<Pair<String, JSONObject>>()
            Hub { path, body ->
                    requests.add(path to body)
                    if (path == "/api/login") 401 to JSONObject().put("error", "invalid_password")
                    else 200 to JSONObject().put("ok", true)
                }
                .use { hub ->
                    config.url = hub.url
                    config.token = "s".repeat(64)
                    config.deviceId = "synthetic"
                    config.deviceName = "before"
                    Repository(context).apply {
                        try {
                            saveConnection(hub.url, "", "  after  ")
                            assertEquals("after", config.deviceName)
                            assertEquals("synthetic", config.deviceId)
                            assertEquals("s".repeat(64), config.token)
                            val rename = requests.poll(2, TimeUnit.SECONDS)!!
                            assertEquals("/api/devices/synthetic", rename.first)
                            assertEquals("after", rename.second.getString("name"))
                            try {
                                login(hub.url, "wrong synthetic password", "replacement")
                                fail("bad password accepted")
                            } catch (e: ApiException) {
                                assertEquals(401, e.status)
                            }
                            assertEquals(hub.url, config.url)
                            assertEquals("s".repeat(64), config.token)
                            assertEquals("synthetic", config.deviceId)
                        } finally {
                            store.close()
                        }
                    }
                }
        }

    @Test
    fun malformedSuccessfulLoginCannotPublishNewOriginOrEraseOldCache() = withConfig { context, config ->
        val name = "login-atomic-${java.util.UUID.randomUUID()}.db"
        try {
            config.url = "https://old.example"
            config.token = "a".repeat(64)
            config.deviceId = "old-device"
            config.deviceName = "old phone"
            Repository(context, name).apply {
                try {
                    store.applySync(JSONArray().put(JSONObject().put("id", 1).put("device_id", "remote")
                        .put("receiver", "+1234567890").put("sender", "10086").put("body", "synthetic")
                        .put("timestamp", 1L)), 1)
                    Hub { _, _ -> 200 to JSONObject().put("device", JSONObject()) }.use { hub ->
                        assertThrows(Exception::class.java) { login(hub.url, "synthetic password", "new phone") }
                    }
                    assertEquals("https://old.example", config.url)
                    assertEquals("a".repeat(64), config.token)
                    assertEquals("old-device", config.deviceId)
                    assertEquals(1, store.messages().size)
                    assertFalse(config.identityChanging)
                    config.beginIdentityChange()
                    assertEquals("", Config(context).token)
                    assertThrows(IllegalStateException::class.java) { Api(Config(context)).request("/me") }
                    Hub { _, _ -> 200 to JSONObject().put("token", "b".repeat(64)).put("device",
                        JSONObject().put("id", "new-device").put("name", "new phone")
                            .put("created_at", 10L).put("notify", false)) }.use { hub ->
                        login(hub.url, "synthetic password", "new phone")
                        assertEquals(hub.url, Config(context).url)
                    }
                    assertEquals("b".repeat(64), Config(context).token)
                    assertEquals("new-device", Config(context).deviceId)
                    assertFalse(config.identityChanging)
                    assertFalse(config.upload)
                    assertTrue(store.messages().isEmpty())
                } finally { store.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun outagePreservesTokenAndPasswordsKeepExactWhitespace() = withConfig { context, config ->
        val status = java.util.concurrent.atomic.AtomicInteger(503)
        val loginBodies = LinkedBlockingQueue<String>()
        Hub { path, body ->
                if (path == "/api/login") {
                    loginBodies.add(body.getString("password"))
                    200 to
                        JSONObject()
                            .put("token", "s".repeat(64))
                            .put("device", JSONObject().put("id", "synthetic")
                                .put("name", "synthetic").put("created_at", 1L).put("notify", true))
                } else status.get() to JSONObject().put("error", "database_unavailable")
            }
            .use { hub ->
                config.url = hub.url
                config.token = "s".repeat(64)
                try {
                    Api(config).request("/me")
                    fail("outage accepted")
                } catch (e: ApiException) {
                    assertEquals(503, e.status)
                }
                assertEquals("s".repeat(64), config.token)
                status.set(200)
                Api(config).request("/me")
                assertEquals("s".repeat(64), config.token)
                Repository(context).apply {
                    try {
                        login(hub.url, "  synthetic password  ", "synthetic")
                    } finally {
                        store.close()
                    }
                }
                assertEquals("  synthetic password  ", loginBodies.poll(2, TimeUnit.SECONDS))
            }
    }

    @Test
    fun largeHistoryQueueContinuesAndRegistersCapturedNumbersAfterSIMChange() =
        withConfig { context, config ->
            val uploaded = java.util.concurrent.atomic.AtomicInteger()
            val registered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val registrations = java.util.concurrent.atomic.AtomicInteger()
            val downloads = java.util.concurrent.atomic.AtomicInteger()
            val oldPhone = "+1234567890"
            val me =
                JSONObject()
                    .put("cursor", 0)
                    .put("epoch", 1)
                    .put(
                        "device",
                        JSONObject().put("notify", false).put("upload", true).put("created_at", 1),
                    )
            Hub { path, body ->
                    200 to
                        when {
                            path == "/api/me" -> me
                            path.startsWith("/api/read-state?") -> emptyReadState()
                            path == "/api/contacts" -> JSONObject().put("contacts", JSONObject())
                            path == "/api/sims" -> {
                                registrations.incrementAndGet()
                                registered.add(body.getString("phone"))
                                JSONObject().put("ok", true)
                            }
                             path == "/api/messages" -> {
                                 val messages = body.getJSONArray("messages")
                                 assertTrue("download gets a turn before each upload", downloads.get() > uploaded.get() / 100)
                                 assertTrue("one bounded upload batch per round", messages.length() in 1..100)
                                val acks = JSONArray()
                                for (i in 0 until messages.length()) {
                                    assertTrue(messages.getJSONObject(i).getBoolean("historical"))
                                    assertEquals(
                                        oldPhone,
                                        messages.getJSONObject(i).getString("receiver"),
                                    )
                                    assertTrue(registered.contains(oldPhone))
                                    acks.put(
                                        JSONObject()
                                            .put("id", uploaded.incrementAndGet())
                                            .put("duplicate", false)
                                    )
                                }
                                JSONObject().put("acks", acks)
                            }
                             else -> {
                                 if (path.startsWith("/api/sync?")) downloads.incrementAndGet()
                                 JSONObject()
                                    .put("messages", JSONArray())
                                    .put("cursor", 0)
                                    .put("more", false)
                                     .put("epoch", 1)
                             }
                        }
                }
                .use { hub ->
                    config.url = hub.url
                    config.token = "s".repeat(64)
                    config.deviceId = "synthetic"
                    config.upload = true
                    config.historyEnabled = true
                    config.notify = false
                    Store(context).use { store ->
                        store.saveSim(SimMapping(19, 0, "+9876543210", "SIM1"))
                        val baseID = System.currentTimeMillis()
                        val records =
                            (1..2005).map { id ->
                                (baseID + id) to
                                    SmsRecord(
                                        oldPhone,
                                        "10086",
                                        "synthetic queue $baseID $id",
                                        100 + id.toLong(),
                                        19,
                                        true,
                                    )
                            }
                        val keys = records.map { "provider:${it.first}" }
                        try {
                            store.captureProviders(records, false)
                            // Cancelling work in withConfig does not stop every background sync:
                            // a CaptureWorker that is already running finishes its blocking body
                            // and enqueues a SyncWorker once it sees this test's upload switch and
                            // queue, that worker chains successors while the queue is non-empty,
                            // and syncNow() enqueues asynchronously. Each such pass drains one more
                            // batch between two rounds (seen on API 28 as exactly 200 rows
                            // fewer than expected). Holding the sync mutex makes such a worker
                            // wait instead, so every batch counted here is one of these runs.
                            runBlocking {
                                identityMutex.withLock {
                                    try {
                                        for (round in 1..21) {
                                            assertFalse(SyncEngine.runLocked(context))
                                            assertEquals((2005 - round * 100).coerceAtLeast(0), store.pendingCount())
                                            assertEquals(2, registrations.get())
                                        }
                                        // A final successor catches up data committed by the last upload.
                                        assertTrue(SyncEngine.runLocked(context))
                                        assertEquals(0, store.pendingCount())
                                        assertEquals(2005, uploaded.get())
                                        // Accepted registrations are cached per hub device across runs.
                                        assertEquals(2, registrations.get())
                                        assertTrue(registered.contains("+9876543210"))
                                    } finally {
                                        // Cancel a waiting worker while it is still suspended on
                                        // the mutex, before it can run against this hub.
                                        cancelSyncWork(context)
                                    }
                                }
                            }
                        } finally {
                            store.ack(keys)
                        }
                    }
                }
        }

    @Test
    fun contactOutageCannotBlockUploadOrDownloadAndAuthenticationFailureStillPropagates() =
        withConfig { context, config ->
            val requests = mutableListOf<String>()
            val contactStatus = java.util.concurrent.atomic.AtomicInteger(503)
            Hub { path, _ ->
                    requests.add(path)
                    if (path.startsWith("/api/read-state?")) 200 to emptyReadState()
                    else when (path) {
                        "/api/me" ->
                            200 to
                                JSONObject()
                                    .put("cursor", 0)
                                    .put("epoch", 1)
                                    .put(
                                        "device",
                                        JSONObject()
                                            .put("upload", true)
                                            .put("notify", false)
                                            .put("created_at", 1),
                                    )
                        "/api/contacts" ->
                            contactStatus.get() to JSONObject().put("error", "synthetic_failure")
                        "/api/sims" -> 200 to JSONObject().put("ok", true)
                        "/api/messages" ->
                            200 to
                                JSONObject()
                                    .put(
                                        "acks",
                                        JSONArray()
                                            .put(JSONObject().put("id", 1).put("duplicate", false)),
                                    )
                        else ->
                            200 to
                                JSONObject()
                                    .put("messages", JSONArray())
                                    .put("cursor", 0)
                                    .put("epoch", 1)
                                    .put("more", false)
                    }
                }
                .use { hub ->
                    config.url = hub.url
                    config.token = "s".repeat(64)
                    config.upload = true
                    Store(context).use { store ->
                        val message =
                            SmsRecord("+1234567890", "10086", "synthetic contacts outage", 100, 7)
                        store.queue(message)
                        store.applyContacts(JSONObject().put("10086", "synthetic cached contact"))
                        try {
                            // Contact names are cosmetic: the sync itself succeeds.
                            runBlocking { SyncEngine.run(context) }
                            assertEquals(0, store.pendingCount())
                            assertTrue(
                                requests.indexOf("/api/messages") <
                                    requests.indexOf("/api/contacts")
                            )
                            assertTrue(requests.any { it.startsWith("/api/sync?") })
                            assertTrue(config.syncHealthy)
                            assertFalse(config.contactsHealthy)
                            store.readableDatabase
                                .rawQuery(
                                    "SELECT name FROM contact_snapshot WHERE phone='10086'",
                                    null,
                                )
                                .use {
                                    assertTrue(it.moveToFirst())
                                    assertEquals("synthetic cached contact", it.getString(0))
                                }
                            contactStatus.set(401)
                            val failure =
                                assertThrows(ApiException::class.java) {
                                    runBlocking { SyncEngine.run(context) }
                                }
                            assertEquals(401, failure.status)
                            assertEquals("", config.token)
                        } finally {
                            store.ack(listOf(message.fingerprint()))
                        }
                    }
                }
        }

    @Test
    fun syncSuccessCannotHideMissingCapturePermissionAndTriggersCoalesce() =
        withConfig { context, config ->
            val failing = java.util.concurrent.atomic.AtomicBoolean(false)
            val me =
                JSONObject()
                    .put("cursor", 0)
                    .put("epoch", 1)
                    .put(
                        "device",
                        JSONObject().put("notify", false).put("upload", false).put("created_at", 1),
                    )
            Hub { path, _ ->
                    if (failing.get()) 503 to JSONObject().put("error", "database_unavailable")
                    else
                        200 to
                            when {
                                path == "/api/me" -> me
                                path.startsWith("/api/read-state?") -> emptyReadState()
                                path == "/api/contacts" ->
                                    JSONObject().put("contacts", JSONObject())
                                else ->
                                    JSONObject()
                                        .put("messages", JSONArray())
                                        .put("cursor", 0)
                                        .put("more", false)
                                        .put("epoch", 1)
                            }
                }
                .use { hub ->
                    config.url = hub.url
                    config.token = "s".repeat(64)
                    config.deviceId = "synthetic"
                    config.upload = true
                    config.historyEnabled = true
                    val manager = WorkManager.getInstance(context)
                    val existing =
                        manager
                            .getWorkInfosForUniqueWork("hongshu-sync")
                            .get()
                            .map { it.id }
                            .toSet()
                    runBlocking { syncNow(context, manual = true).join() }
                    fun waitFor(
                        predicate: (List<androidx.work.WorkInfo>) -> Boolean
                    ): List<androidx.work.WorkInfo> {
                        val deadline = System.currentTimeMillis() + 15000
                        while (System.currentTimeMillis() < deadline) {
                            val infos = manager.getWorkInfosForUniqueWork("hongshu-sync").get()
                            if (predicate(infos)) return infos
                            Thread.sleep(50)
                        }
                        error("worker did not reach expected state")
                    }
                    waitFor {
                        it.any { work ->
                            work.id !in existing &&
                                work.state == androidx.work.WorkInfo.State.SUCCEEDED
                        }
                    }
                    config.captureStatus = catchUpInbox(context)
                    assertTrue(
                        "capture health: ${config.captureStatus}",
                        config.captureStatus.contains("权限"),
                    )
                    assertEquals("同步完成", config.status)
                    failing.set(true)
                    runBlocking { syncNow(context).join() }
                    // Wait until the failed attempt is parked in backoff. A worker still RUNNING
                    // keeps its own slot and may legitimately add one successor, so counting
                    // pending work from that moment raced with the worker's own timing.
                    val retry = waitFor {
                        it.any { work ->
                            work.state == androidx.work.WorkInfo.State.ENQUEUED &&
                                work.runAttemptCount > 0
                        }
                    }
                    val old = retry.first { !it.state.isFinished }.id
                    runBlocking { repeat(100) { syncNow(context).join() } }
                    // A worker that is still RUNNING keeps its slot and can add one successor, so
                    // let the running attempt settle before counting the parked successor.
                    waitFor { infos ->
                        infos.none { it.state == androidx.work.WorkInfo.State.RUNNING }
                    }
                    assertEquals(
                        1,
                        manager.getWorkInfosForUniqueWork("hongshu-sync").get().count {
                            !it.state.isFinished
                        },
                    )
                    runBlocking { syncNow(context, manual = true).join() }
                    assertTrue(
                        manager.getWorkInfosForUniqueWork("hongshu-sync").get().none {
                            !it.state.isFinished && it.id == old
                        }
                    )
                }
        }
}
