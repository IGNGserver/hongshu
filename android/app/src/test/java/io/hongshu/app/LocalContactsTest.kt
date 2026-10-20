package io.hongshu.app

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalContactsTest {
    private lateinit var context: Application
    private lateinit var provider: SyntheticContacts
    private lateinit var source: LocalContacts
    private val sender = "13800000001"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("hongshu", 0).edit().clear().commit()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        provider = SyntheticContacts()
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply {
            authority = ContactsContract.AUTHORITY
            exported = true
        })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        source = LocalContacts(context)
        Config(context).localContacts = true
        source.setForeground(true)
    }

    @Test fun numbersRequireFullUnambiguousMatches() {
        assertEquals(sender, localContactNumber("+86 (138) 0000-0001"))
        assertEquals(sender, localContactNumber("008613800000001"))
        assertEquals("+12025550123", localContactNumber("+1 202-555-0123"))
        assertNotEquals(localContactNumber("+12025550123"), localContactNumber("2025550123"))
        for (value in listOf("10086", "+8610086", "106901234567", "张三", "13800000001x")) assertNull(localContactNumber(value))
        val candidate = ContactCandidate(1, "+8613800000001", "合成联系人", null)
        assertEquals("合成联系人", chooseLocalContact(sender, listOf(candidate, candidate))?.name)
        assertNull(chooseLocalContact(sender, listOf(candidate, candidate.copy(id = 2))))
        assertNull(chooseLocalContact("13900000001", listOf(candidate)))
        assertNull(chooseLocalContact(sender, listOf(candidate.copy(name = " "))))
    }

    @Test fun defaultIsOffAndUnavailableAccessNeverQueriesProvider() {
        Config(context).localContacts = false
        assertNull(source.lookup(sender))
        assertTrue(source.matchingSenders("合成", listOf(sender)).isEmpty())
        assertNull(source.photo("content://com.android.contacts/photos/1"))
        assertEquals(0, provider.queries)
        Config(context).localContacts = true
        source.setForeground(false)
        assertNull(source.lookup(sender))
        assertEquals(0, provider.queries)
        context.getSharedPreferences("hongshu", 0).edit().remove("local_contacts").commit()
        assertFalse(Config(context).localContacts)
    }

    @Test fun cachedNamesMissesAndPermissionChangesAreInvalidated() {
        provider.rows = listOf(ContactCandidate(1, sender, "合成甲", null))
        assertEquals("合成甲", source.lookup(sender)?.name)
        assertEquals("合成甲", source.lookup(sender)?.name)
        assertEquals(1, provider.queries)
        provider.rows = listOf(ContactCandidate(1, sender, "合成乙", null))
        source.invalidate()
        assertEquals("合成乙", source.lookup(sender)?.name)
        shadowOf(context).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertNull(source.lookup(sender))
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        provider.rows = emptyList()
        assertNull(source.lookup(sender))
        provider.rows = listOf(ContactCandidate(1, sender, "合成丙", null))
        assertNull(source.lookup(sender))
        source.invalidate()
        assertEquals("合成丙", source.lookup(sender)?.name)
        Config(context).localContacts = false
        assertNull(source.lookup(sender))
        Config(context).localContacts = true
        provider.rows = emptyList()
        assertNull(source.lookup(sender))
    }

    @Test fun ambiguousProviderMatchesFailuresAndLateResultsNeverDecorateUi() {
        val row = ContactCandidate(1, sender, "合成甲", null)
        provider.rows = listOf(row, row.copy(id = 2))
        assertNull(source.lookup(sender))
        source.invalidate()
        provider.rows = listOf(row)
        provider.onQuery = { source.invalidate() }
        assertNull(source.lookup(sender))
        provider.onQuery = null
        assertEquals("合成甲", source.lookup(sender)?.name)
        source.invalidate()
        provider.fail = true
        assertNull(source.lookup(sender))
        assertNull(source.photo("https://example.invalid/avatar.png"))
        assertNull(source.photo("content://other.provider/photo"))
        assertNull(source.photo("content://com.android.contacts/missing"))
    }

    @Test fun nameSearchUsesOnlyLocalSendersAndRejectsAmbiguities() {
        provider.rows = listOf(ContactCandidate(1, sender, "合成朋友", null))
        assertEquals(setOf(sender, "+86$sender"), source.matchingSenders("朋友", listOf(sender, "+86$sender", "13900000001", "10086")))
        assertTrue(source.matchingSenders("不存在", listOf(sender)).isEmpty())
        provider.rows = provider.rows + ContactCandidate(2, sender, "合成朋友", null)
        source.invalidate()
        assertTrue(source.matchingSenders("朋友", listOf(sender)).isEmpty())
    }

    @Test fun cacheIsBoundedAndBackgroundResumeDropsOldEntries() {
        for (i in 0..300) {
            val number = "139" + i.toString().padStart(8, '0')
            provider.rows = listOf(ContactCandidate(i.toLong(), number, "合成 $i", null))
            assertNotNull(source.lookup(number))
        }
        provider.rows = emptyList()
        assertNull(source.lookup("13900000000"))
        source.setForeground(false)
        source.setForeground(true)
        assertNull(source.lookup("13900000300"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun photosAreSmallMemoryOnlyAndPermissionRevocationDiscardsTheBitmap() {
        val file = java.io.File.createTempFile("contact-", ".png", context.cacheDir)
        try {
            val original = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
            file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            provider.photoFile = file
            val uri = "content://com.android.contacts/photos/1"
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            assertEquals(1024, bounds.outWidth)
            context.contentResolver.openInputStream(Uri.parse(uri))!!.use { assertTrue(it.readBytes().isNotEmpty()) }
            val photo = source.photo(uri)
            assertNotNull(photo)
            assertTrue(photo!!.width <= 256 && photo.height <= 256)
            assertSame(photo, source.photo(uri))
            source.invalidate()
            assertNotSame(photo, source.photo(uri))
            shadowOf(context).denyPermissions(Manifest.permission.READ_CONTACTS)
            assertNull(source.photo(uri))
        } finally { file.delete() }
    }

    @Test fun providerChangesRefreshCachedNamesAndObserverCanBeReleased() {
        provider.rows = listOf(ContactCandidate(1, sender, "合成旧名称", null))
        assertEquals("合成旧名称", source.lookup(sender)?.name)
        var changes = 0
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { changes++; source.invalidate() }
        }
        source.observe(observer, true)
        try {
            provider.rows = listOf(ContactCandidate(1, sender, "合成新名称", null))
            context.contentResolver.notifyChange(Uri.parse("content://com.android.contacts/contacts/1"), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, changes)
            assertEquals("合成新名称", source.lookup(sender)?.name)
        } finally { source.observe(observer, false) }
        context.contentResolver.notifyChange(ContactsContract.Contacts.CONTENT_URI, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)
    }

    @Test fun localSearchPreservesFiltersPaginationAndNeverWritesContactsIntoSms() {
        val name = "contacts-${java.util.UUID.randomUUID()}.db"
        try {
            Store(context, name).use { store ->
                val receiver = "+8613800000000"
                fun message(id: Int, from: String, sim: String = receiver) = JSONObject()
                    .put("id", id).put("device_id", "remote").put("receiver", sim)
                    .put("sender", from).put("body", "synthetic $id").put("timestamp", id * 1000)
                store.applySync(JSONArray().put(message(1, sender)).put(message(2, "13900000001"))
                    .put(message(3, sender, "+8613900000000")), 3)
                store.queue(SmsRecord(receiver, "13700000001", "synthetic local", 4000, 1))
                val matched = setOf(sender, "13700000001")
                assertEquals(setOf(sender, "13900000001", "13700000001"), store.contactSearchSenders().toSet())
                assertEquals(listOf("13700000001", sender), store.messagesWithLocal(q = "合成姓名", localContactSenders = matched).map { it.sender })
                assertEquals(listOf("13700000001"), store.messagesWithLocal(q = "合成姓名", localContactSenders = matched, limit = 1).map { it.sender })
                assertEquals(1, store.messages(q = "合成姓名", sim = receiver, localContactSenders = matched).size)
                assertEquals(0, store.messages(q = "合成姓名", group = "otp", localContactSenders = matched).size)
                assertEquals(3, store.groupCounts("合成姓名", localContactSenders = matched)["all"])
                assertEquals(2, store.groupCounts("合成姓名", receiver, matched)["all"])
                assertTrue(store.messagesWithLocal(q = "合成姓名").isEmpty())
                assertTrue(store.messagesWithLocal(q = "合成姓名", localContactSenders = setOf("' OR 1=1 --")).isEmpty())
                val large = (0..1200).map { "136" + it.toString().padStart(8, '0') }.toSet() + sender
                assertEquals(1, store.messages(q = "合成姓名", localContactSenders = large).size)
                assertTrue(store.messages().all { it.contact.isEmpty() })
                assertFalse(store.pending().single().second.json().toString().contains("合成姓名"))
                assertTrue(store.pending().single().second.json().keys().asSequence().none { it in setOf("contact", "photo", "name", "lookup_key") })
            }
        } finally { context.deleteDatabase(name) }
    }

    private class SyntheticContacts : ContentProvider() {
        var rows = emptyList<ContactCandidate>()
        var queries = 0
        var fail = false
        var photoFile: java.io.File? = null
        var onQuery: (() -> Unit)? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): MatrixCursor {
            queries++
            if (fail) throw SecurityException("synthetic access revoked")
            onQuery?.invoke()
            val columns = projection ?: emptyArray()
            return MatrixCursor(columns).apply {
                for (row in rows.filter { args.isNullOrEmpty() || it.name.contains(args[0], ignoreCase = true) }) {
                    addRow(columns.map { column -> val value: Any? = when (column) {
                        ContactsContract.PhoneLookup._ID -> row.id
                        ContactsContract.PhoneLookup.NUMBER, ContactsContract.CommonDataKinds.Phone.NUMBER -> row.number
                        ContactsContract.PhoneLookup.DISPLAY_NAME -> row.name
                        ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI -> row.photo
                        else -> null
                    }; value }.toTypedArray())
                }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(photoFile ?: throw java.io.FileNotFoundException(), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
