package com.x8bit.bitwarden.data.platform.datasource.disk

/**
 * Primary access point for disk information about where the local vault is kept.
 */
interface LocalVaultDiskSource {
    /**
     * The URI of the folder that the user chose to keep the vault in, or `null` if the user did
     * not choose one.
     */
    var vaultFolderUri: String?
}
