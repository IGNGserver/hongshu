package io.hongshu.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode

// The diagnostics log says what each source did, never what the SMS said.
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CaptureEventTest {
    private val phone = "+8613800000000"

    private fun withStore(test: (Store) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "events-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use(test)
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun sourcesRecordContentFreeOutcomes() = withStore { store ->
        val t = 1_700_000_000_000
        assertTrue(store.queueRealtime(SmsRecord(phone, "10086", "secret body a", t, 7), 0, t))
        assertFalse(store.queueRealtime(SmsRecord(phone, "10086", "secret body a", t, 7), 0, t + 1))
        assertTrue(store.queueNotification(SmsRecord("", "95588", "secret body b", t + 2, -1)))
        assertFalse(store.captureProvider(9, SmsRecord(phone, "95588", "secret body b", t + 3, 7)))
        val events = store.captureEvents()
        assertEquals(listOf("paired_inbox", "duplicate", "saved"), events.map { it.outcome })
        val labels = events.map(::captureEventLabel)
        assertTrue(labels.none { "secret" in it || "10086" in it || "95588" in it })
        assertTrue(labels.first().endsWith("短信通知 · 已与收件箱记录关联"))
    }

    @Test
    fun burstsFoldAndTheLogStaysBounded() = withStore { store ->
        store.recordCaptureEvent(CaptureSource.NOTIFICATION, "ignored", at = 1000)
        store.recordCaptureEvent(CaptureSource.NOTIFICATION, "ignored", at = 2000)
        assertEquals(listOf(2), store.captureEvents().map { it.count })
        store.recordCaptureEvent(CaptureSource.NOTIFICATION, "ignored", at = 2000 + CAPTURE_EVENT_FOLD_MS + 1)
        assertEquals(2, store.captureEvents().size)
        store.recordCaptureEvent(CaptureSource.HISTORY, "added", 0)
        assertEquals(2, store.captureEvents().size)
        for (i in 1..CAPTURE_EVENT_LIMIT + 10)
            store.recordCaptureEvent(if (i % 2 == 0) CaptureSource.BROADCAST else CaptureSource.HISTORY, "saved", at = i * 1000L)
        assertEquals(CAPTURE_EVENT_LIMIT, store.captureEvents(limit = 1000).size)
    }
}
