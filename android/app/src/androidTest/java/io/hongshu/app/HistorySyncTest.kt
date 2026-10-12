package io.hongshu.app

import android.Manifest
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class HistorySyncTest {
    private class Inbox : ContentProvider() {
        var rows = 1205
        var lastTimestamp = 100L

        override fun onCreate() = true

        override fun getType(uri: Uri) = "vnd.android.cursor.dir/sms"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            assertEquals("_id>?", selection)
            val after = selectionArgs!![0].toLong()
            return MatrixCursor(arrayOf("_id", "address", "body", "date", "sub_id")).apply {
                for (id in 1..rows) if (id > after)
                    addRow(
                        arrayOf<Any>(
                            id,
                            "10086",
                            "synthetic $id",
                            if (id == rows) lastTimestamp else 100L,
                            7,
                        )
                    )
            }
        }
    }

    @Test
    fun enablingSyncAutomaticallyIncludesPreConnectionHistoryAndResumesWithoutDuplicates() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefs = base.getSharedPreferences("hongshu", Context.MODE_PRIVATE)
        val savedUpload = Config(base).upload
        val savedAuto = Config(base).automaticSim
        val savedPending = Config(base).inboxScanPending
        val inbox = Inbox()
        val resolver = ContentResolver.wrap(inbox)
        val context =
            object : ContextWrapper(base) {
                override fun getContentResolver(): ContentResolver = resolver

                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    if (permission == Manifest.permission.READ_SMS)
                        PackageManager.PERMISSION_GRANTED
                    else base.checkPermission(permission, pid, uid)
            }
        val name = "history-${java.util.UUID.randomUUID()}.db"
        try {
            Config(base).upload = true
            Config(base).automaticSim = false
            Store(context, name).use { store ->
                store.resetForNewIdentity(connectedAt = 1000)
                store.initializeHistoryBoundary(2000)
                store.saveSim(SimMapping(7, 0, "+1234567890", "SIM1"))
                assertEquals(1000, scanInbox(context, store, false, true))
                assertEquals(1000L, store.providerWatermark())
                assertTrue(Config(base).inboxScanPending)
                assertTrue(store.pending().all { it.second.historical })
            }
            Store(context, name).use { store ->
                assertEquals(205, scanInbox(context, store, false, true))
                assertFalse(Config(base).inboxScanPending)
                assertEquals(1205, store.pendingCount())
                assertEquals(0, scanInbox(context, store, false, true))
                inbox.rows++
                inbox.lastTimestamp = 2100
                assertEquals(1, scanInbox(context, store, false, true))
                val live =
                    store.readableDatabase
                        .rawQuery("SELECT historical FROM outbox WHERE key='provider:1206'", null)
                        .use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                assertEquals(0, live)
                Config(base).upload = false
                inbox.rows++
                assertEquals(0, scanInbox(context, store, false, true))
                assertEquals(1206, store.pendingCount())
                assertEquals("未启用本机采集", catchUpInbox(context))
            }
        } finally {
            base.deleteDatabase(name)
            prefs
                .edit()
                .putBoolean("upload", savedUpload)
                .putBoolean("automatic_sim", savedAuto)
                .putBoolean("inbox_scan_pending", savedPending)
                .commit()
        }
    }
}
