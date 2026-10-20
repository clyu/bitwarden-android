package com.x8bit.bitwarden.data.auth.manager

import com.x8bit.bitwarden.data.auth.manager.model.ChangeMasterPasswordResult

/**
 * Manages the master password of the active account.
 */
interface MasterPasswordManager {
    /**
     * Changes the master password of the active account from [currentPassword] to [newPassword],
     * with [passwordHint] as its hint, or no hint if it is `null`. The user key stays the same, so
     * the items of the account do not change, but the account is logged out of every device: of
     * this one once the vault took the new master password, and of the others that share the
     * vault folder at their next sync.
     *
     * The vault of the account must be unlocked.
     */
    suspend fun changeMasterPassword(
        currentPassword: String,
        newPassword: String,
        passwordHint: String?,
    ): ChangeMasterPasswordResult
}
