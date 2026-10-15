package io.hongshu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {
    private val healthy =
        SetupFacts(
            connected = true,
            upload = true,
            receiveSms = true,
            readSms = true,
            recovery = true,
            notify = true,
            keepAlive = true,
            realtimeRunning = true,
            realtimeConnected = true,
        )

    @Test
    fun healthySetupNeedsNothing() {
        assertTrue(attentionItems(healthy).isEmpty())
        assertEquals(StatusLevel.Ok to "已开启", captureStatus(healthy))
        assertEquals(StatusLevel.Ok to "实时连接中", backgroundStatus(healthy))
    }

    @Test
    fun disconnectedHubIsTheOnlyReportedProblem() {
        // Everything else depends on the hub, so listing it would only be noise.
        val items = attentionItems(healthy.copy(connected = false, receiveSms = false))

        assertEquals(listOf(SettingsPage.Hub), items.map { it.page })
    }

    @Test
    fun missingPermissionsPointToTheCapturePage() {
        val facts = healthy.copy(receiveSms = false, readSms = false)

        assertEquals(StatusLevel.Attention to "需要授权", captureStatus(facts))
        assertEquals(
            listOf("缺少接收短信权限", "缺少读取短信权限"),
            attentionItems(facts).map { it.title },
        )
        assertTrue(attentionItems(facts).all { it.page == SettingsPage.Capture })
    }

    @Test
    fun readPermissionOnlyMattersWhenInboxReadingIsOn() {
        val facts = healthy.copy(readSms = false, recovery = false, history = false)

        assertTrue(attentionItems(facts).isEmpty())
    }

    @Test
    fun captureProblemsAreIgnoredWhileCaptureIsOff() {
        val facts = healthy.copy(upload = false, receiveSms = false)

        assertEquals(StatusLevel.Off to "已关闭", captureStatus(facts))
        assertTrue(attentionItems(facts).isEmpty())
    }

    @Test
    fun unconfirmedNumbersSendTheUserToSimSettings() {
        val facts = healthy.copy(pending = PendingCounts(needsSim = 3))

        assertEquals(SettingsPage.Sims, attentionItems(facts).single().page)
    }

    @Test
    fun stoppedRealtimeServiceIsReportedBeforeBatteryAdvice() {
        val facts = healthy.copy(realtimeRunning = false, batteryUnrestricted = false)

        assertEquals(listOf("实时连接未运行"), attentionItems(facts).map { it.title })
        assertEquals(StatusLevel.Attention to "未运行", backgroundStatus(facts))
    }

    @Test
    fun keepAliveOffMeansPeriodicSyncOnly() {
        val facts = healthy.copy(keepAlive = false, realtimeRunning = false)

        assertEquals(StatusLevel.Off to "仅周期同步", backgroundStatus(facts))
        assertTrue(attentionItems(facts).isEmpty())
    }

    @Test
    fun appSettingsReadAsDisconnectedWithoutAHub() {
        val facts = healthy.copy(connected = false, postNotifications = false)

        assertEquals(StatusLevel.Off to "未连接", notificationsStatus(facts))
        assertEquals(StatusLevel.Off to "未连接", backgroundStatus(facts))
    }
}
