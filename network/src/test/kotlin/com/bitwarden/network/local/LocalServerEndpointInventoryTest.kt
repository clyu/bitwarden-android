package com.bitwarden.network.local

import com.bitwarden.localvault.isAnswered
import com.bitwarden.network.api.DigitalAssetLinkApi
import com.bitwarden.network.api.FillAssistApi
import com.bitwarden.network.api.HaveIBeenPwnedApi
import com.bitwarden.network.api.SyncApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.HTTP
import retrofit2.http.OPTIONS
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import java.io.File
import java.lang.reflect.Method
import java.util.zip.ZipFile

/**
 * Tests that every request the app can make to a Bitwarden server is known here as one that the
 * local server answers or as one that it does not support, as the route table of the local vault
 * has it.
 *
 * When the app changes what it asks of a server, these tests fail instead of a feature quietly
 * no longer working. A request that is gone deserves a look as much as one that is new: it may
 * have moved into the SDK, which makes its requests without the local server seeing them.
 */
class LocalServerEndpointInventoryTest {
    @Test
    fun `every request that the app can make should be listed as answered or unsupported`() {
        assertEquals(
            emptyList<String>(),
            (apiEndpoints() - ANSWERED_ENDPOINTS - UNSUPPORTED_ENDPOINTS).sorted(),
            "Requests that the app can make but that are not listed",
        )
    }

    @Test
    fun `every listed request should be one that the app can make`() {
        assertEquals(
            emptyList<String>(),
            (ANSWERED_ENDPOINTS + UNSUPPORTED_ENDPOINTS - apiEndpoints()).sorted(),
            "Listed requests that the app no longer makes through the local server",
        )
    }

    @Test
    fun `answered requests should be answered by the local server`() {
        assertEquals(emptyList<String>(), ANSWERED_ENDPOINTS.filterNot { it.hasRoute() })
    }

    @Test
    fun `unsupported requests should be refused by the local server`() {
        assertEquals(emptyList<String>(), UNSUPPORTED_ENDPOINTS.filter { it.hasRoute() })
    }
}

/**
 * Returns `true` if the local vault has a route for this endpoint, with an ID that nothing has in
 * place of each parameter of its path. The ID is a UUID, as the local server only takes one for
 * the ID of an item or a folder.
 */
private fun String.hasRoute(): Boolean {
    val (method, path) = split(" ", limit = 2)
    return isAnswered(
        method = method,
        path = path.split("/").map { if (it == ANY_SEGMENT) UNKNOWN_ID else it },
    )
}

/**
 * Returns every request that the app can make to a Bitwarden server, as its method and its path
 * with a `*` in place of each parameter.
 */
private fun apiEndpoints(): Set<String> =
    apiInterfaces()
        .filterNot { it in APIS_OF_OTHER_SERVERS }
        .flatMap { it.declaredMethods.toList() }
        .mapNotNull { it.toEndpointOrNull() }
        .toSet()

/**
 * Returns the interfaces that the requests of the app are declared in, which is every interface
 * in the package of the [SyncApi].
 */
private fun apiInterfaces(): List<Class<*>> {
    val packageName = SyncApi::class.java.name.substringBeforeLast(delimiter = ".")
    val packagePath = packageName.replace(oldChar = '.', newChar = '/')
    // The classes are in a directory or in an archive, depending on how the tests are run.
    val location = requireNotNull(SyncApi::class.java.protectionDomain?.codeSource?.location) {
        "The classes of the app are not loaded from a location."
    }
    val classes = File(location.toURI())
    val classFileNames = if (classes.isDirectory) {
        File(classes, packagePath).list().orEmpty().toList()
    } else {
        ZipFile(classes).use { zip ->
            zip
                .entries()
                .asSequence()
                .map { it.name }
                .filter { it.startsWith("$packagePath/") }
                .map { it.removePrefix("$packagePath/") }
                .toList()
        }
    }
    return classFileNames
        .filter { it.endsWith(CLASS_SUFFIX) && '$' !in it && '/' !in it }
        .map { Class.forName("$packageName.${it.removeSuffix(CLASS_SUFFIX)}") }
        .filter { it.isInterface }
}

/**
 * Returns the request that this method makes, or `null` if it makes none or makes one to an
 * address that is only known by the time it is made, which is never one of the Bitwarden API.
 */
private fun Method.toEndpointOrNull(): String? {
    val (method, path) = annotations
        .firstNotNullOfOrNull { annotation ->
            when (annotation) {
                is DELETE -> "DELETE" to annotation.value
                is GET -> "GET" to annotation.value
                is HEAD -> "HEAD" to annotation.value
                is HTTP -> annotation.method to annotation.path
                is OPTIONS -> "OPTIONS" to annotation.value
                is PATCH -> "PATCH" to annotation.value
                is POST -> "POST" to annotation.value
                is PUT -> "PUT" to annotation.value
                else -> null
            }
        }
        ?: return null
    return path
        .trim('/')
        .takeIf { it.isNotEmpty() }
        ?.replace(regex = PATH_PARAMETER_REGEX, replacement = ANY_SEGMENT)
        ?.let { "$method $it" }
}

private const val ANY_SEGMENT: String = "*"
private const val UNKNOWN_ID: String = "00000000-0000-4000-8000-000000000000"
private const val CLASS_SUFFIX: String = ".class"
private val PATH_PARAMETER_REGEX: Regex = Regex("""\{[^}]+\}""")

/**
 * The interfaces of requests that are not made to a Bitwarden server, and that the local server
 * therefore has nothing to do with. They do leave the device.
 */
private val APIS_OF_OTHER_SERVERS: Set<Class<*>> = setOf(
    DigitalAssetLinkApi::class.java,
    FillAssistApi::class.java,
    HaveIBeenPwnedApi::class.java,
)

/**
 * The requests that the local server answers from the vault.
 */
private val ANSWERED_ENDPOINTS: Set<String> = setOf(
    "DELETE accounts",
    "DELETE ciphers/*",
    "DELETE ciphers/*/attachment/*",
    "DELETE folders/*",
    "GET accounts/revision-date",
    "GET auth-requests",
    "GET ciphers/*",
    "GET ciphers/*/attachment/*",
    "GET ciphers/has-unassigned-ciphers",
    "GET config",
    "GET devices",
    "GET devices/knowndevice",
    "GET folders/*",
    "GET sync",
    "POST accounts/kdf",
    "POST accounts/password",
    "POST accounts/password-hint",
    "POST accounts/prelogin",
    "POST accounts/register/finish",
    "POST accounts/register/send-verification-email",
    "POST accounts/register/verification-email-clicked",
    "POST ciphers",
    "POST ciphers/*/attachment/v2",
    "POST ciphers/create",
    "POST ciphers/import",
    "POST collect",
    "POST connect/token",
    "POST folders",
    "PUT ciphers/*",
    "PUT ciphers/*/archive",
    "PUT ciphers/*/delete",
    "PUT ciphers/*/restore",
    "PUT ciphers/*/unarchive",
    "PUT devices/identifier/*/token",
    "PUT folders/*",
)

/**
 * The requests that the local server does not support, as they are about what only a Bitwarden
 * server can offer: organizations, Sends, subscriptions, and logging in with anything but a
 * master password.
 */
private val UNSUPPORTED_ENDPOINTS: Set<String> = setOf(
    "DELETE sends/*",
    "GET account/billing/vnext/subscription",
    "GET auth-requests/*",
    "GET auth-requests/*/response",
    "GET organizations/*/auto-enroll-status",
    "GET organizations/*/keys",
    "GET plans/premium",
    "GET sends/*",
    "GET sso/prevalidate",
    "POST account/billing/vnext/portal-session",
    "POST account/billing/vnext/premium/checkout",
    "POST accounts/convert-to-key-connector",
    "POST accounts/keys",
    "POST accounts/request-otp",
    "POST accounts/resend-new-device-otp",
    "POST accounts/set-key-connector-key",
    "POST accounts/set-password",
    "POST accounts/verify-otp",
    "POST auth-requests",
    "POST auth-requests/admin-request",
    "POST ciphers/*/attachment/*",
    "POST ciphers/*/attachment/*/share",
    "POST organizations/*/leave",
    "POST organizations/domain/sso/verified",
    "POST sends",
    "POST sends/*/file/*",
    "POST sends/file/v2",
    "POST two-factor/send-email-login",
    "PUT accounts/update-temp-password",
    "PUT auth-requests/*",
    "PUT ciphers/*/collections_v2",
    "PUT ciphers/*/share",
    "PUT ciphers/share",
    "PUT devices/*/keys",
    "PUT organizations/*/users/*/reset-password-enrollment",
    "PUT organizations/*/users/revoke-self",
    "PUT sends/*",
    "PUT sends/*/remove-password",
)
