package com.x8bit.bitwarden.data.platform.manager.localvault.model

/**
 * Models the result of choosing the folder that the vault is kept in.
 */
sealed class SetVaultFolderResult {
    /**
     * The vault is kept in the folder from now on.
     */
    data object Success : SetVaultFolderResult()

    /**
     * The folder cannot be used. The vault is kept where it was.
     */
    data class Error(
        val error: Throwable,
    ) : SetVaultFolderResult()
}
