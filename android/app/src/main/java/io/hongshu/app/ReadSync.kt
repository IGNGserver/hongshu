package io.hongshu.app

import java.net.URLEncoder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private fun Store.readMeta(key: String, fallback: Long = 0): Long = readableDatabase
    .rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key))
    .use { if (it.moveToFirst()) it.getLong(0) else fallback }

internal fun Store.readStateRevision(): Long =
    if (readMeta("read_state_seen") == 1L && readMeta("read_state_epoch") == epoch())
        readMeta("read_state_revision", -1) else -1

internal fun Store.readStateHead(): Long = readMeta("read_state_head", -1)

// A remote read must not create another outbound read receipt. Preserve newer
// offline local reads; only a completed, revision-fenced snapshot is checkpointed.
internal fun Store.applyReadState(page: JSONObject) = synchronized(identityLock) {
    val pageEpoch = page.getLong("epoch")
    val revision = page.getLong("revision")
    val head = page.getLong("head")
    val global = page.getLong("global_watermark_id")
    check(pageEpoch == epoch() && revision >= 0 && head >= 0 && global in 0..head) { "Invalid read state snapshot" }
    val items = page.getJSONArray("items")
    val values = (0 until items.length()).map { i ->
        val item = items.getJSONObject(i)
        val sender = item.get("sender") as? String ?: error("Invalid read sender")
        val id = item.getLong("message_id")
        check(sender.isNotEmpty() && sender.toByteArray(Charsets.UTF_8).size <= 100 && !sender.contains('\u0000') && id in 0..head) {
            "Invalid read watermark"
        }
        sender to id
    }
    val complete = !page.getBoolean("more")
    val db = writableDatabase
    var changed = false
    db.beginTransaction()
    try {
        if (global > readMeta("global_watermark_id")) {
            db.execSQL("UPDATE meta SET value=? WHERE key='global_watermark_id'", arrayOf(global))
            changed = true
        }
        for ((sender, id) in values) {
            val previous = db.rawQuery("SELECT last_read_message_id FROM conversation_reads WHERE sender=?", arrayOf(sender))
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (id > previous) {
                db.execSQL("INSERT OR REPLACE INTO conversation_reads VALUES (?,?)", arrayOf<Any>(sender, id))
                changed = true
            }
        }
        if (complete) {
            for ((key, value) in listOf("read_state_revision" to revision, "read_state_head" to head, "read_state_epoch" to pageEpoch, "read_state_seen" to 1L)) {
                db.execSQL("INSERT OR REPLACE INTO meta VALUES (?,?)", arrayOf<Any>(key, value))
            }
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    if (changed) Store.changes.value++
}

internal suspend fun syncReadState(store: Store, request: (String) -> JSONObject): Boolean {
    val epoch = store.epoch()
    check(epoch > 0) { "Read state requires a synchronized epoch" }
    val previous = store.readStateRevision()
    var path = "/read-state?epoch=$epoch&limit=200" + if (previous >= 0) "&since=$previous&head=${store.readStateHead()}" else ""
    var expectedRevision: Long? = null
    var expectedHead: Long? = null
    val cursors = mutableSetOf<String>()
    while (true) {
        coroutineContext.ensureActive()
        val page = try { request(path) } catch (e: ApiException) {
            // Older hubs do not provide this additive metadata API.
            if (e.status == 404 || e.status == 405) return true
            if (e.status == 409 && e.code in setOf("read_state_changed", "epoch_changed")) return false
            throw e
        }
        coroutineContext.ensureActive()
        val revision = page.getLong("revision")
        val head = page.getLong("head")
        check(page.getLong("epoch") == epoch && (expectedRevision == null || expectedRevision == revision) && (expectedHead == null || expectedHead == head)) {
            "Read snapshot changed between pages"
        }
        expectedRevision = revision
        expectedHead = head
        val more = page.getBoolean("more")
        val next = page.getString("next")
        if (more) {
            val items = page.getJSONArray("items")
            check(items.length() > 0 && next == items.getJSONObject(items.length() - 1).getString("sender") && cursors.add(next)) {
                "Read pagination did not advance"
            }
        }
        store.applyReadState(page)
        if (!more) return true
        path = "/read-state?epoch=$epoch&revision=$revision&head=$head&limit=200&after=" + URLEncoder.encode(next, "UTF-8")
    }
}
