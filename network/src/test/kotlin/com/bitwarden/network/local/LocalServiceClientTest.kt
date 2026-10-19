package com.bitwarden.network.local

import com.bitwarden.core.di.CoreModule
import com.bitwarden.network.BitwardenServiceClient
import com.bitwarden.network.bitwardenServiceClient
import com.bitwarden.network.interceptor.AuthTokenProvider
import com.bitwarden.network.model.AttachmentJsonResponse
import com.bitwarden.network.model.AuthTokenData
import com.bitwarden.network.model.BitwardenServiceClientConfig
import com.bitwarden.network.model.CreateCipherInOrganizationJsonRequest
import com.bitwarden.network.model.CreateCipherResponseJson
import com.bitwarden.network.model.FolderJsonRequest
import com.bitwarden.network.model.GetCipherResponse
import com.bitwarden.network.model.GetTokenResponseJson
import com.bitwarden.network.model.KdfJson
import com.bitwarden.network.model.KdfTypeJson
import com.bitwarden.network.model.MasterPasswordAuthenticationDataJson
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.bitwarden.network.model.PasswordHintResponseJson
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.network.model.UpdateCipherResponseJson
import com.bitwarden.network.model.UpdateFolderResponseJson
import com.bitwarden.network.model.UpdateKdfJsonRequest
import com.bitwarden.network.model.createMockAttachmentJsonRequest
import com.bitwarden.network.model.createMockCipherJsonRequest
import com.bitwarden.network.util.parseJwtTokenDataOrNull
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tests that the services the app talks to a Bitwarden server with are answered from the local
 * vault, all the way through the HTTP client that they are made with.
 */
class LocalServiceClientTest {
    private val json = CoreModule.providesJson(buildInfoManager = mockk(relaxed = true))
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC)
    private val root: File = Files.createTempDirectory("local-vault").toFile()
    private val fileSystem = FileLocalVaultFileSystem(root = root)

    /**
     * Stands in for the parts of the app that register and log in, which are made with a
     * service that reads the model of the device and so cannot be created in a unit test. It
     * writes as another device, so that what the [client] writes can be told apart.
     */
    private val server = LocalServer(
        fileSystem = fileSystem,
        clock = clock,
        deviceId = STAND_IN_DEVICE_ID,
    )

    private var authTokenData: AuthTokenData? = null

    private val client: BitwardenServiceClient = bitwardenServiceClient(
        config = BitwardenServiceClientConfig(
            clientData = BitwardenServiceClientConfig.ClientData(
                userAgent = "mockUserAgent",
                clientName = "mockClientName",
                clientVersion = "mockClientVersion",
            ),
            appIdProvider = mockk {
                every { uniqueAppId } returns DEVICE_ID
            },
            baseUrlsProvider = mockk(),
            authTokenProvider = object : AuthTokenProvider {
                override fun getAuthTokenDataOrNull(userId: String): AuthTokenData? = authTokenData

                override fun getAuthTokenDataOrNull(): AuthTokenData? = authTokenData
            },
            certificateProvider = mockk {
                every { chooseClientAlias(any(), any(), any()) } returns ""
                every { getCertificateChain(any()) } returns emptyArray()
                every { getPrivateKey(any()) } returns null
            },
            cookieProvider = mockk(),
            permissionProvider = mockk(),
            clock = clock,
            localVaultFileSystem = fileSystem,
        ),
        json = json,
    )

    @BeforeEach
    fun setup() {
        server.handle(
            request = LocalRequest(
                method = "POST",
                url = "https://$LOCAL_VAULT_HOST/identity/accounts/register/finish",
                headers = mapOf("Content-Type" to "application/json"),
                readBody = { REGISTER_JSON.toByteArray() },
            ),
        )
        val tokenResponse = logIn(passwordHash = PASSWORD_HASH)
        val accessToken = json
            .decodeFromString<GetTokenResponseJson.Success>(requireNotNull(tokenResponse.body))
            .accessToken
        authTokenData = AuthTokenData(
            userId = requireNotNull(parseJwtTokenDataOrNull(accessToken)).userId,
            accessToken = accessToken,
            expiresAtSec = clock.instant().epochSecond + 3600L,
        )
    }

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `sync should return the vault of the account that is logged in`() = runTest {
        val syncResponse = client.syncService.sync().getOrThrow()

        assertEquals(EMAIL, syncResponse.profile.email)
        assertEquals(authTokenData?.userId, syncResponse.profile.id)
        assertEquals(emptyList<SyncResponseJson.Cipher>(), syncResponse.ciphers)
        assertEquals(
            clock.millis(),
            client.syncService.getAccountRevisionDateMillis().getOrThrow(),
        )
    }

    @Test
    fun `sync should fail when no account is logged in`() = runTest {
        authTokenData = null

        assertTrue(client.syncService.sync().isFailure)
    }

    @Test
    fun `config should not report any feature flags`() = runTest {
        authTokenData = null

        val config = client.configService.getConfig().getOrThrow()

        assertEquals(emptyMap<String, Any>(), config.featureStates)
        assertNull(config.server)
    }

    @Test
    fun `ciphers should be kept in the local vault`() = runTest {
        val createResponse = client
            .ciphersService
            .createCipher(body = createCipherRequest(number = 1))
            .getOrThrow()
        val created = (createResponse as CreateCipherResponseJson.Success).cipher
        assertEquals("mockName-1", created.name)
        assertEquals(listOf(created), client.syncService.sync().getOrThrow().ciphers)

        val updateResponse = client
            .ciphersService
            .updateCipher(
                cipherId = created.id,
                body = createCipherRequest(
                    number = 2,
                    lastKnownRevisionDate = created.revisionDate,
                ),
            )
            .getOrThrow()
        val updated = (updateResponse as UpdateCipherResponseJson.Success).cipher
        assertEquals(created.id, updated.id)
        assertEquals("mockName-2", updated.name)

        client.ciphersService.softDeleteCipher(cipherId = created.id).getOrThrow()
        assertEquals(
            GetCipherResponse.Success(cipher = updated.copy(deletedDate = clock.instant())),
            client.ciphersService.getCipher(cipherId = created.id).getOrThrow(),
        )

        assertEquals(
            updated,
            client.ciphersService.restoreCipher(cipherId = created.id).getOrThrow(),
        )

        client.ciphersService.hardDeleteCipher(cipherId = created.id).getOrThrow()
        assertTrue(
            client.ciphersService.getCipher(cipherId = created.id).getOrThrow()
                is GetCipherResponse.NotFound,
        )
        assertEquals(
            emptyList<SyncResponseJson.Cipher>(),
            client.syncService.sync().getOrThrow().ciphers,
        )
    }

    @Test
    fun `cipher that is created without collections should be kept in the local vault`() =
        runTest {
            // This is the request that the app makes for every item that is added by hand.
            val createResponse = client
                .ciphersService
                .createCipherInOrganization(
                    body = CreateCipherInOrganizationJsonRequest(
                        cipher = createCipherRequest(number = 1),
                        collectionIds = emptyList(),
                    ),
                )
                .getOrThrow()
            val created = (createResponse as CreateCipherResponseJson.Success).cipher

            assertEquals("mockName-1", created.name)
            assertNull(created.organizationId)
            assertEquals(listOf(created), client.syncService.sync().getOrThrow().ciphers)
        }

    @Test
    fun `cipher that is out of date should not be updated`() = runTest {
        val createResponse = client
            .ciphersService
            .createCipher(body = createCipherRequest(number = 1))
            .getOrThrow()
        val created = (createResponse as CreateCipherResponseJson.Success).cipher

        val updateResponse = client
            .ciphersService
            .updateCipher(
                cipherId = created.id,
                body = createCipherRequest(
                    number = 2,
                    lastKnownRevisionDate = created.revisionDate.minusSeconds(60),
                ),
            )
            .getOrThrow()

        assertTrue(updateResponse is UpdateCipherResponseJson.Invalid)
        assertEquals(listOf(created), client.syncService.sync().getOrThrow().ciphers)
    }

    @Test
    fun `folders should be kept in the local vault`() = runTest {
        val folder = client
            .folderService
            .createFolder(body = FolderJsonRequest(name = "mockFolderName-1"))
            .getOrThrow()
        assertEquals("mockFolderName-1", folder.name)
        assertEquals(folder, client.folderService.getFolder(folderId = folder.id).getOrThrow())

        val updateResponse = client
            .folderService
            .updateFolder(
                folderId = folder.id,
                body = FolderJsonRequest(name = "mockFolderName-2"),
            )
            .getOrThrow()
        val updated = (updateResponse as UpdateFolderResponseJson.Success).folder
        assertEquals(folder.copy(name = "mockFolderName-2"), updated)
        assertEquals(listOf(updated), client.syncService.sync().getOrThrow().folders)

        client.folderService.deleteFolder(folderId = folder.id).getOrThrow()
        assertEquals(
            emptyList<SyncResponseJson.Folder>(),
            client.syncService.sync().getOrThrow().folders,
        )
    }

    @Test
    fun `changes should be written to the directory of the ID of the app`() = runTest {
        val folder = client
            .folderService
            .createFolder(body = FolderJsonRequest(name = "mockFolderName-1"))
            .getOrThrow()

        val devices = File(root, "${requireNotNull(authTokenData).userId}/devices")
        assertEquals(setOf(DEVICE_ID, STAND_IN_DEVICE_ID), devices.list().orEmpty().toSet())
        assertTrue(File(devices, "$DEVICE_ID/vault.json").readText().contains(folder.id))
    }

    @Test
    fun `attachment content should be downloaded from the local vault`() = runTest {
        val createResponse = client
            .ciphersService
            .createCipher(body = createCipherRequest(number = 1))
            .getOrThrow()
        val cipher = (createResponse as CreateCipherResponseJson.Success).cipher
        val createAttachmentResponse = client
            .ciphersService
            .createAttachment(
                cipherId = cipher.id,
                body = createMockAttachmentJsonRequest(number = 1, fileSize = "3"),
            )
            .getOrThrow()
        val attachmentResponse = createAttachmentResponse as AttachmentJsonResponse.Success
        // The app uploads the content with a request that is made with an Android class, which
        // is not there in a unit test.
        server.handle(
            request = LocalRequest(
                method = "PUT",
                url = attachmentResponse.url,
                writeBodyTo = { it.write(ATTACHMENT_CONTENT) },
            ),
        )

        val attachment = client
            .ciphersService
            .getCipherAttachment(
                cipherId = cipher.id,
                attachmentId = attachmentResponse.attachmentId,
            )
            .getOrThrow()
        val content = client
            .downloadService
            .getDataStream(url = requireNotNull(attachment.url))
            .getOrThrow()
            .use { it.bytes() }

        assertArrayEquals(ATTACHMENT_CONTENT, content)

        client
            .ciphersService
            .deleteCipherAttachment(
                cipherId = cipher.id,
                attachmentId = attachmentResponse.attachmentId,
            )
            .getOrThrow()
        assertTrue(
            client.downloadService.getDataStream(url = requireNotNull(attachment.url)).isFailure,
        )
    }

    @Test
    fun `password hint should be returned as an error message`() = runTest {
        authTokenData = null

        assertEquals(
            PasswordHintResponseJson.Error(errorMessage = "Your master password hint: mockHint"),
            client.accountsService.requestPasswordHint(email = EMAIL).getOrThrow(),
        )
    }

    @Test
    fun `KDF settings should be changed in the local vault without logging the account out`() =
        runTest {
            val securityStamp = client.syncService.sync().getOrThrow().profile.securityStamp

            client.accountsService.updateKdf(body = UPDATE_KDF_REQUEST).getOrThrow()

            val syncResponse = client.syncService.sync().getOrThrow()
            assertEquals(securityStamp, syncResponse.profile.securityStamp)
            assertEquals(NEW_USER_KEY, syncResponse.profile.key)
            assertEquals(
                MasterPasswordUnlockDataJson(
                    salt = EMAIL,
                    kdf = ARGON2_KDF,
                    masterKeyWrappedUserKey = NEW_USER_KEY,
                ),
                syncResponse.userDecryption?.masterPasswordUnlock,
            )
            // The master password is known by the hash that the new settings derive.
            assertEquals(400, logIn(passwordHash = PASSWORD_HASH).code)
            assertEquals(200, logIn(passwordHash = NEW_PASSWORD_HASH).code)
        }

    @Test
    fun `KDF settings should not be changed with the hash of another master password`() =
        runTest {
            val result = client.accountsService.updateKdf(
                body = UPDATE_KDF_REQUEST.copy(masterPasswordHash = "mockOtherPasswordHash"),
            )

            assertTrue(requireNotNull(result.exceptionOrNull()).isBadRequest())
            assertEquals(200, logIn(passwordHash = PASSWORD_HASH).code)
            assertEquals(400, logIn(passwordHash = NEW_PASSWORD_HASH).code)
        }

    /**
     * Logs in to the account with the [passwordHash] that the app derives from the master
     * password, as the app does when the user logs in.
     */
    private fun logIn(passwordHash: String): LocalResponse {
        val form = mapOf(
            "grant_type" to "password",
            "username" to EMAIL,
            "password" to passwordHash,
        )
        return server.handle(
            request = LocalRequest(
                method = "POST",
                url = "https://$LOCAL_VAULT_HOST/identity/connect/token",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                readBody = {
                    form
                        .entries
                        .joinToString(separator = "&") { (name, value) ->
                            "$name=${URLEncoder.encode(value, Charsets.UTF_8)}"
                        }
                        .toByteArray()
                },
            ),
        )
    }

    /**
     * A request for an item the way the app sends it for an item of the user: without an
     * organization, and encrypted for the account that is logged in.
     */
    private fun createCipherRequest(
        number: Int,
        lastKnownRevisionDate: Instant? = null,
    ) = createMockCipherJsonRequest(
        number = number,
        attachments = null,
        organizationId = null,
        folderId = null,
        lastKnownRevisionDate = lastKnownRevisionDate,
        archivedDate = null,
        encryptedFor = authTokenData?.userId,
    )
}

private const val EMAIL = "test@example.com"
private const val PASSWORD_HASH = "mockPasswordHash"
private const val NEW_PASSWORD_HASH = "mockNewPasswordHash"
private const val NEW_USER_KEY = "mockNewUserKey"
private const val DEVICE_ID = "11111111-2222-4333-8444-555555555555"
private const val STAND_IN_DEVICE_ID = "66666666-7777-4888-9999-000000000000"

private val ATTACHMENT_CONTENT = byteArrayOf(1, 2, 3)

private val ARGON2_KDF = KdfJson(
    kdfType = KdfTypeJson.ARGON2_ID,
    iterations = 3,
    memory = 64,
    parallelism = 4,
)

/**
 * The request that the app changes the KDF settings with, from the PBKDF2-SHA256 of the account
 * to Argon2id.
 */
private val UPDATE_KDF_REQUEST = UpdateKdfJsonRequest(
    authenticationData = MasterPasswordAuthenticationDataJson(
        kdf = ARGON2_KDF,
        masterPasswordAuthenticationHash = NEW_PASSWORD_HASH,
        salt = EMAIL,
    ),
    key = NEW_USER_KEY,
    masterPasswordHash = PASSWORD_HASH,
    newMasterPasswordHash = NEW_PASSWORD_HASH,
    unlockData = MasterPasswordUnlockDataJson(
        salt = EMAIL,
        kdf = ARGON2_KDF,
        masterKeyWrappedUserKey = NEW_USER_KEY,
    ),
)

private const val REGISTER_JSON = """
{
  "email": "$EMAIL",
  "emailVerificationToken": "mockToken",
  "masterPasswordHint": "mockHint",
  "userAsymmetricKeys": {
    "publicKey": "mockPublicKey",
    "encryptedPrivateKey": "mockPrivateKey"
  },
  "MasterPasswordAuthentication": {
    "Kdf": {
      "kdfType": 0,
      "iterations": 600000
    },
    "MasterPasswordAuthenticationHash": "$PASSWORD_HASH",
    "Salt": "$EMAIL"
  },
  "MasterPasswordUnlock": {
    "salt": "$EMAIL",
    "kdf": {
      "kdfType": 0,
      "iterations": 600000
    },
    "masterKeyWrappedUserKey": "mockUserKey"
  }
}
"""
