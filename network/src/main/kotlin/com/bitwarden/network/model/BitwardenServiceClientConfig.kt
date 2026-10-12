package com.bitwarden.network.model

import com.bitwarden.network.BitwardenServiceClient
import com.bitwarden.network.interceptor.AuthTokenProvider
import com.bitwarden.network.interceptor.BaseUrlsProvider
import com.bitwarden.network.local.LocalVaultFileSystem
import com.bitwarden.network.provider.AppIdProvider
import com.bitwarden.network.provider.CookieProvider
import com.bitwarden.network.provider.PermissionProvider
import com.bitwarden.network.ssl.CertificateProvider
import java.time.Clock

/**
 * Models configuration for [BitwardenServiceClient].
 *
 * @property localVaultFileSystem Where the vault is kept on the device. When set, there is no
 * Bitwarden server: everything it would have been asked is answered from this vault instead.
 */
data class BitwardenServiceClientConfig(
    val clientData: ClientData,
    val appIdProvider: AppIdProvider,
    val baseUrlsProvider: BaseUrlsProvider,
    val authTokenProvider: AuthTokenProvider,
    val certificateProvider: CertificateProvider,
    val cookieProvider: CookieProvider,
    val permissionProvider: PermissionProvider,
    val clock: Clock,
    val enableHttpBodyLogging: Boolean = false,
    val localVaultFileSystem: LocalVaultFileSystem? = null,
) {
    /**
     * Models data about the client application.
     */
    data class ClientData(
        val userAgent: String,
        val clientName: String,
        val clientVersion: String,
    )
}
