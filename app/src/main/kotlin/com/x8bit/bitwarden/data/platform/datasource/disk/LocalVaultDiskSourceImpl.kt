package com.x8bit.bitwarden.data.platform.datasource.disk

import android.content.SharedPreferences
import com.bitwarden.data.datasource.disk.BaseDiskSource

private const val VAULT_FOLDER_URI_KEY = "localVaultFolderUri"

/**
 * Primary implementation of [LocalVaultDiskSource].
 */
class LocalVaultDiskSourceImpl(
    sharedPreferences: SharedPreferences,
) : BaseDiskSource(sharedPreferences = sharedPreferences),
    LocalVaultDiskSource {
    override var vaultFolderUri: String?
        get() = getString(key = VAULT_FOLDER_URI_KEY)
        set(value) {
            putString(key = VAULT_FOLDER_URI_KEY, value = value)
        }
}
