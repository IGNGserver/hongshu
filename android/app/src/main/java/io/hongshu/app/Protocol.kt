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

fun shouldNotify(
    source: String,
    self: String,
    historical: Boolean,
    hasPreviousSync: Boolean,
): Boolean = hasPreviousSync && !historical && source != self

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
