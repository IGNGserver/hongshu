package io.hongshu.app

import java.util.Calendar

private val OtpKeywords =
    Regex(
        "验证码|校验码|动态码|动态密码|动态口令|确认码|安全码|认证码|登录码|verification(?: code)?|passcode|one[- ]time(?: password| code)?|\\bcode\\b|\\botp\\b",
        RegexOption.IGNORE_CASE,
    )
private val OtpCandidate = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)?")
private val OtpAfterKeyword = Regex("(?:为|是|为您|is|:|：|\\s|\\(|（|\\)|）|\\[|\\]|【|】|=|'|\"|-)*", RegexOption.IGNORE_CASE)
private val OtpBeforeKeyword = Regex("(?:是|为|is|as|your|for|的|您|本次|账号|账户|[\\u4e00-\\u9fa5A-Za-z0-9]+|:|：|\\s|\\(|（|\\)|）|\\[|\\]|【|】|=|'|\"|-)*", RegexOption.IGNORE_CASE)
private val NonOtpCode = Regex("discount|coupon|promo|order (?:number|code|id)|tracking (?:number|code)|优惠码|邀请码|取件码", RegexOption.IGNORE_CASE)
private val StrongOtpKeyword = Regex("验证码|校验码|动态码|动态密码|动态口令|确认码|安全码|认证码|登录码|verification|passcode|one[- ]time|\\botp\\b", RegexOption.IGNORE_CASE)
private val OtpAlternative = Regex("^\\s*(?:或者|或|or|and|与|和|、|/)\\s*[A-Za-z0-9]+(?:-[A-Za-z0-9]+)?(?:[^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE)

/**
 * The one-time code in a verification SMS, or null. Only messages that say they carry a code
 * qualify, so order numbers, amounts and dates in ordinary messages are not offered for copying.
 */
fun extractOtp(body: String): String? {
    if (NonOtpCode.containsMatchIn(body) && !StrongOtpKeyword.containsMatchIn(body)) return null
    val candidates = mutableSetOf<String>()
    val tokens = OtpCandidate.findAll(body).toList()
    var first = 0
    for (keyword in OtpKeywords.findAll(body)) {
        while (first < tokens.size && tokens[first].range.last < keyword.range.first - 32) first++
        for (index in first until tokens.size) {
            val token = tokens[index]
            if (token.range.first > keyword.range.last + 1 + 32) break
            if (token.value.length !in 4..8 || token.value.none { it in '0'..'9' }) continue
            val start = token.range.first
            val end = token.range.last + 1
            val ks = keyword.range.first
            val ke = keyword.range.last + 1
            val adjacent =
                (start >= ke && start - ke <= 32 && OtpAfterKeyword.matches(body.substring(ke, start))) ||
                    (end <= ks && ks - end <= 32 && OtpBeforeKeyword.matches(body.substring(end, ks)))
            if (!adjacent) continue
            val before = body.substring(0, start)
            val after = body.substring(end)
            if (before.endsWith(".") || (after.startsWith(".") && after.getOrNull(1)?.isDigit() == true)) continue
            if (OtpAlternative.containsMatchIn(after.trimStart())) continue
            val tail = after.trimStart()
            if (listOf("元", "年", "月", "/").any { tail.startsWith(it) } || (tail.startsWith("-") && !tail.startsWith("--"))) continue
            candidates += token.value
        }
    }
    return candidates.singleOrNull()
}

/** Last four digits, the way people refer to their numbers ("尾号 1234"). */
internal fun phoneTail(phone: String): String = phone.filter(Char::isDigit).takeLast(4)

/** Short name for a receiving number: its card slot when known, otherwise its tail digits. */
fun simLabel(receiver: String, sims: List<SimMapping>): String {
    if (receiver.isEmpty()) return "本机"
    val sim = sims.firstOrNull { it.phone.isNotEmpty() && it.phone == receiver }
    val tail = phoneTail(receiver)
    return when {
        sim != null -> "卡${sim.slot + 1}" + if (tail.isNotEmpty()) " · $tail" else ""
        tail.isNotEmpty() -> "尾号 $tail"
        else -> receiver
    }
}

/** First visible character of a saved contact name, or null for bare numbers. */
fun avatarInitial(contact: String, sender: String): String? {
    val name = contact.ifEmpty { sender }.trim().trimStart('【', '[', '(', '（')
    val first = name.firstOrNull() ?: return null
    if (first.isDigit() || first == '+') return null
    return String(Character.toChars(name.codePointAt(0))).uppercase()
}

/** Carrier and service short codes (10086, 95588, 106…) rather than personal numbers. */
fun isServiceNumber(sender: String): Boolean {
    val digits = sender.removePrefix("+86")
    return digits.all(Char::isDigit) &&
        (digits.length in 3..6 || (digits.startsWith("106") && digits.length > 6))
}

/** Day separator text for a conversation: 今天, 昨天, 10月12日 周日, or with the year. */
fun dayLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val day = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun Calendar.sameDay(other: Calendar) =
        get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
            get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
    if (day.sameDay(today)) return "今天"
    if (day.sameDay(yesterday)) return "昨天"
    val week = "日一二三四五六"[day.get(Calendar.DAY_OF_WEEK) - 1]
    val date = "${day.get(Calendar.MONTH) + 1}月${day.get(Calendar.DAY_OF_MONTH)}日 周$week"
    return if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) date
    else "${day.get(Calendar.YEAR)}年$date"
}

fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) &&
        x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}
