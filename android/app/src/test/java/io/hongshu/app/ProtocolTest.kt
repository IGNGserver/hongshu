package io.hongshu.app

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
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
    fun notificationExcludesSourceInitialSyncAndHistory() {
        assertTrue(shouldNotify("other", "self", false, true))
        assertFalse(shouldNotify("self", "self", false, true))
        assertFalse(shouldNotify("other", "self", true, true))
        assertFalse(shouldNotify("other", "self", false, false))
    }
}
