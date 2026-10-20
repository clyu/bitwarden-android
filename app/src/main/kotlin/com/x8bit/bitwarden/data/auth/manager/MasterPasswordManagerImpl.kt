package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.UpdateKdfResponse
import com.bitwarden.core.data.manager.toast.ToastManager
import com.bitwarden.core.data.repository.error.MissingPropertyException
import com.bitwarden.core.data.util.flatMap
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.network.local.isBadRequest
import com.bitwarden.network.model.MasterPasswordAuthenticationDataJson
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.bitwarden.network.model.ResetPasswordRequestJson
import com.bitwarden.network.service.AccountsService
import com.bitwarden.ui.platform.resource.BitwardenString
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.util.toKdf
import com.x8bit.bitwarden.data.auth.datasource.sdk.util.toKdfRequestModel
import com.x8bit.bitwarden.data.auth.manager.model.ChangeMasterPasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.LogoutReason
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource

/**
 * Primary implementation of [MasterPasswordManager].
 */
class MasterPasswordManagerImpl(
    private val authDiskSource: AuthDiskSource,
    private val authSdkSource: AuthSdkSource,
    private val vaultSdkSource: VaultSdkSource,
    private val accountsService: AccountsService,
    private val userLogoutManager: UserLogoutManager,
    private val toastManager: ToastManager,
) : MasterPasswordManager {
    override suspend fun changeMasterPassword(
        currentPassword: String,
        newPassword: String,
        passwordHint: String?,
    ): ChangeMasterPasswordResult {
        val profile = authDiskSource.userState?.activeAccount?.profile
            ?: return ChangeMasterPasswordResult.Error(error = NoActiveUserException())
        val masterPasswordUnlock = profile.userDecryptionOptions?.masterPasswordUnlock
            ?: return ChangeMasterPasswordResult.Error(
                error = MissingPropertyException("Master Password Unlock"),
            )
        val userId = profile.userId
        // The settings that the account has, which the vault knows the current master password
        // by, and takes no others for the new one with. The SDK keeps those that the vault was
        // unlocked with, which are the ones from before once a sync took a change of them from
        // another device.
        val kdf = masterPasswordUnlock.kdf.toKdf()
        return authSdkSource
            .hashPassword(
                salt = masterPasswordUnlock.salt,
                password = currentPassword,
                kdf = kdf,
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
            .flatMap { currentPasswordHash ->
                // The SDK derives the new master key with the settings that it is given here,
                // where makeUpdatePassword, which the password reset of upstream uses, derives it
                // with those that the vault was unlocked with. The vault would then keep the user
                // key encrypted with a master key that the settings of the account do not derive,
                // and the account could not be unlocked or logged in to anymore.
                vaultSdkSource
                    .makeUpdateKdf(userId = userId, password = newPassword, kdf = kdf)
                    .flatMap { response ->
                        accountsService.resetPassword(
                            body = response.toResetPasswordRequestJson(
                                currentPasswordHash = currentPasswordHash,
                                passwordHint = passwordHint,
                            ),
                        )
                    }
            }
            .fold(
                onSuccess = {
                    toastManager.show(messageId = BitwardenString.updated_master_password)
                    // The vault gave the account a new security stamp, which logs it out of the
                    // other devices that share the vault folder at their next sync, as they unlock
                    // it with the old master password. It is logged out of this one right away,
                    // as upstream does once the master password is reset, which clears what this
                    // device keeps of the old one.
                    userLogoutManager.logout(userId = userId, reason = LogoutReason.PasswordReset)
                    ChangeMasterPasswordResult.Success
                },
                onFailure = { error ->
                    if (error.isBadRequest()) {
                        ChangeMasterPasswordResult.MasterPasswordRejected
                    } else {
                        ChangeMasterPasswordResult.Error(error = error)
                    }
                },
            )
    }
}

/**
 * Converts the [UpdateKdfResponse] of the SDK for the new master password to the request that
 * changes the master password, with the [currentPasswordHash] that the current master password is
 * known by until then, and the [passwordHint] of the new one.
 */
private fun UpdateKdfResponse.toResetPasswordRequestJson(
    currentPasswordHash: String,
    passwordHint: String?,
): ResetPasswordRequestJson {
    val authenticationData = masterPasswordAuthenticationData
    val unlockData = masterPasswordUnlockData
    return ResetPasswordRequestJson(
        currentPasswordHash = currentPasswordHash,
        passwordHint = passwordHint,
        authenticationData = MasterPasswordAuthenticationDataJson(
            kdf = authenticationData.kdf.toKdfRequestModel(),
            masterPasswordAuthenticationHash = authenticationData.masterPasswordAuthenticationHash,
            salt = authenticationData.salt,
        ),
        unlockData = MasterPasswordUnlockDataJson(
            kdf = unlockData.kdf.toKdfRequestModel(),
            masterKeyWrappedUserKey = unlockData.masterKeyWrappedUserKey,
            salt = unlockData.salt,
            containedKeyId = unlockData.containedKeyId,
        ),
    )
}
