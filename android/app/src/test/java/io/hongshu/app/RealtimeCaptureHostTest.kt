package io.hongshu.app

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver

// Executes the same SQLite/permission/concurrency contracts without a connected phone.
// It cannot validate OEM delivery, process kills, Direct Boot, or the user's ROM.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RealtimeCaptureHostTest : RealtimeCaptureContract() {
    override fun providerResolver(provider: ContentProvider): ContentResolver {
        // Robolectric routes shadow queries by authority rather than honoring wrap's transport.
        ShadowContentResolver.registerProviderInternal("sms", provider)
        return super.providerResolver(provider)
    }
}
