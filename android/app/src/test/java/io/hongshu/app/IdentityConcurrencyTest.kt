package io.hongshu.app

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IdentityConcurrencyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clear() {
        context.getSharedPreferences("hongshu", Context.MODE_PRIVATE).edit().clear().commit()
        Config(context).deviceId = "self"
    }

    private fun message() = JSONObject().put("id", 1L).put("device_id", "remote")
        .put("sender", "10086").put("receiver", "+1234567890").put("body", "synthetic cached SMS")
        .put("timestamp", 1L)

    @Test fun stalledMeRequestCannotBlockLocalConversationAndStaleSnapshotCannotMarkRead() {
        val name = "local-snapshot-${java.util.UUID.randomUUID()}.db"
        val requestReceived = CountDownLatch(1)
        val releaseNetwork = CountDownLatch(1)
        val networkDone = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
            Config(context).url = "http://127.0.0.1:${server.localPort}"
            val responder = thread {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        assertEquals("/api/me", input.readLine().split(' ')[1])
                        while (!input.readLine().isNullOrEmpty()) { /* headers */ }
                        requestReceived.countDown()
                        check(releaseNetwork.await(10, TimeUnit.SECONDS))
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    }
                } catch (e: Throwable) { failures += e }
            }
            val sync = thread {
                try {
                    // This is the same long-lived network mutex held by SyncEngine.run.
                    runBlocking { identityMutex.withLock { Api(Config(context)).request("/me") } }
                } catch (e: Throwable) { failures += e }
                finally { networkDone.countDown() }
            }
            try {
                assertTrue(requestReceived.await(5, TimeUnit.SECONDS))
                Store(context, name).use { store ->
                    store.applySync(JSONArray().put(message()), 1, pageEpoch = 1)
                    val snapshot = pool.submit<ConversationSnapshot?> {
                        store.conversationSnapshot("10086", 200, "all")
                    }.get(2, TimeUnit.SECONDS)!!
                    assertEquals("synthetic cached SMS", snapshot.messages.single().body)
                    assertEquals(1L, networkDone.count) // The HTTP request is still deliberately blocked.
                    assertTrue(pool.submit<Boolean> { store.markDisplayedRead(snapshot) }.get(2, TimeUnit.SECONDS))
                    store.resetSyncedCache(2)
                    store.applySync(JSONArray().put(message()), 1, pageEpoch = 2)
                    assertFalse(store.markDisplayedRead(snapshot))
                    assertEquals(1, store.unreadCounts()["10086"])
                    assertTrue(store.pendingReads().isEmpty())
                }
            } finally {
                releaseNetwork.countDown()
                sync.join(5000)
                responder.join(5000)
                pool.shutdownNow()
                context.deleteDatabase(name)
            }
            assertFalse(sync.isAlive)
            assertFalse(responder.isAlive)
            assertEquals(emptyList<Throwable>(), failures)
        }
    }

    @Test fun identityCannotChangeBetweenOtpValidationAndClipboardWrite() {
        val name = "otp-lock-${java.util.UUID.randomUUID()}.db"
        val inClipboard = CountDownLatch(1)
        val releaseClipboard = CountDownLatch(1)
        val switching = CountDownLatch(1)
        val switched = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        Store(context, name).use { it.rememberEpoch(1) }
        val tag = OtpNotificationManager.notificationTag(context, 1, 1)
        val copier = thread {
            try {
                assertTrue(copyNotificationOtp(context, tag, "123456", name) { app, code ->
                    assertTrue(Thread.holdsLock(identityLock))
                    inClipboard.countDown()
                    check(releaseClipboard.await(5, TimeUnit.SECONDS))
                    copyOtp(app, code)
                })
            } catch (e: Throwable) { failures += e }
        }
        val changer = thread {
            try {
                check(inClipboard.await(5, TimeUnit.SECONDS))
                switching.countDown()
                synchronized(identityLock) { Config(context).beginIdentityChange() }
                switched.countDown()
            } catch (e: Throwable) { failures += e }
        }
        try {
            assertTrue(switching.await(5, TimeUnit.SECONDS))
            assertFalse(switched.await(150, TimeUnit.MILLISECONDS))
        } finally {
            releaseClipboard.countDown()
            copier.join(5000)
            changer.join(5000)
        }
        try {
            assertFalse(copier.isAlive)
            assertFalse(changer.isAlive)
            assertEquals(emptyList<Throwable>(), failures)
            assertTrue(Config(context).identityChanging)
            assertFalse(copyNotificationOtp(context, tag, "654321", name))
            assertEquals("123456", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldEpochOtpCannotCopyEvenWhenDeviceIdentityIsUnchanged() {
        val name = "otp-epoch-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { it.rememberEpoch(1) }
            val oldTag = OtpNotificationManager.notificationTag(context, 7, 1)
            assertTrue(copyNotificationOtp(context, oldTag, "123456", name))
            Store(context, name).use { it.resetSyncedCache(2) }
            assertFalse(copyNotificationOtp(context, oldTag, "654321", name))
            assertEquals("123456", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            assertTrue(copyNotificationOtp(context, OtpNotificationManager.notificationTag(context, 7, 2), "987654", name))
        } finally { context.deleteDatabase(name) }
    }
}
