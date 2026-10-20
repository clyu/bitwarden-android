package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.MasterPasswordAuthenticationData
import com.bitwarden.core.MasterPasswordUnlockData
import com.bitwarden.core.UpdateKdfResponse
import com.bitwarden.core.data.manager.toast.ToastManager
import com.bitwarden.core.data.repository.error.MissingPropertyException
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.crypto.Kdf
import com.bitwarden.data.datasource.disk.model.EnvironmentUrlDataJson
import com.bitwarden.network.local.createMockBadRequestThrowable
import com.bitwarden.network.model.KdfJson
import com.bitwarden.network.model.KdfTypeJson
import com.bitwarden.network.model.MasterPasswordAuthenticationDataJson
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.bitwarden.network.model.ResetPasswordRequestJson
import com.bitwarden.network.model.UserDecryptionOptionsJson
import com.bitwarden.network.service.AccountsService
import com.bitwarden.ui.platform.resource.BitwardenString
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.manager.model.ChangeMasterPasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.LogoutReason
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class MasterPasswordManagerTest {

    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val authSdkSource: AuthSdkSource = mockk {
        coEvery {
            hashPassword(
                salt = EMAIL,
                password = PASSWORD,
                kdf = ACCOUNT_KDF,
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
        } returns PASSWORD_HASH.asSuccess()
    }
    private val vaultSdkSource: VaultSdkSource = mockk {
        coEvery {
            makeUpdateKdf(userId = USER_ID, password = NEW_PASSWORD, kdf = ACCOUNT_KDF)
        } returns UPDATE_KDF_RESPONSE.asSuccess()
    }
    private val accountsService: AccountsService = mockk {
        coEvery { resetPassword(body = any()) } returns Unit.asSuccess()
    }
    private val userLogoutManager: UserLogoutManager = mockk {
        every { logout(userId = any(), reason = any()) } just runs
    }
    private val toastManager: ToastManager = mockk {
        every { show(messageId = any(), duration = any()) } just runs
    }

    private val manager: MasterPasswordManager = MasterPasswordManagerImpl(
        authDiskSource = fakeAuthDiskSource,
        authSdkSource = authSdkSource,
        vaultSdkSource = vaultSdkSource,
        accountsService = accountsService,
        userLogoutManager = userLogoutManager,
        toastManager = toastManager,
    )

    @BeforeEach
    fun setup() {
        fakeAuthDiskSource.userState = USER_STATE
    }

    @Test
    fun `changeMasterPassword without an active account should return Error`() = runTest {
        fakeAuthDiskSource.userState = null

        val result = changeMasterPassword()

        assertTrue((result as ChangeMasterPasswordResult.Error).error is NoActiveUserException)
        coVerify(exactly = 0) { accountsService.resetPassword(body = any()) }
        verify(exactly = 0) { userLogoutManager.logout(userId = any(), reason = any()) }
    }

    @Test
    fun `changeMasterPassword without master password unlock data should return Error`() =
        runTest {
            val userState = USER_STATE.copy(
                accounts = mapOf(
                    USER_ID to ACCOUNT.copy(
                        profile = PROFILE.copy(
                            userDecryptionOptions = USER_DECRYPTION_OPTIONS.copy(
                                hasMasterPassword = false,
                                masterPasswordUnlock = null,
                            ),
                        ),
                    ),
                ),
            )
            fakeAuthDiskSource.userState = userState

            val result = changeMasterPassword()

            assertTrue(
                (result as ChangeMasterPasswordResult.Error).error is MissingPropertyException,
            )
            coVerify(exactly = 0) { accountsService.resetPassword(body = any()) }
            verify(exactly = 0) { userLogoutManager.logout(userId = any(), reason = any()) }
            assertEquals(userState, fakeAuthDiskSource.userState)
        }

    @Test
    fun `changeMasterPassword when the master password cannot be hashed should return Error`() =
        runTest {
            val error = Throwable("Fail!")
            coEvery {
                authSdkSource.hashPassword(
                    salt = EMAIL,
                    password = PASSWORD,
                    kdf = ACCOUNT_KDF,
                    purpose = HashPurpose.SERVER_AUTHORIZATION,
                )
            } returns error.asFailure()

            val result = changeMasterPassword()

            assertEquals(ChangeMasterPasswordResult.Error(error = error), result)
            coVerify(exactly = 0) { accountsService.resetPassword(body = any()) }
            verify(exactly = 0) { userLogoutManager.logout(userId = any(), reason = any()) }
        }

    @Test
    fun `changeMasterPassword when the SDK fails should return Error and send nothing`() =
        runTest {
            val error = Throwable("Fail!")
            coEvery {
                vaultSdkSource.makeUpdateKdf(
                    userId = USER_ID,
                    password = NEW_PASSWORD,
                    kdf = ACCOUNT_KDF,
                )
            } returns error.asFailure()

            val result = changeMasterPassword()

            assertEquals(ChangeMasterPasswordResult.Error(error = error), result)
            coVerify(exactly = 0) { accountsService.resetPassword(body = any()) }
            verify(exactly = 0) { userLogoutManager.logout(userId = any(), reason = any()) }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `changeMasterPassword should derive the new master key with the settings of the account`() =
        runTest {
            val result = changeMasterPassword()

            assertEquals(ChangeMasterPasswordResult.Success, result)
            // The current master password is proven with the hash of the settings of the
            // account, and the new master key is derived with them as well: not with the
            // settings that the SDK was unlocked with, which another device may have changed
            // since.
            coVerify(exactly = 1) {
                vaultSdkSource.makeUpdateKdf(
                    userId = USER_ID,
                    password = NEW_PASSWORD,
                    kdf = ACCOUNT_KDF,
                )
                accountsService.resetPassword(body = RESET_PASSWORD_REQUEST)
            }
        }

    @Test
    fun `changeMasterPassword without a hint should send no hint`() = runTest {
        val result = manager.changeMasterPassword(
            currentPassword = PASSWORD,
            newPassword = NEW_PASSWORD,
            passwordHint = null,
        )

        assertEquals(ChangeMasterPasswordResult.Success, result)
        coVerify(exactly = 1) {
            accountsService.resetPassword(
                body = RESET_PASSWORD_REQUEST.copy(passwordHint = null),
            )
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `changeMasterPassword should log the account out once the vault took the new master password`() =
        runTest {
            val result = changeMasterPassword()

            assertEquals(ChangeMasterPasswordResult.Success, result)
            verifyOrder {
                toastManager.show(messageId = BitwardenString.updated_master_password)
                userLogoutManager.logout(userId = USER_ID, reason = LogoutReason.PasswordReset)
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `changeMasterPassword when the vault refuses the master password should return MasterPasswordRejected`() =
        runTest {
            coEvery {
                accountsService.resetPassword(body = any())
            } returns createMockBadRequestThrowable().asFailure()

            val result = changeMasterPassword()

            assertEquals(ChangeMasterPasswordResult.MasterPasswordRejected, result)
            verify(exactly = 0) {
                toastManager.show(messageId = any(), duration = any())
                userLogoutManager.logout(userId = any(), reason = any())
            }
            assertEquals(USER_STATE, fakeAuthDiskSource.userState)
        }

    @Test
    fun `changeMasterPassword when the request fails otherwise should return Error`() = runTest {
        val error = Throwable("Fail!")
        coEvery { accountsService.resetPassword(body = any()) } returns error.asFailure()

        val result = changeMasterPassword()

        assertEquals(ChangeMasterPasswordResult.Error(error = error), result)
        verify(exactly = 0) {
            toastManager.show(messageId = any(), duration = any())
            userLogoutManager.logout(userId = any(), reason = any())
        }
        assertEquals(USER_STATE, fakeAuthDiskSource.userState)
    }

    private suspend fun changeMasterPassword(): ChangeMasterPasswordResult =
        manager.changeMasterPassword(
            currentPassword = PASSWORD,
            newPassword = NEW_PASSWORD,
            passwordHint = PASSWORD_HINT,
        )
}

private const val CONTAINED_KEY_ID = "mockContainedKeyId"
private const val EMAIL = "test@bitwarden.com"
private const val NEW_PASSWORD = "newPassword"
private const val NEW_PASSWORD_HASH = "mockNewPasswordHash"
private const val NEW_USER_KEY = "mockNewUserKey"
private const val PASSWORD = "password"
private const val PASSWORD_HASH = "mockPasswordHash"
private const val PASSWORD_HINT = "mockPasswordHint"
private const val STALE_PASSWORD_HASH = "mockStalePasswordHash"
private const val USER_ID = "2a135b23-e1fb-42c9-bec3-573857bc8181"

/**
 * The settings of the account, which another device changed them to after the vault of this one
 * was unlocked with PBKDF2-SHA256.
 */
private val ACCOUNT_KDF = Kdf.Argon2id(iterations = 3u, memory = 64u, parallelism = 4u)
private val ACCOUNT_KDF_JSON = KdfJson(
    kdfType = KdfTypeJson.ARGON2_ID,
    iterations = 3,
    memory = 64,
    parallelism = 4,
)

private val USER_DECRYPTION_OPTIONS = UserDecryptionOptionsJson(
    hasMasterPassword = true,
    keyConnectorUserDecryptionOptions = null,
    trustedDeviceUserDecryptionOptions = null,
    masterPasswordUnlock = MasterPasswordUnlockDataJson(
        salt = EMAIL,
        kdf = ACCOUNT_KDF_JSON,
        masterKeyWrappedUserKey = "mockUserKey",
    ),
)

private val PROFILE = AccountJson.Profile(
    userId = USER_ID,
    email = EMAIL,
    isEmailVerified = true,
    name = "Bitwarden Tester",
    hasPremiumPersonally = false,
    hasPremiumFromOrganization = null,
    stamp = null,
    organizationId = null,
    avatarColorHex = null,
    forcePasswordResetReason = null,
    kdfType = KdfTypeJson.ARGON2_ID,
    kdfIterations = 3,
    kdfMemory = 64,
    kdfParallelism = 4,
    userDecryptionOptions = USER_DECRYPTION_OPTIONS,
    isTwoFactorEnabled = false,
    creationDate = Instant.parse("2024-09-13T01:00:00.00Z"),
)

private val ACCOUNT = AccountJson(
    profile = PROFILE,
    settings = AccountJson.Settings(
        environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US,
    ),
)

private val USER_STATE = UserStateJson(
    activeUserId = USER_ID,
    accounts = mapOf(USER_ID to ACCOUNT),
)

private val UPDATE_KDF_RESPONSE = UpdateKdfResponse(
    masterPasswordAuthenticationData = MasterPasswordAuthenticationData(
        kdf = ACCOUNT_KDF,
        salt = EMAIL,
        masterPasswordAuthenticationHash = NEW_PASSWORD_HASH,
    ),
    masterPasswordUnlockData = MasterPasswordUnlockData(
        kdf = ACCOUNT_KDF,
        masterKeyWrappedUserKey = NEW_USER_KEY,
        salt = EMAIL,
        containedKeyId = CONTAINED_KEY_ID,
    ),
    // The SDK derives it with the settings that the vault was unlocked with.
    oldMasterPasswordAuthenticationData = MasterPasswordAuthenticationData(
        kdf = Kdf.Pbkdf2(iterations = 600_000u),
        salt = EMAIL,
        masterPasswordAuthenticationHash = STALE_PASSWORD_HASH,
    ),
)

/**
 * The request that changes the master password to [NEW_PASSWORD], with the settings of the
 * account.
 */
private val RESET_PASSWORD_REQUEST = ResetPasswordRequestJson(
    currentPasswordHash = PASSWORD_HASH,
    passwordHint = PASSWORD_HINT,
    authenticationData = MasterPasswordAuthenticationDataJson(
        kdf = ACCOUNT_KDF_JSON,
        masterPasswordAuthenticationHash = NEW_PASSWORD_HASH,
        salt = EMAIL,
    ),
    unlockData = MasterPasswordUnlockDataJson(
        salt = EMAIL,
        kdf = ACCOUNT_KDF_JSON,
        masterKeyWrappedUserKey = NEW_USER_KEY,
        containedKeyId = CONTAINED_KEY_ID,
    ),
)
