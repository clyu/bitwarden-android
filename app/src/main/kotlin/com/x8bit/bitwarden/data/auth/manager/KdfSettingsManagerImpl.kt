package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.UpdateKdfResponse
import com.bitwarden.core.data.repository.error.MissingPropertyException
import com.bitwarden.core.data.util.flatMap
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.crypto.Kdf
import com.bitwarden.network.local.isBadRequest
import com.bitwarden.network.model.MasterPasswordAuthenticationDataJson
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.bitwarden.network.model.UpdateKdfJsonRequest
import com.bitwarden.network.service.AccountsService
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.util.toKdf
import com.x8bit.bitwarden.data.auth.datasource.sdk.util.toKdfRequestModel
import com.x8bit.bitwarden.data.auth.manager.model.ChangeKdfResult
import com.x8bit.bitwarden.data.auth.repository.util.updateKdf
import com.x8bit.bitwarden.data.auth.repository.util.updateMasterPasswordUnlock
import com.x8bit.bitwarden.data.auth.util.isAllowedSetting
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource

/**
 * Primary implementation of [KdfSettingsManager].
 */
class KdfSettingsManagerImpl(
    private val authDiskSource: AuthDiskSource,
    private val authSdkSource: AuthSdkSource,
    private val vaultSdkSource: VaultSdkSource,
    private val accountsService: AccountsService,
) : KdfSettingsManager {
    override val activeAccountKdf: Kdf?
        get() = authDiskSource
            .userState
            ?.activeAccount
            ?.profile
            ?.userDecryptionOptions
            ?.masterPasswordUnlock
            ?.kdf
            ?.toKdf()

    override suspend fun changeKdf(masterPassword: String, kdf: Kdf): ChangeKdfResult {
        // The vault takes any settings, and a master key that a client cannot derive with them
        // would leave the account impossible to unlock or to log in to.
        if (!kdf.isAllowedSetting()) {
            return ChangeKdfResult.Error(
                error = IllegalArgumentException("The KDF settings are not allowed."),
            )
        }
        val profile = authDiskSource.userState?.activeAccount?.profile
            ?: return ChangeKdfResult.Error(error = NoActiveUserException())
        val masterPasswordUnlock = profile.userDecryptionOptions?.masterPasswordUnlock
            ?: return ChangeKdfResult.Error(
                error = MissingPropertyException("Master Password Unlock"),
            )
        val userId = profile.userId
        // The master password is proven to the vault with the hash of the settings that the
        // account has now. The SDK would derive it with the settings that the vault was unlocked
        // with, which are the ones from before once a sync took a change from another device.
        return authSdkSource
            .hashPassword(
                salt = masterPasswordUnlock.salt,
                password = masterPassword,
                kdf = masterPasswordUnlock.kdf.toKdf(),
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
            .flatMap { masterPasswordHash ->
                vaultSdkSource
                    .makeUpdateKdf(userId = userId, password = masterPassword, kdf = kdf)
                    .flatMap { response ->
                        accountsService
                            .updateKdf(
                                body = response.toUpdateKdfJsonRequest(
                                    masterPasswordHash = masterPasswordHash,
                                ),
                            )
                            .map { response }
                    }
            }
            .fold(
                onSuccess = { response ->
                    val unlockData = response.masterPasswordUnlockData
                    authDiskSource.userState = authDiskSource
                        .userState
                        ?.updateKdf(userId = userId, kdf = unlockData.kdf.toKdfRequestModel())
                        ?.updateMasterPasswordUnlock(
                            userId = userId,
                            masterPasswordUnlock = unlockData,
                        )
                    ChangeKdfResult.Success
                },
                onFailure = { error ->
                    if (error.isBadRequest()) {
                        ChangeKdfResult.MasterPasswordRejected
                    } else {
                        ChangeKdfResult.Error(error = error)
                    }
                },
            )
    }
}

/**
 * Converts the [UpdateKdfResponse] of the SDK to the request that changes the settings, with the
 * [masterPasswordHash] that the master password is known by until then.
 */
private fun UpdateKdfResponse.toUpdateKdfJsonRequest(
    masterPasswordHash: String,
): UpdateKdfJsonRequest {
    val authenticationData = masterPasswordAuthenticationData
    val unlockData = masterPasswordUnlockData
    return UpdateKdfJsonRequest(
        authenticationData = MasterPasswordAuthenticationDataJson(
            kdf = authenticationData.kdf.toKdfRequestModel(),
            masterPasswordAuthenticationHash = authenticationData.masterPasswordAuthenticationHash,
            salt = authenticationData.salt,
        ),
        key = unlockData.masterKeyWrappedUserKey,
        masterPasswordHash = masterPasswordHash,
        newMasterPasswordHash = authenticationData.masterPasswordAuthenticationHash,
        unlockData = MasterPasswordUnlockDataJson(
            kdf = unlockData.kdf.toKdfRequestModel(),
            masterKeyWrappedUserKey = unlockData.masterKeyWrappedUserKey,
            salt = unlockData.salt,
            containedKeyId = unlockData.containedKeyId,
        ),
    )
}
