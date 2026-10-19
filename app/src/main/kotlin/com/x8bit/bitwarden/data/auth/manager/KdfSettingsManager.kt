package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.crypto.Kdf
import com.x8bit.bitwarden.data.auth.manager.model.ChangeKdfResult

/**
 * Manages how the master key of the active account is derived from its master password: the
 * key derivation function (KDF) and what deriving it takes.
 */
interface KdfSettingsManager {
    /**
     * The settings that the master key of the active account is derived with, or `null` if there
     * is no active account or it has no master password.
     */
    val activeAccountKdf: Kdf?

    /**
     * Derives the master key of the active account with [kdf] from now on, once the vault has
     * taken [masterPassword] as the master password of the account. The user key stays the same,
     * so the items of the account do not change, and no device is logged out.
     *
     * The vault of the account must be unlocked.
     */
    suspend fun changeKdf(masterPassword: String, kdf: Kdf): ChangeKdfResult
}
