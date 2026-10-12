package com.x8bit.bitwarden.data.platform.manager.localvault

import android.net.Uri
import com.bitwarden.network.local.LocalVaultFileSystem
import com.x8bit.bitwarden.data.platform.manager.localvault.model.SetVaultFolderResult

/**
 * Manages where on the device the vault is kept.
 */
interface LocalVaultStorageManager {
    /**
     * The place the vault is kept in. It always reflects the current choice of the user.
     */
    val fileSystem: LocalVaultFileSystem

    /**
     * A name for the folder that the user chose to keep the vault in, or `null` if the user did
     * not choose one and the vault is kept in the storage of the app.
     */
    val vaultFolderName: String?

    /**
     * Keeps the vault in the folder with the given [uri] from now on. The accounts that exist
     * where the vault was kept so far are copied to the folder, unless it already has them.
     */
    suspend fun setVaultFolder(uri: Uri): SetVaultFolderResult
}
