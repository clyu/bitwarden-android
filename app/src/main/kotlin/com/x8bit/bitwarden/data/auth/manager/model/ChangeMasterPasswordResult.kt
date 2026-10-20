package com.x8bit.bitwarden.data.auth.manager.model

/**
 * Models the result of changing the master password of the active account.
 */
sealed class ChangeMasterPasswordResult {
    /**
     * The master password was changed, and the account was logged out of this device.
     */
    data object Success : ChangeMasterPasswordResult()

    /**
     * The vault did not take the current master password, as it knows the master password by
     * another hash than the one that this device derives: the master password or the encryption
     * key settings were changed on another device since this one last synced. Nothing was
     * changed.
     */
    data object MasterPasswordRejected : ChangeMasterPasswordResult()

    /**
     * The master password could not be changed, and nothing was changed.
     */
    data class Error(
        val error: Throwable,
    ) : ChangeMasterPasswordResult()
}
