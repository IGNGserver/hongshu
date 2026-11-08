package io.hongshu.app

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

/**
 * The hub, Web client and Android client must agree on the error-code vocabulary.
 *
 * The Go test proves the shared contract matches what the server can emit and the Web
 * test proves the browser never maps a phantom code; this test proves Android still has
 * a specific user-facing message for every code it claims to handle, so a hub rename
 * cannot silently degrade the app to "中枢返回错误 0（code）".
 */
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
class ProtocolContractTest {
    private fun contract(): JSONObject {
        val directory = System.getProperty("hongshu.protocol.dir")
            ?: error("hongshu.protocol.dir is not set by the build")
        val file = java.io.File(directory, "error-codes.v1.json")
        check(file.isFile) { "shared protocol contract is missing: $file" }
        return JSONObject(file.readText())
    }

    @Test fun sharedContractIsVersionedAndComplete() {
        val contract = contract()
        assertEquals(1, contract.getInt("version"))
        val codes = contract.getJSONArray("codes")
        assertTrue("contract is suspiciously small: ${codes.length()}", codes.length() > 40)
        val seen = mutableSetOf<String>()
        for (index in 0 until codes.length()) {
            val code = codes.getString(index)
            assertTrue("duplicate contract code: $code", seen.add(code))
            assertFalse("contract code must be a wire token: $code", code.isBlank() || code.contains(' '))
        }
        assertTrue(
            "dynamic AI codes must stay documented",
            contract.getJSONObject("dynamic_codes").getJSONArray("patterns").length() > 0,
        )
    }

    @Test fun everyContractCodeProducesAUsableMessage() {
        val codes = contract().getJSONArray("codes")
        for (index in 0 until codes.length()) {
            val code = codes.getString(index)
            for (status in intArrayOf(0, 400, 401, 409, 429, 503)) {
                val text = describeApiError(status, code)
                assertTrue("blank message for $code/$status", text.isNotBlank())
                assertFalse("message still has a format placeholder for $code", text.contains("%d"))
            }
        }
    }

    @Test fun uploadAckVocabularyMatchesTheSharedContract() {
        // Upload results arrive inside a 200 acks[] entry, so these are separate from the
        // top-level error codes. A new hub-side ack error must force a client update here.
        val ack = contract().getJSONObject("ack_errors")
        fun accepted(surface: String): Set<String> = buildSet {
            add("")
            val entries = ack.getJSONArray(surface)
            for (index in 0 until entries.length()) add(entries.getString(index))
        }
        assertEquals(setOf("", "invalid_message", "sim_not_confirmed"), accepted("messages"))
        assertEquals(setOf("", "invalid_call", "sim_not_confirmed"), accepted("calls"))
    }

    @Test fun handledCodesKeepTheirSpecificText() {
        // These have dedicated branches; losing one would leak a raw code into the UI.
        val handled = listOf(
            "database_unavailable", "upload_disabled", "unauthorized", "invalid_password",
            "rate_limited", "authentication_busy", "invalid_message", "invalid_batch",
            "invalid_sim", "epoch_changed", "forbidden", "admin_required", "origin_denied",
            "origin_required", "invalid_name",
        )
        val contract = contract()
        val known = buildSet {
            val codes = contract.getJSONArray("codes")
            for (index in 0 until codes.length()) add(codes.getString(index))
            val ack = contract.getJSONObject("ack_errors")
            for (surface in listOf("messages", "calls")) {
                val entries = ack.getJSONArray(surface)
                for (index in 0 until entries.length()) add(entries.getString(index))
            }
        }
        for (code in handled) {
            assertTrue(
                "Android handles $code but it is not in the shared contract",
                known.contains(code),
            )
            assertFalse(
                "$code lost its specific message",
                describeApiError(0, code).startsWith("中枢返回错误"),
            )
        }
    }
}
