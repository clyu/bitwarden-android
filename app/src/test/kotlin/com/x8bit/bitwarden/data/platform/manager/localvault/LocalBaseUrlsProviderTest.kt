package com.x8bit.bitwarden.data.platform.manager.localvault

import com.bitwarden.network.interceptor.BaseUrlsProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalBaseUrlsProviderTest {
    private val delegate: BaseUrlsProvider = mockk {
        every { getBaseApiUrl() } returns "https://api.bitwarden.com"
        every { getBaseIdentityUrl() } returns "https://identity.bitwarden.com"
        every { getBaseEventsUrl() } returns "https://events.bitwarden.com"
        every { getBaseFillAssistUrl() } returns FILL_ASSIST_URL
    }

    private val provider = LocalBaseUrlsProvider(delegate = delegate)

    @Test
    fun `getBaseApiUrl should return a URL of the host that can never be reached`() {
        assertEquals("https://vault.local.invalid/api", provider.getBaseApiUrl())
    }

    @Test
    fun `getBaseIdentityUrl should return a URL of the host that can never be reached`() {
        assertEquals("https://vault.local.invalid/identity", provider.getBaseIdentityUrl())
    }

    @Test
    fun `getBaseEventsUrl should return a URL of the host that can never be reached`() {
        assertEquals("https://vault.local.invalid/events", provider.getBaseEventsUrl())
    }

    @Test
    fun `getBaseFillAssistUrl should return the URL of the delegate`() {
        assertEquals(FILL_ASSIST_URL, provider.getBaseFillAssistUrl())
    }
}

private const val FILL_ASSIST_URL: String = "https://fill-assist.example.com"
