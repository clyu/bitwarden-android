package com.x8bit.bitwarden.data.platform.datasource.disk

import androidx.core.content.edit
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalVaultDiskSourceTest {
    private val fakeSharedPreferences = FakeSharedPreferences()

    private val localVaultDiskSource = LocalVaultDiskSourceImpl(
        sharedPreferences = fakeSharedPreferences,
    )

    @Test
    fun `vaultFolderUri should pull from and update SharedPreferences`() {
        // Shared preferences and the disk source start with the same value.
        assertNull(localVaultDiskSource.vaultFolderUri)
        assertNull(fakeSharedPreferences.getString(VAULT_FOLDER_URI_KEY, null))

        // Updating the disk source updates shared preferences
        localVaultDiskSource.vaultFolderUri = VAULT_FOLDER_URI
        assertEquals(
            VAULT_FOLDER_URI,
            fakeSharedPreferences.getString(VAULT_FOLDER_URI_KEY, null),
        )

        // Updating shared preferences updates the disk source
        fakeSharedPreferences.edit { putString(VAULT_FOLDER_URI_KEY, null) }
        assertNull(localVaultDiskSource.vaultFolderUri)
    }
}

private const val VAULT_FOLDER_URI_KEY = "bwPreferencesStorage:localVaultFolderUri"
private const val VAULT_FOLDER_URI =
    "content://com.android.externalstorage.documents/tree/primary%3AVault"
