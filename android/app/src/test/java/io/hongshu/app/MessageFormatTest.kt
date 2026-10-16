package io.hongshu.app

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatTest {
    @Test
    fun findsTheCodeInVerificationMessages() {
        assertEquals("482913", extractOtp("【某银行】您的验证码为482913，5分钟内有效。"))
        assertEquals("1234", extractOtp("Your verification code is 1234."))
        assertEquals("88231", extractOtp("动态密码：88231，请勿泄露"))
    }

    @Test
    fun prefersTheNumberNextToTheKeyword() {
        assertEquals("6604", extractOtp("订单 20231018 已支付，验证码 6604，客服 95588"))
    }

    @Test
    fun ignoresOrdinaryMessagesWithNumbers() {
        assertNull(extractOtp("您尾号 1234 的卡消费 5600 元"))
        assertNull(extractOtp("验证码已发送，请查收"))
    }

    @Test
    fun labelsReceiversBySimSlotOrTail() {
        val sims = listOf(SimMapping(1, 0, "+8613800138000", "SIM1"))

        assertEquals("卡1 · 8000", simLabel("+8613800138000", sims))
        assertEquals("尾号 9999", simLabel("13900139999", sims))
        assertEquals("本机", simLabel("", sims))
    }

    @Test
    fun avatarsUseNamesButNotNumbers() {
        assertEquals("张", avatarInitial("张三", "13800138000"))
        assertEquals("招", avatarInitial("", "【招商银行】"))
        assertEquals("A", avatarInitial("alice", ""))
        assertNull(avatarInitial("", "+8613800138000"))
    }

    @Test
    fun recognisesServiceShortCodes() {
        assertTrue(isServiceNumber("10086"))
        assertTrue(isServiceNumber("1069012345678"))
        assertFalse(isServiceNumber("13800138000"))
        assertFalse(isServiceNumber("招商银行"))
    }

    @Test
    fun daySeparatorsUseRelativeNames() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 16, 12, 0) }.timeInMillis
        val day = 24 * 60 * 60 * 1000L

        assertEquals("今天", dayLabel(now - 60_000, now))
        assertEquals("昨天", dayLabel(now - day, now))
        assertEquals("10月12日 周一", dayLabel(now - 4 * day, now))
        assertEquals("2025年10月16日 周四", dayLabel(now - 365 * day, now))
    }
}
