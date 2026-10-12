package com.bitwarden.network.local

import com.bitwarden.localvault.localVaultHost
import com.bitwarden.network.interceptor.FakeInterceptorChain
import okhttp3.FormBody
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LocalServerInterceptorTest {
    private val root: File = Files.createTempDirectory("local-vault").toFile()
    private val server = LocalServer(
        fileSystem = FileLocalVaultFileSystem(root = root),
        clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC),
    )

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `the host of the local vault should be the one that the local server hands out`() {
        assertEquals(localVaultHost(), LOCAL_VAULT_HOST)
    }

    @Test
    fun `intercept without a host should answer every request from the local server`() {
        val interceptor = LocalServerInterceptor(localServer = server)
        val request = Request.Builder().url("https://api.bitwarden.com/config").build()

        val response = interceptor.intercept(
            chain = FakeInterceptorChain(
                request = request,
                responseProvider = { error("The request should not go out") },
            ),
        )

        assertEquals(HTTP_OK, response.code)
        assertEquals(request, response.request)
        assertEquals("application/json; charset=utf-8", response.body.contentType().toString())
        assertEquals(true, response.body.string().contains("\"featureStates\""))
    }

    @Test
    fun `intercept should hand over a form with the media type of its body`() {
        val interceptor = LocalServerInterceptor(localServer = server)
        val form = FormBody
            .Builder()
            .add(name = "grant_type", value = "password")
            .add(name = "username", value = "nobody@example.com")
            .add(name = "password", value = "mockPasswordHash")
            .build()
        val request = Request
            .Builder()
            .url("https://api.bitwarden.com/connect/token")
            .post(form)
            .build()

        val response = interceptor.intercept(
            chain = FakeInterceptorChain(
                request = request,
                responseProvider = { error("The request should not go out") },
            ),
        )

        // Without the form, the local server would not know that it is asked for a password.
        assertEquals(HTTP_BAD_REQUEST, response.code)
        assertEquals(true, response.body.string().contains("Username or password is incorrect"))
    }

    @Test
    fun `intercept with a host should answer requests for that host from the local server`() {
        val interceptor = LocalServerInterceptor(localServer = server, host = LOCAL_VAULT_HOST)
        val request = Request
            .Builder()
            .url("https://$LOCAL_VAULT_HOST/attachments/mockUserId/mockCipherId/mockId")
            .build()

        val response = interceptor.intercept(
            chain = FakeInterceptorChain(
                request = request,
                responseProvider = { error("The request should not go out") },
            ),
        )

        assertEquals(HTTP_NOT_FOUND, response.code)
    }

    @Test
    fun `intercept with a host should let requests for other hosts go out`() {
        val interceptor = LocalServerInterceptor(localServer = server, host = LOCAL_VAULT_HOST)
        val request = Request.Builder().url("https://api.pwnedpasswords.com/range/abcde").build()
        var proceededRequest: Request? = null

        interceptor.intercept(
            chain = FakeInterceptorChain(
                request = request,
                responseProvider = {
                    proceededRequest = it
                    FakeInterceptorChain.DEFAULT_RESPONSE_PROVIDER(it)
                },
            ),
        )

        assertEquals(request, proceededRequest)
    }
}
