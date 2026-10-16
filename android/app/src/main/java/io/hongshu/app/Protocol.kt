package io.hongshu.app

import java.security.MessageDigest

data class SmsRecord(
    val receiver: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val subscriptionId: Int,
    val historical: Boolean = false,
    // The network timestamp that identifies a redelivered broadcast, set only when an
    // implausible network time was replaced by the local receive time. Nullable so that
    // copy(timestamp = ...) never carries a stale identity.
    val networkTime: Long? = null,
) {
    val identityTime: Long
        get() = networkTime ?: timestamp

    fun fingerprint(): String = sha256("$receiver\u0000$sender\u0000$timestamp\u0000$body")
}

fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        ""
    ) {
        "%02x".format(it)
    }

fun confirmedNumber(value: String): Boolean = Regex("^\\+?[0-9]{3,20}$").matches(value)

fun normalizeSimNumber(value: String): String = value.filterNot { it.isWhitespace() || it in "()-" }

internal const val SMS_CLOCK_SKEW_LIMIT = 10 * 60 * 1000L

// The PDU carries the SMSC's time, which some carriers and roaming networks get wrong by hours.
// Beyond the limit the local receive time is a better ordering and pairing key.
fun effectiveSmsTime(networkTime: Long, receivedAt: Long): Long =
    if (networkTime <= 0 || kotlin.math.abs(networkTime - receivedAt) > SMS_CLOCK_SKEW_LIMIT)
        receivedAt
    else networkTime

fun isHistoricalSMS(timestamp: Long, boundary: Long): Boolean = boundary > 0 && timestamp < boundary

internal fun manualSimMappings(active: List<SimMapping>, phones: List<String>): List<SimMapping> {
    check(active.isNotEmpty()) { "未检测到活动 SIM 卡" }
    check(active.all { it.slot in 0..1 } && active.map { it.slot }.distinct().size == active.size) {
        "当前 SIM 卡槽无法安全匹配"
    }
    // A blank field means "do not sync this card"; at least one card must be mapped.
    val mappings =
        active.map { sim ->
            val phone = normalizeSimNumber(phones[sim.slot].trim())
            check(phone.isEmpty() || confirmedNumber(phone)) {
                "请填写 SIM${sim.slot + 1} 的有效手机号，或留空表示不同步该卡"
            }
            sim.copy(phone = phone, label = "SIM${sim.slot + 1}")
        }
    check(mappings.any { it.phone.isNotEmpty() }) { "请至少填写一张 SIM 卡的手机号" }
    return mappings
}

// Recognized numbers win; a typed number only fills a card the system left blank.
internal fun automaticSimMappings(active: List<SimMapping>, phones: List<String>): List<SimMapping> =
    active.map { sim ->
        if (confirmedNumber(sim.phone) || sim.slot !in phones.indices) sim
        else {
            val typed = normalizeSimNumber(phones[sim.slot].trim())
            check(typed.isEmpty() || confirmedNumber(typed)) {
                "请填写 SIM${sim.slot + 1} 的有效手机号，或留空表示不同步该卡"
            }
            sim.copy(phone = typed)
        }
    }

// Many ROMs never expose the line number. A number the user typed for a subscription stays
// valid for that same subscription; a new card gets a new subscription ID and starts empty.
internal fun mergeAutomaticSims(stored: List<SimMapping>, active: List<SimMapping>): List<SimMapping> =
    active.map { sim ->
        if (confirmedNumber(sim.phone)) sim
        else
            stored
                .find { it.sub == sim.sub && confirmedNumber(it.phone) }
                ?.copy(slot = sim.slot, label = sim.label) ?: sim
    }

// Broadcast display addresses and provider rows disagree on country prefixes and spacing.
// Only presentation differences are removed; short codes and names compare verbatim.
fun normalizeSender(value: String): String {
    val compact = value.filterNot { it.isWhitespace() || it in "()-" }
    val national =
        when {
            compact.startsWith("+86") -> compact.substring(3)
            compact.startsWith("0086") -> compact.substring(4)
            compact.length == 13 && compact.startsWith("861") -> compact.substring(2)
            else -> compact
        }
    return national.ifEmpty { compact }
}

fun sameSender(left: String, right: String): Boolean =
    left == right || normalizeSender(left) == normalizeSender(right)

// One side must know its SIM; two unknown captures are never merged by content alone.
// Equal known subscriptions pair even when the number snapshot changed in between (the user
// corrected or the carrier updated the number); a known/unknown pair needs compatible numbers.
internal fun compatibleSims(
    leftSub: Int,
    leftReceiver: String,
    rightSub: Int,
    rightReceiver: String,
): Boolean {
    val receivers = leftReceiver.isEmpty() || rightReceiver.isEmpty() || leftReceiver == rightReceiver
    return when {
        leftSub >= 0 && rightSub >= 0 -> leftSub == rightSub
        leftSub >= 0 || rightSub >= 0 -> receivers
        else -> leftReceiver.isNotEmpty() && leftReceiver == rightReceiver
    }
}

fun combineParts(parts: List<String>): String = parts.joinToString("")

internal fun resolvedReceiver(mappings: List<SimMapping>, sub: Int, confirmedSub: Int): String {
    if (sub >= 0) return mappings.find { it.sub == sub }?.phone ?: ""
    return mappings.singleOrNull()?.takeIf { it.sub >= 0 && it.sub == confirmedSub }?.phone ?: ""
}

// A restored database reuses message IDs. Clients that already recorded an epoch
// must drop their synced cache when it changes. Zero is a legal epoch, so "never
// seen" is a separate flag rather than a sentinel value.
fun epochReset(seen: Boolean, stored: Long, observed: Long): Boolean = seen && stored != observed

fun shouldNotify(
    source: String,
    self: String,
    historical: Boolean,
    hasPreviousSync: Boolean,
    messageTime: Long = 0,
    pairedAt: Long = 0,
): Boolean {
    if (historical || source == self) return false
    // The first catch-up must still announce messages that arrived after this
    // device was paired. Older history stays silent.
    if (!hasPreviousSync) return pairedAt > 0 && messageTime >= pairedAt
    return true
}

// Inbox date and the broadcast PDU timestamp often differ by seconds. A later
// history import must not become a second copy of a message already captured live.
fun sameMessage(left: SmsRecord, right: SmsRecord, windowMillis: Long = 120_000): Boolean =
    left.sender == right.sender &&
        left.body == right.body &&
        kotlin.math.abs(left.timestamp - right.timestamp) <= windowMillis

fun uploadBatchCount(encodedSizes: List<Int>, budget: Int = 900000): Int {
    var bytes = 32
    var count = 0
    for (size in encodedSizes.take(100)) {
        if (size > budget) break
        if (bytes + size + 1 > budget) break
        bytes += size + 1
        count++
    }
    return count
}
