package com.x8bit.bitwarden.data.auth.datasource.disk

import com.bitwarden.core.di.CoreModule
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import com.x8bit.bitwarden.data.platform.datasource.disk.legacy.LegacySecureStorageMigrator
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests that [AuthDiskSourceImpl.uniqueAppId], which names the directory of each account that
 * the local vault writes the changes of the app to, is made only once.
 */
class AuthDiskSourceUniqueAppIdTest {
    private val sharedPreferences = spyk(FakeSharedPreferences())

    private val authDiskSource = AuthDiskSourceImpl(
        encryptedSharedPreferences = FakeSharedPreferences(),
        keystoreEncryptedPreferences = FakeSharedPreferences(),
        sharedPreferences = sharedPreferences,
        legacySecureStorageMigrator = mockk<LegacySecureStorageMigrator> {
            every { migrateIfNecessary() } just runs
        },
        json = CoreModule.providesJson(buildInfoManager = mockk(relaxed = true)),
    )

    @Test
    fun `uniqueAppId should be the one that another thread stored while it waited for it`() {
        // Another thread stores its ID between the first time that the ID is read and the time
        // that it is read again under the lock.
        every { sharedPreferences.getString(APP_ID_KEY, null) } returnsMany listOf(null, OTHER_ID)

        assertEquals(OTHER_ID, authDiskSource.uniqueAppId)
        // No ID of its own was made.
        assertNull(sharedPreferences.all[APP_ID_KEY])
    }
}

private const val APP_ID_KEY: String = "bwPreferencesStorage:appId"
private const val OTHER_ID: String = "mockOtherId"
