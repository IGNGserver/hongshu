package io.hongshu.app

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test
    fun manualFieldsResolveSystemSlotsNotListOrderOrOldSubscriptions() {
        val active = listOf(SimMapping(42, 1, "", ""), SimMapping(17, 0, "", ""))
        val result = manualSimMappings(active, listOf("+86 138-0000-0000", "+8613900000000"))
        assertEquals("+8613900000000", result[0].phone)
        assertEquals(42, result[0].sub)
        assertEquals("+8613800000000", result[1].phone)
        assertEquals(17, result[1].sub)
        assertEquals("SIM1", result[1].label)
        assertEquals("+8613800000000", normalizeSimNumber("+86 (138) 0000-0000"))
    }

    @Test
    fun manualModeRejectsAmbiguousSlotAndMissingActiveNumber() {
        for (active in
            listOf(
                emptyList(),
                listOf(SimMapping(7, -1, "", "")),
                listOf(SimMapping(7, 0, "", ""), SimMapping(8, 0, "", "")),
                listOf(SimMapping(8, 1, "", "")),
            )) {
            try {
                manualSimMappings(active, listOf("+1234567890", ""))
                fail("unsafe mapping accepted")
            } catch (_: IllegalStateException) {}
        }
    }

    @Test
    fun oldSMSIsHistoricalWithoutFilteringItOut() {
        assertTrue(isHistoricalSMS(10, 1000))
        assertTrue(isHistoricalSMS(999, 1000))
        assertFalse(isHistoricalSMS(1000, 1000))
        assertFalse(isHistoricalSMS(1001, 1000))
        assertFalse(isHistoricalSMS(10, 0))
    }

    @Test
    fun knownUnmappedSubscriptionNeverUsesSingleSimFallback() {
        val mappings = listOf(SimMapping(7, 0, "+123456789", "synthetic"))
        assertEquals("+123456789", resolvedReceiver(mappings, 7, 7))
        assertEquals("", resolvedReceiver(mappings, 8, 7))
        assertEquals("+123456789", resolvedReceiver(mappings, -1, 7))
        assertEquals("", resolvedReceiver(mappings, -1, 8))
        assertEquals(
            "",
            resolvedReceiver(mappings + SimMapping(8, 1, "+987654321", "other"), -1, 7),
        )
    }

    @Test
    fun realtimeSupportsHTTPAndHTTPSOrigins() {
        assertEquals("http://sms.example.com:8080", hubOrigin("HTTP://sms.example.com:8080/"))
        assertEquals(
            "ws://sms.example.com:8080/api/ws",
            realtimeURL("http://sms.example.com:8080/"),
        )
        assertEquals("wss://sms.example.com/api/ws", realtimeURL("https://sms.example.com"))
    }

    @Test
    fun uploadBatchHonorsActualEscapedJsonBytes() {
        assertEquals(14, uploadBatchCount(List(100) { 64000 }))
        assertEquals(1, uploadBatchCount(listOf(450000, 450000)))
        assertEquals(0, uploadBatchCount(emptyList()))
        assertEquals(0, uploadBatchCount(listOf(950000)))
    }

    @Test
    fun multipartKeepsUnicodeAndOrder() {
        assertEquals("长短信😀第二段", combineParts(listOf("长短信😀", "第二段")))
    }

    @Test
    fun fingerprintStableAndChangesWithReceiver() {
        val m = SmsRecord("+8613800000000", "10086", "测试", 1700000000000, 1)
        assertEquals(m.fingerprint(), m.copy(subscriptionId = 2).fingerprint())
        assertNotEquals(m.fingerprint(), m.copy(receiver = "+8613900000000").fingerprint())
        assertEquals(64, m.fingerprint().length)
        assertEquals(
            "40315f7bdb01179c52c2db83ef04c8ec9f3957d384188ff8901f4115252fa41a",
            m.fingerprint(),
        )
        assertEquals(m.fingerprint(), m.copy(historical = true).fingerprint())
    }

    @Test
    fun numberNeedsUserConfirmedDigits() {
        assertTrue(confirmedNumber("+8613800000000"))
        assertFalse(confirmedNumber("SIM 1"))
        assertFalse(confirmedNumber(""))
        assertFalse(confirmedNumber("+12\u0000"))
    }

    @Test
    fun inboxImportWithinTwoMinutesIsTheSameLiveMessage() {
        val live = SmsRecord("+8613800000000", "10086", "验证码 123456", 1700000000000, 1)
        assertTrue(sameMessage(live, live.copy(timestamp = live.timestamp + 119_000)))
        assertFalse(sameMessage(live, live.copy(timestamp = live.timestamp + 121_000)))
        assertFalse(sameMessage(live, live.copy(body = "验证码 654321")))
    }

    @Test
    fun notificationExcludesSourceInitialSyncAndHistory() {
        assertTrue(shouldNotify("other", "self", false, true))
        assertFalse(shouldNotify("self", "self", false, true))
        assertFalse(shouldNotify("other", "self", true, true))
        assertFalse(shouldNotify("other", "self", false, false))
        assertTrue(shouldNotify("other", "self", false, false, 200, 100))
        assertFalse(shouldNotify("other", "self", false, false, 50, 100))
        assertFalse(shouldNotify("other", "self", true, false, 200, 100))
    }

    @Test
    fun restoredEpochDropsAKnownCursorButFirstSightOnlyRecordsIt() {
        assertFalse(epochReset(false, 0, 1))
        assertFalse(epochReset(true, 1, 1))
        assertTrue(epochReset(true, 1, 2))
        assertTrue(epochReset(true, 0, 1))
    }

    @Test
    fun manualModeAllowsOneBlankCardButNotAllBlank() {
        val active = listOf(SimMapping(17, 0, "", ""), SimMapping(42, 1, "", ""))
        val result = manualSimMappings(active, listOf("13800000000", " "))
        assertEquals("13800000000", result[0].phone)
        assertEquals("", result[1].phone)
        for (phones in listOf(listOf("", ""), listOf("abc", ""))) {
            try {
                manualSimMappings(active, phones)
                fail("invalid manual mapping accepted")
            } catch (_: IllegalStateException) {}
        }
    }

    @Test
    fun automaticModeKeepsTypedNumberOnlyForTheSameSubscription() {
        val stored = listOf(SimMapping(17, 0, "13800000000", "SIM1"))
        val active = listOf(SimMapping(17, 1, "", "SIM2"), SimMapping(42, 0, "", "SIM1"))
        val merged = mergeAutomaticSims(stored, active)
        assertEquals(SimMapping(17, 1, "13800000000", "SIM2"), merged[0])
        assertEquals("", merged[1].phone)
        val recognized = listOf(SimMapping(17, 0, "13900000000", "SIM1"))
        assertEquals(recognized, mergeAutomaticSims(stored, recognized))
        val typed = automaticSimMappings(active, listOf("13700000000", "13600000000"))
        assertEquals("13600000000", typed[0].phone)
        assertEquals("13700000000", typed[1].phone)
        assertEquals(recognized, automaticSimMappings(recognized, listOf("13700000000", "")))
    }

    @Test
    fun senderNormalizationOnlyRemovesPresentation() {
        assertTrue(sameSender("+86 138-0000-0000", "13800000000"))
        assertTrue(sameSender("008610086", "10086"))
        assertTrue(sameSender("8613800000000", "13800000000"))
        assertFalse(sameSender("10086", "10010"))
        assertFalse(sameSender("86100", "100"))
        assertTrue(sameSender("中国移动", "中国移动"))
    }

    @Test
    fun simCompatibilityNeedsOneKnownSide() {
        assertTrue(compatibleSims(7, "", -1, ""))
        assertTrue(compatibleSims(-1, "", 7, "+1234567890"))
        assertFalse(compatibleSims(7, "", 8, ""))
        assertTrue(compatibleSims(7, "+1234567890", 7, "+9876543210"))
        assertFalse(compatibleSims(-1, "", -1, ""))
        assertTrue(compatibleSims(-1, "+1234567890", -1, "+1234567890"))
        assertFalse(compatibleSims(7, "+1234567890", -1, "+1999999999"))
    }
}
