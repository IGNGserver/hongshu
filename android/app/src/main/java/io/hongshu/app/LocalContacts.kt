package io.hongshu.app

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Deliberately unrelated to hub contact_snapshot and SmsRecord. Never serialized. */
internal data class LocalContact(val name: String, val photo: String? = null)

/** No suffix matching, international-region guesses, service codes or sender-name matching. */
internal fun localContactNumber(value: String): String? {
    val compact = value.filterNot { it.isWhitespace() || it in "()-" }
    if (!Regex("^\\+?[0-9]{7,20}$").matches(compact) || isServiceNumber(compact)) return null
    val chinese = when {
        compact.startsWith("+86") -> compact.removePrefix("+86")
        compact.startsWith("0086") -> compact.removePrefix("0086")
        else -> compact
    }
    if (isServiceNumber(chinese)) return null
    return if (Regex("^1[3-9][0-9]{9}$").matches(chinese)) chinese else compact
}

internal data class ContactCandidate(val id: Long, val number: String, val name: String, val photo: String?)

internal fun chooseLocalContact(sender: String, rows: List<ContactCandidate>): LocalContact? {
    val key = localContactNumber(sender) ?: return null
    val matches = rows.filter { localContactNumber(it.number) == key }.distinctBy { it.id }
    val row = matches.singleOrNull() ?: return null
    return row.name.trim().takeIf { it.isNotEmpty() }?.let { LocalContact(it, row.photo) }
}

/** IO-only access; bounded memory caches, no disk cache and no network dependencies. */
internal class LocalContacts(private val context: Context) {
    private val config = Config(context)
    val revision = MutableStateFlow(0L)
    @Volatile private var foreground = false
    private val names = LruCache<String, LocalContact>(256)
    private val misses = LruCache<String, Boolean>(256)
    private val photos = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun available(): Boolean = foreground && config.localContacts && hasPermission(context, Manifest.permission.READ_CONTACTS)

    fun observe(observer: ContentObserver, register: Boolean) {
        if (register) context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        else context.contentResolver.unregisterContentObserver(observer)
    }

    @Synchronized fun invalidate() {
        names.evictAll()
        misses.evictAll()
        photos.evictAll()
        revision.value++
    }

    @Synchronized fun setForeground(value: Boolean) {
        foreground = value
        invalidate()
    }

    fun lookup(sender: String): LocalContact? {
        if (!available()) { invalidateCaches(); return null }
        val version = revision.value
        val number = localContactNumber(sender) ?: return null
        names.get(number)?.let { return it }
        if (misses.get(number) == true) return null
        val found = runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(sender)),
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.NUMBER,
                    ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null,
            )?.use { c ->
                val rows = buildList {
                    while (c.moveToNext()) add(ContactCandidate(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3)))
                }
                chooseLocalContact(sender, rows)
            }
        }.getOrNull()
        synchronized(this) {
            if (!available() || version != revision.value) return null
            if (found == null) misses.put(number, true) else names.put(number, found)
        }
        return found
    }

    fun photo(uri: String): Bitmap? {
        if (!available()) { invalidateCaches(); return null }
        val version = revision.value
        photos.get(uri)?.let { return it }
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "content" || parsed.authority != ContactsContract.AUTHORITY) return null
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) inSampleSize *= 2
            }
            context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
        synchronized(this) {
            if (!available() || version != revision.value) return null
            if (bitmap != null) photos.put(uri, bitmap)
        }
        return bitmap
    }

    /** Returns original SMS senders, not contact names. Ambiguities are checked by PhoneLookup. */
    fun matchingSenders(query: String, senders: List<String>): Set<String> {
        if (query.isBlank() || !available()) return emptySet()
        val version = revision.value
        val numbers = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "instr(lower(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}),lower(?))>0",
                arrayOf(query.trim()), null,
            )?.use { c -> buildSet { while (c.moveToNext()) localContactNumber(c.getString(0).orEmpty())?.let(::add) } }.orEmpty()
        }.getOrDefault(emptySet())
        val matches = senders.filter { localContactNumber(it) in numbers && lookup(it)?.name?.contains(query.trim(), ignoreCase = true) == true }.toSet()
        return if (available() && version == revision.value) matches else emptySet()
    }

    private fun invalidateCaches() {
        names.evictAll(); misses.evictAll(); photos.evictAll()
    }
}

internal val LocalContactSource = staticCompositionLocalOf<LocalContacts?> { null }
internal val LocalContactRevision = staticCompositionLocalOf { 0L }

@Composable
internal fun ObserveLocalContacts(source: LocalContacts, enabled: Boolean, permission: Boolean) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(source, owner, enabled, permission) {
        val handler = Handler(Looper.getMainLooper())
        var pending = false
        val invalidate = Runnable { pending = false; source.invalidate() }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // Invalidate immediately (no stale name/photo), debounce repeated provider events.
                if (!pending) source.invalidate()
                pending = true
                handler.removeCallbacks(invalidate)
                handler.postDelayed(invalidate, 300)
            }
        }
        val lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) source.setForeground(true)
            if (event == Lifecycle.Event.ON_PAUSE) source.setForeground(false)
        }
        source.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        owner.lifecycle.addObserver(lifecycle)
        if (enabled && permission) runCatching { source.observe(observer, register = true) }
        onDispose {
            handler.removeCallbacks(invalidate)
            source.observe(observer, register = false)
            owner.lifecycle.removeObserver(lifecycle)
            source.setForeground(false)
        }
    }
}

@Composable
internal fun rememberLocalContact(sender: String): LocalContact? {
    val source = LocalContactSource.current
    val revision = LocalContactRevision.current
    var contact by remember(source, sender, revision) { mutableStateOf<LocalContact?>(null) }
    LaunchedEffect(source, sender, revision) {
        contact = withContext(Dispatchers.IO) { source?.lookup(sender) }
    }
    return contact.takeIf { source?.available() == true }
}
