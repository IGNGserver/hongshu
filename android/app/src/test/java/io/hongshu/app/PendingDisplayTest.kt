package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PendingDisplayTest {
    private val phone = "+8613800000000"

    private fun withStore(test: (Store) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "display-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use(test)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun remote(id: Long, sender: String, time: Long) =
        JSONObject()
            .put("id", id)
            .put("device_id", "other")
            .put("receiver", phone)
            .put("sender", sender)
            .put("body", "remote $id")
            .put("timestamp", time)

    @Test
    fun countsReadAsUserFacingReasons() {
        assertEquals("没有未上传的短信", PendingCounts().label())
        assertEquals("未上传 3 条：2 条等待上传 · 1 条无法上传", PendingCounts(2, 0, 1).label())
        assertEquals("有 4 条本机短信尚未上传到中枢，其中 3 条需要确认接收号码", PendingCounts(1, 3, 0).banner())
        assertEquals("有 1 条本机短信尚未上传到中枢", PendingCounts(1, 0, 0).banner())
    }

    @Test
    fun hubErrorsAreDescribedWithoutRawCodes() {
        assertEquals("中枢数据库暂时不可用，稍后自动重试", ApiException(503, "database_unavailable").message)
        assertEquals("中枢已禁止本机上传，可在网页端设备管理中开启", describeApiError(403, "upload_disabled"))
        assertEquals("中枢暂时不可用（502），稍后自动重试", describeApiError(502, "network_error"))
        assertTrue(describeApiError(418, "teapot").contains("418"))
    }

    @Test
    fun localCapturesAppearInConversationsUntilAcknowledged() = withStore { store ->
        store.saveSim(SimMapping(7, 0, phone, "SIM1"))
        store.applySync(JSONArray().put(remote(1, "10086", 1000)).put(remote(2, "95588", 3000)), 2)
        store.queue(SmsRecord(phone, "10086", "local newest", 5000, 7))
        val heads = store.messagesWithLocal()
        assertEquals(listOf("10086", "95588"), heads.map { it.sender })
        assertTrue(heads.first().local)
        assertTrue(heads.first().id < 0)
        val thread = store.messagesWithLocal(sender = "10086")
        assertEquals(listOf("local newest", "remote 1"), thread.map { it.body })
        assertEquals(1, store.messagesWithLocal(q = "newest").size)
        assertTrue(store.messagesWithLocal(sim = "+1999").isEmpty())
        store.ack(store.pending().map { it.first })
        assertFalse(store.messagesWithLocal().any { it.local })
        // The inbox-only query used elsewhere never includes local rows.
        assertFalse(store.messages().any { it.local })
    }

    @Test
    fun syncReconcilesAnOutboxAfterTheUploadAckWasLost() = withStore { store ->
        store.saveSim(SimMapping(7, 0, phone, "SIM1"))
        val local = SmsRecord(phone, "10086", "lost ACK", 5000, 7)
        store.queue(local)
        assertEquals(1, store.pendingCount())
        store.applySync(
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", 1)
                        .put("device_id", "self")
                        .put("receiver", local.receiver)
                        .put("sender", local.sender)
                        .put("body", local.body)
                        .put("timestamp", local.timestamp)
                ),
            1,
            self = "self",
        )
        assertEquals(0, store.pendingCount())
        assertFalse(store.messagesWithLocal(sender = local.sender).any { it.local })
    }

    @Test
    fun syncDoesNotReconcileAnotherDevicesIdenticalMessage() = withStore { store ->
        val local = SmsRecord(phone, "10086", "other device copy", 6000, 7)
        store.queue(local)
        store.applySync(
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", 1)
                        .put("device_id", "other")
                        .put("receiver", local.receiver)
                        .put("sender", local.sender)
                        .put("body", local.body)
                        .put("timestamp", local.timestamp)
                ),
            1,
            self = "self",
        )
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun oneSenderConversationContainsMessagesFromMultipleReceivingPhones() = withStore { store ->
        val secondPhone = "+8613900000000"
        store.applySync(
            JSONArray()
                .put(remote(1, "10086", 1000))
                .put(remote(2, "10086", 2000).put("receiver", secondPhone)),
            2,
        )
        val heads = store.messagesWithLocal()
        assertEquals(listOf("10086"), heads.map { it.sender })
        val thread = store.messagesWithLocal(sender = "10086")
        assertEquals(setOf(phone, secondPhone), thread.map { it.receiver }.toSet())
    }

    @Test
    fun unpairedNotificationsWaitForTheirSmsCounterpartBeforeUpload() = withStore { store ->
        val now = 10_000_000L
        assertTrue(store.queueNotification(SmsRecord(phone, "妈妈", "synthetic", now, 7)))
        assertTrue(store.queueNotification(SmsRecord(phone, "95588", "synthetic", now, 7)))
        assertTrue(store.pending(now = now + 1000).isEmpty())
        assertEquals(NOTIFICATION_NUMBER_HOLD - 1000, store.nextNotificationRelease(now + 1000))
        assertEquals(listOf("95588"), store.pending(now = now + NOTIFICATION_NUMBER_HOLD + 1).map { it.second.sender })
        assertEquals(NOTIFICATION_NAME_HOLD - NOTIFICATION_NUMBER_HOLD - 1,
            store.nextNotificationRelease(now + NOTIFICATION_NUMBER_HOLD + 1))
        assertEquals(2, store.pending(now = now + NOTIFICATION_NAME_HOLD + 1).size)
        assertNull(store.nextNotificationRelease(now + NOTIFICATION_NAME_HOLD + 1))
    }

    @Test
    fun notificationPairedWithItsSmsIsNotHeld() = withStore { store ->
        val now = 10_000_000L
        assertTrue(store.queueNotification(SmsRecord("", "95588", "synthetic", now, -1)))
        assertFalse(store.captureProvider(9, SmsRecord(phone, "95588", "synthetic", now + 2000, 7)))
        assertEquals(listOf("95588"), store.pending(now = now + 3000).map { it.second.sender })
        assertNull(store.nextNotificationRelease(now + 3000))
    }
}
