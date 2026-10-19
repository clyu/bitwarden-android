package com.x8bit.bitwarden.data.auth.manager.model

/**
 * Models the result of changing how the master key of the active account is derived from its
 * master password.
 */
sealed class ChangeKdfResult {
    /**
     * The master key is derived with the new settings from now on.
     */
    data object Success : ChangeKdfResult()

    /**
     * The vault did not take the master password, as it knows the master password by another
     * hash than the one that this device derives: the master password or the settings were
     * changed on another device since this one last synced. Nothing was changed.
     */
    data object MasterPasswordRejected : ChangeKdfResult()

    /**
     * The settings could not be changed, and nothing was changed.
     */
    data class Error(
        val error: Throwable,
    ) : ChangeKdfResult()
}
