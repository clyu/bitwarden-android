package com.x8bit.bitwarden.data.platform.manager.localvault

import com.bitwarden.network.interceptor.BaseUrlsProvider
import com.bitwarden.network.local.LOCAL_VAULT_HOST

private const val LOCAL_VAULT_URL: String = "https://$LOCAL_VAULT_HOST"

/**
 * A [BaseUrlsProvider] for an app that has no Bitwarden server. Wherever the server would be, it
 * gives the address of a host that can never be reached, so that a request that is not answered
 * from the local vault fails instead of leaving the device. That is what becomes of the requests
 * that the SDK makes by itself, which never pass by anything that could answer them.
 *
 * Everything else is as the [delegate] has it.
 */
class LocalBaseUrlsProvider(
    delegate: BaseUrlsProvider,
) : BaseUrlsProvider by delegate {
    override fun getBaseApiUrl(): String = "$LOCAL_VAULT_URL/api"

    override fun getBaseIdentityUrl(): String = "$LOCAL_VAULT_URL/identity"

    override fun getBaseEventsUrl(): String = "$LOCAL_VAULT_URL/events"
}
