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
        assertEquals("G-397783", extractOtp("【Google】G-397783 是您的 Google 验证码。请勿与任何人分享此码。"))
        assertEquals("G-315643", extractOtp("G-315643 is your Google verification code"))
        assertEquals("719-839", extractOtp("Your Stripe verification code is: 719-839."))
        assertEquals("123-456", extractOtp("Your code is 123-456"))
        assertEquals("123456", extractOtp("您的验证码是【123456】"))
        assertEquals("482913", extractOtp("Your code is \"482913\""))
        assertEquals("987654", extractOtp("登录验证码为：'987654'，请妥善保管"))
        assertEquals("84920", extractOtp("Telegram code: 84920"))
        assertEquals("58291", extractOtp("Use 58291 as your login code"))
        assertEquals("84921", extractOtp("Your security code is 84921"))
        assertEquals("394821", extractOtp("Your one-time passcode is 394821"))
        assertEquals("582910", extractOtp("【京东】验证码：582910。您正在登录京东账号，验证码5分钟内有效。"))
        assertEquals("748291", extractOtp("【华为】748291 是您的华为账号验证码，5分钟内有效。"))
        assertEquals("482019", extractOtp("【小米科技】482019（验证码）。请勿向任何人泄露。"))
        assertEquals("482913", extractOtp("您的动态口令是 482913"))
        assertEquals("849201", extractOtp("849201 is your WhatsApp code"))
        assertEquals("84921", extractOtp("Your Steam verification code is 84921"))
        assertEquals("849201", extractOtp("Your Amazon OTP is: 849201. Don't share it."))
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
