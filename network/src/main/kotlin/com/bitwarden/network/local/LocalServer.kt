package com.bitwarden.network.local

import com.bitwarden.localvault.InternalException
import com.bitwarden.localvault.LocalVaultException
import com.bitwarden.localvault.LocalVaultServer
import com.bitwarden.localvault.VaultClientKind
import com.bitwarden.localvault.VaultFileSystem
import com.bitwarden.localvault.VaultLogLevel
import com.bitwarden.localvault.VaultPlatform
import com.bitwarden.localvault.VaultRequest
import com.bitwarden.localvault.VaultResponseBody
import com.bitwarden.localvault.internalErrorJson
import com.bitwarden.localvault.streamsRequestBody
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock

/**
 * The host of the URLs that the local server hands out for the content of attachments, and of
 * the URLs that the app has in place of those of a server. It is reserved, so it can never
 * resolve to a real server. The local vault has it as well, which the tests check.
 */
const val LOCAL_VAULT_HOST: String = "vault.local.invalid"

internal const val HTTP_OK: Int = 200
internal const val HTTP_BAD_REQUEST: Int = 400
internal const val HTTP_UNAUTHORIZED: Int = 401
internal const val HTTP_NOT_FOUND: Int = 404
internal const val HTTP_INTERNAL_ERROR: Int = 500

private const val JSON_MEDIA_TYPE: String = "application/json"

/**
 * A request to the [LocalServer], as the HTTP client of the app has it. What it asks for is
 * worked out by the local vault.
 *
 * @property method The HTTP method.
 * @property url The URL.
 * @property headers The headers, including the `Content-Type` of the body.
 * @property readBody Reads the body.
 * @property writeBodyTo Writes the body to a stream without holding all of it in memory.
 */
internal class LocalRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val readBody: () -> ByteArray = { ByteArray(size = 0) },
    val writeBodyTo: (OutputStream) -> Unit = { },
)

/**
 * A response from the [LocalServer].
 *
 * @property code The HTTP status code.
 * @property contentType The media type of the body, if there is one.
 * @property body The JSON body, if any.
 * @property stream The binary body, if any.
 */
internal class LocalResponse(
    val code: Int,
    val contentType: String? = null,
    val body: String? = null,
    val stream: InputStream? = null,
)

/**
 * Does what the Bitwarden server does for a single user, on top of a local vault: it registers
 * and authenticates accounts, and stores their encrypted items, folders and attachments.
 *
 * The work is done by the local vault in Rust (the submodule `local-vault-rs`), which the other
 * clients that share a vault folder use as well. This only hands it the requests, the vault
 * folder, the clock and the ID of this installation of the app, and streams the content of
 * attachments, which does not pass through it.
 *
 * The local vault writes the changes of the app to a directory of the vault folder named after
 * [deviceId], which no other device writes, and puts the vault together from the directories of
 * every device that shares the folder.
 */
internal class LocalServer(
    private val fileSystem: LocalVaultFileSystem,
    private val clock: Clock,
    private val deviceId: String,
) {
    /**
     * The local vault, which loads its library the first time that it is needed.
     */
    private val server: LocalVaultServer by lazy {
        LocalVaultServer(
            fileSystem = FileSystemCallback(fileSystem = fileSystem),
            platform = PlatformCallback(clock = clock),
            client = VaultClientKind.ANDROID,
            deviceId = deviceId,
        )
    }

    /**
     * The response for a call of the local vault that fails in a way that it does not answer.
     */
    private val internalError: LocalResponse by lazy {
        LocalResponse(
            code = HTTP_INTERNAL_ERROR,
            contentType = JSON_MEDIA_TYPE,
            body = internalErrorJson(),
        )
    }

    /**
     * Handles the given [request]. Requests are handled one at a time, as each of them reads the
     * whole vault and then writes what it changed in it, and the content of an attachment is
     * stored along with the request that it is sent with.
     */
    @Synchronized
    @Throws(IOException::class)
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun handle(request: LocalRequest): LocalResponse {
        val response = try {
            server.handle(request = request.toVaultRequest())
        } catch (e: LocalVaultException) {
            // The vault folder cannot be reached, which the app shows as a failed network call.
            throw IOException(e.reason(), e)
        } catch (e: InternalException) {
            Timber.e(e, "Local server error")
            return internalError
        } catch (e: RuntimeException) {
            // Nothing may escape from here: OkHttp rethrows what an interceptor throws on one of
            // its own threads, which would take the app down.
            Timber.e(e, "Local server error")
            return internalError
        }
        response.storeRequestBodyAt?.let { path ->
            fileSystem.openOutput(path = path).use(request.writeBodyTo)
        }
        val code = response.code.toInt()
        val contentType = response.contentType
        return when (val body = response.body) {
            is VaultResponseBody.Empty -> LocalResponse(code = code)
            is VaultResponseBody.Json -> LocalResponse(
                code = code,
                contentType = contentType,
                body = body.json,
            )

            is VaultResponseBody.File -> LocalResponse(
                code = code,
                contentType = contentType,
                stream = fileSystem.openInput(path = body.path)
                    ?: throw IOException("The content of the attachment is gone."),
            )
        }
    }

    /**
     * The request in the form that the local vault takes. The body of a request for the content
     * of an attachment is not read here: it is written to the vault folder as it comes in.
     */
    private fun LocalRequest.toVaultRequest(): VaultRequest =
        VaultRequest(
            method = method,
            url = url,
            headers = headers,
            body = if (streamsRequestBody(url = url)) ByteArray(size = 0) else readBody(),
        )
}

/**
 * The vault folder, as the local vault reaches it.
 */
internal class FileSystemCallback(
    private val fileSystem: LocalVaultFileSystem,
) : VaultFileSystem {
    override fun list(directory: List<String>): List<String> =
        withVaultExceptions { fileSystem.list(directory = directory) }

    override fun exists(path: List<String>): Boolean =
        withVaultExceptions { fileSystem.exists(path = path) }

    override fun read(path: List<String>): ByteArray? =
        withVaultExceptions { fileSystem.openInput(path = path)?.use { it.readBytes() } }

    override fun write(path: List<String>, content: ByteArray) {
        withVaultExceptions { fileSystem.openOutput(path = path).use { it.write(content) } }
    }

    override fun rename(path: List<String>, newName: String): Boolean =
        withVaultExceptions { fileSystem.rename(path = path, newName = newName) }

    override fun delete(path: List<String>): Boolean =
        withVaultExceptions { fileSystem.delete(path = path) }
}

/**
 * What the local vault needs of the app apart from the vault folder.
 */
private class PlatformCallback(
    private val clock: Clock,
) : VaultPlatform {
    override fun nowMillis(): Long = clock.millis()

    override fun log(level: VaultLogLevel, message: String) {
        when (level) {
            VaultLogLevel.WARNING -> Timber.w(message)
            VaultLogLevel.ERROR -> Timber.e(message)
        }
    }
}

/**
 * Runs the given [block] and reports a vault folder that cannot be reached the way the local
 * vault expects it to be reported.
 */
private inline fun <T> withVaultExceptions(block: () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        throw LocalVaultException.Io(reason = e.message ?: "The vault folder cannot be reached")
    }

/**
 * What failed, as the local vault says it.
 */
internal fun LocalVaultException.reason(): String =
    when (this) {
        is LocalVaultException.Io -> reason
    }
