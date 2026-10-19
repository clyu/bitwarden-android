package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.MasterPasswordAuthenticationData
import com.bitwarden.core.MasterPasswordUnlockData
import com.bitwarden.core.UpdateKdfResponse
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
import com.bitwarden.network.model.UpdateKdfJsonRequest
import com.bitwarden.network.model.UserDecryptionOptionsJson
import com.bitwarden.network.service.AccountsService
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.manager.model.ChangeKdfResult
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class KdfSettingsManagerTest {

    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val authSdkSource: AuthSdkSource = mockk {
        coEvery {
            hashPassword(
                salt = EMAIL,
                password = PASSWORD,
                kdf = CURRENT_KDF,
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
        } returns PASSWORD_HASH.asSuccess()
    }
    private val vaultSdkSource: VaultSdkSource = mockk {
        coEvery {
            makeUpdateKdf(userId = USER_ID, password = PASSWORD, kdf = NEW_KDF)
        } returns UPDATE_KDF_RESPONSE.asSuccess()
    }
    private val accountsService: AccountsService = mockk {
        coEvery { updateKdf(body = any()) } returns Unit.asSuccess()
    }

    private val manager: KdfSettingsManager = KdfSettingsManagerImpl(
        authDiskSource = fakeAuthDiskSource,
        authSdkSource = authSdkSource,
        vaultSdkSource = vaultSdkSource,
        accountsService = accountsService,
    )

    @BeforeEach
    fun setup() {
        fakeAuthDiskSource.userState = USER_STATE
        fakeAuthDiskSource.storeMasterPasswordHash(
            userId = USER_ID,
            passwordHash = LOCAL_PASSWORD_HASH,
        )
    }

    @Test
    fun `activeAccountKdf should be null without an active account`() {
        fakeAuthDiskSource.userState = null

        assertNull(manager.activeAccountKdf)
    }

    @Test
    fun `activeAccountKdf should be null when the active account has no master password`() {
        fakeAuthDiskSource.userState = USER_STATE.withUserDecryptionOptions(
            userDecryptionOptions = USER_DECRYPTION_OPTIONS.copy(
                hasMasterPassword = false,
                masterPasswordUnlock = null,
            ),
        )

        assertNull(manager.activeAccountKdf)
    }

    @Test
    fun `activeAccountKdf should be the settings that the master key is derived with`() {
        assertEquals(CURRENT_KDF, manager.activeAccountKdf)

        fakeAuthDiskSource.userState = USER_STATE.withUserDecryptionOptions(
            userDecryptionOptions = USER_DECRYPTION_OPTIONS.copy(
                masterPasswordUnlock = MASTER_PASSWORD_UNLOCK.copy(kdf = NEW_KDF_JSON),
            ),
        )

        assertEquals(NEW_KDF, manager.activeAccountKdf)
    }

    @Test
    fun `changeKdf with settings that are not allowed should return Error and change nothing`() =
        runTest {
            val result = manager.changeKdf(
                masterPassword = PASSWORD,
                kdf = Kdf.Pbkdf2(iterations = 5_000u),
            )

            assertTrue((result as ChangeKdfResult.Error).error is IllegalArgumentException)
            coVerify(exactly = 0) {
                vaultSdkSource.makeUpdateKdf(userId = any(), password = any(), kdf = any())
                accountsService.updateKdf(body = any())
            }
            assertEquals(USER_STATE, fakeAuthDiskSource.userState)
        }

    @Test
    fun `changeKdf without an active account should return Error`() = runTest {
        fakeAuthDiskSource.userState = null

        val result = manager.changeKdf(masterPassword = PASSWORD, kdf = NEW_KDF)

        assertTrue((result as ChangeKdfResult.Error).error is NoActiveUserException)
        coVerify(exactly = 0) { accountsService.updateKdf(body = any()) }
    }

    @Test
    fun `changeKdf without master password unlock data should return Error`() = runTest {
        val userState = USER_STATE.withUserDecryptionOptions(userDecryptionOptions = null)
        fakeAuthDiskSource.userState = userState

        val result = manager.changeKdf(masterPassword = PASSWORD, kdf = NEW_KDF)

        assertTrue((result as ChangeKdfResult.Error).error is MissingPropertyException)
        coVerify(exactly = 0) { accountsService.updateKdf(body = any()) }
        assertEquals(userState, fakeAuthDiskSource.userState)
    }

    @Test
    fun `changeKdf when the master password cannot be hashed should return Error`() = runTest {
        val error = Throwable("Fail!")
        coEvery {
            authSdkSource.hashPassword(
                salt = EMAIL,
                password = PASSWORD,
                kdf = CURRENT_KDF,
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
        } returns error.asFailure()

        val result = manager.changeKdf(masterPassword = PASSWORD, kdf = NEW_KDF)

        assertEquals(ChangeKdfResult.Error(error = error), result)
        coVerify(exactly = 0) { accountsService.updateKdf(body = any()) }
        assertEquals(USER_STATE, fakeAuthDiskSource.userState)
    }

    @Test
    fun `changeKdf when the SDK fails should return Error and send nothing`() = runTest {
        val error = Throwable("Fail!")
        coEvery {
            vaultSdkSource.makeUpdateKdf(userId = USER_ID, password = PASSWORD, kdf = NEW_KDF)
        } returns error.asFailure()

        val result = manager.changeKdf(masterPassword = PASSWORD, kdf = NEW_KDF)

        assertEquals(ChangeKdfResult.Error(error = error), result)
        coVerify(exactly = 0) { accountsService.updateKdf(body = any()) }
        assertEquals(USER_STATE, fakeAuthDiskSource.userState)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `changeKdf should prove the master password with the hash of the settings of the account`() =
        runTest {
            val result = manager.changeKdf(masterPassword = PASSWORD, kdf = NEW_KDF)

            assertEquals(ChangeKdfResult.Success, result)
            // Not the hash of the settings that the SDK was unlocked with, which another device
            // may have changed since.
            coVerify(exactly = 1) {
                accountsService.updateKdf(
                    body = UpdateKdfJsonRequest(
                        authenticationData = MasterPasswordAuthenticationDataJson(
                            kdf = NEW_KDF_JSON,
                            masterPasswordAuthenticationHash = NEW_PASSWORD_HASH,
                            salt = EMAIL,
                        ),
                        key = NEW_USER_KEY,
                        masterPasswordHash = PASSWORD_HASH,
                        newMasterPasswordHash = NEW_PASSWORD_HASH,
                        unlockData = MasterPasswordUnlockDataJson(
                            salt = EMAIL,
                            kdf = NEW_KDF_JSON,
                            masterKeyWrappedUserKey = NEW_USER_KEY,
                            containedKeyId = CONTAINED_KEY_ID,
                        ),
                    ),
                )
            }
        }

    @Test
    fun `changeKdf should keep the new settings once the vault took them`() = runTest {
        val result = manager.changeKdf(masterPassword = PASSWORD, kdf = NEW_KDF)

        assertEquals(ChangeKdfResult.Success, result)
        assertEquals(
            USER_STATE.copy(
                accounts = mapOf(
                    USER_ID to ACCOUNT.copy(
                        profile = PROFILE.copy(
                            kdfType = KdfTypeJson.ARGON2_ID,
                            kdfIterations = 3,
                            kdfMemory = 64,
                            kdfParallelism = 4,
                            userDecryptionOptions = USER_DECRYPTION_OPTIONS.copy(
                                masterPasswordUnlock = MasterPasswordUnlockDataJson(
                                    salt = EMAIL,
                                    kdf = NEW_KDF_JSON,
                                    masterKeyWrappedUserKey = NEW_USER_KEY,
                                    containedKeyId = CONTAINED_KEY_ID,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            fakeAuthDiskSource.userState,
        )
        // The vault stays unlocked with the settings from before until it is locked, and so does
        // the hash that the master password is checked against.
        fakeAuthDiskSource.assertMasterPasswordHash(
            userId = USER_ID,
            passwordHash = LOCAL_PASSWORD_HASH,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `changeKdf when the vault refuses the master password should return MasterPasswordRejected and change nothing`() =
        runTest {
            coEvery {
                accountsService.updateKdf(body = any())
            } returns createMockBadRequestThrowable().asFailure()

            val result = manager.changeKdf(masterPassword = PASSWORD, kdf = NEW_KDF)

            assertEquals(ChangeKdfResult.MasterPasswordRejected, result)
            assertEquals(USER_STATE, fakeAuthDiskSource.userState)
        }

    @Test
    fun `changeKdf when the request fails otherwise should return Error and change nothing`() =
        runTest {
            val error = Throwable("Fail!")
            coEvery { accountsService.updateKdf(body = any()) } returns error.asFailure()

            val result = manager.changeKdf(masterPassword = PASSWORD, kdf = NEW_KDF)

            assertEquals(ChangeKdfResult.Error(error = error), result)
            assertEquals(USER_STATE, fakeAuthDiskSource.userState)
        }
}

private fun UserStateJson.withUserDecryptionOptions(
    userDecryptionOptions: UserDecryptionOptionsJson?,
): UserStateJson =
    copy(
        accounts = mapOf(
            USER_ID to ACCOUNT.copy(
                profile = PROFILE.copy(userDecryptionOptions = userDecryptionOptions),
            ),
        ),
    )

private const val CONTAINED_KEY_ID = "mockContainedKeyId"
private const val EMAIL = "test@bitwarden.com"
private const val LOCAL_PASSWORD_HASH = "mockLocalPasswordHash"
private const val NEW_PASSWORD_HASH = "mockNewPasswordHash"
private const val NEW_USER_KEY = "mockNewUserKey"
private const val PASSWORD = "password"
private const val PASSWORD_HASH = "mockPasswordHash"
private const val STALE_PASSWORD_HASH = "mockStalePasswordHash"
private const val USER_ID = "2a135b23-e1fb-42c9-bec3-573857bc8181"

private val CURRENT_KDF = Kdf.Pbkdf2(iterations = 650_000u)
private val NEW_KDF = Kdf.Argon2id(iterations = 3u, memory = 64u, parallelism = 4u)
private val NEW_KDF_JSON = KdfJson(
    kdfType = KdfTypeJson.ARGON2_ID,
    iterations = 3,
    memory = 64,
    parallelism = 4,
)

private val MASTER_PASSWORD_UNLOCK = MasterPasswordUnlockDataJson(
    salt = EMAIL,
    kdf = KdfJson(
        kdfType = KdfTypeJson.PBKDF2_SHA256,
        iterations = 650_000,
        memory = null,
        parallelism = null,
    ),
    masterKeyWrappedUserKey = "mockUserKey",
)

private val USER_DECRYPTION_OPTIONS = UserDecryptionOptionsJson(
    hasMasterPassword = true,
    keyConnectorUserDecryptionOptions = null,
    trustedDeviceUserDecryptionOptions = null,
    masterPasswordUnlock = MASTER_PASSWORD_UNLOCK,
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
    kdfType = KdfTypeJson.PBKDF2_SHA256,
    kdfIterations = 650_000,
    kdfMemory = null,
    kdfParallelism = null,
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
        kdf = NEW_KDF,
        salt = EMAIL,
        masterPasswordAuthenticationHash = NEW_PASSWORD_HASH,
    ),
    masterPasswordUnlockData = MasterPasswordUnlockData(
        kdf = NEW_KDF,
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
