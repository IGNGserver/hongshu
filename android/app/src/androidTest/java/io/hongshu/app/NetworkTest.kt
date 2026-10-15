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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkTest {
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

    private fun withConfig(test: (Context, Config) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
        val saved = prefs.all
        try {
            WorkManager.getInstance(context).cancelUniqueWork("hongshu-sync").result.get()
            WorkManager.getInstance(context).cancelUniqueWork("hongshu-capture").result.get()
            WorkManager.getInstance(context)
                .cancelUniqueWork("hongshu-capture-periodic")
                .result
                .get()
            prefs.edit().clear().commit()
            test(context, Config(context))
        } finally {
            WorkManager.getInstance(context).cancelUniqueWork("hongshu-sync").result.get()
            WorkManager.getInstance(context).cancelUniqueWork("hongshu-capture").result.get()
            WorkManager.getInstance(context)
                .cancelUniqueWork("hongshu-capture-periodic")
                .result
                .get()
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
    fun outagePreservesTokenAndPasswordsKeepExactWhitespace() = withConfig { context, config ->
        val status = java.util.concurrent.atomic.AtomicInteger(503)
        val loginBodies = LinkedBlockingQueue<String>()
        Hub { path, body ->
                if (path == "/api/login") {
                    loginBodies.add(body.getString("password"))
                    200 to
                        JSONObject()
                            .put("token", "s".repeat(64))
                            .put("device", JSONObject().put("id", "synthetic"))
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
                            path == "/api/contacts" -> JSONObject().put("contacts", JSONObject())
                            path == "/api/sims" -> {
                                registrations.incrementAndGet()
                                registered.add(body.getString("phone"))
                                JSONObject().put("ok", true)
                            }
                            path == "/api/messages" -> {
                                val messages = body.getJSONArray("messages")
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
                            assertFalse(runBlocking { SyncEngine.run(context) })
                            assertEquals(5, store.pendingCount())
                            assertEquals(2, registrations.get())
                            assertTrue(runBlocking { SyncEngine.run(context) })
                            assertEquals(0, store.pendingCount())
                            assertEquals(2005, uploaded.get())
                            assertEquals(4, registrations.get())
                            assertTrue(registered.contains("+9876543210"))
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
                    when (path) {
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
                            assertThrows(ContactsRefreshException::class.java) {
                                runBlocking { SyncEngine.run(context) }
                            }
                            assertEquals(0, store.pendingCount())
                            assertTrue(
                                requests.indexOf("/api/messages") <
                                    requests.indexOf("/api/contacts")
                            )
                            assertTrue(requests.any { it.startsWith("/api/sync?") })
                            assertFalse(config.syncHealthy)
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
                    val retry = waitFor {
                        it.any { work -> !work.state.isFinished && work.runAttemptCount > 0 }
                    }
                    val old = retry.first { !it.state.isFinished }.id
                    runBlocking { repeat(100) { syncNow(context).join() } }
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
