package io.hongshu.app

import java.security.MessageDigest

data class SmsRecord(
    val receiver: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val subscriptionId: Int,
    val historical: Boolean = false,
) {
    fun fingerprint(): String = sha256("$receiver\u0000$sender\u0000$timestamp\u0000$body")
}

fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        ""
    ) {
        "%02x".format(it)
    }

fun confirmedNumber(value: String): Boolean = Regex("^\\+?[0-9]{3,20}$").matches(value)

fun combineParts(parts: List<String>): String = parts.joinToString("")

// A restored database reuses message IDs. Clients that already recorded an epoch
// must drop their synced cache when it changes. Zero is a legal epoch, so "never
// seen" is a separate flag rather than a sentinel value.
fun epochReset(seen: Boolean, stored: Long, observed: Long): Boolean =
    seen && stored != observed

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
