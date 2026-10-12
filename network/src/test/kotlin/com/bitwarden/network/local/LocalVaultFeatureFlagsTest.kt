package com.bitwarden.network.local

import com.bitwarden.core.data.manager.model.FlagKey
import com.bitwarden.localvault.VaultClientKind
import com.bitwarden.localvault.flagsThatMustBeOff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The local server reports no feature flags, so every flag has the value that the app gives it
 * by default. These tests fail when an update from upstream changes what that means for the
 * local vault.
 */
class LocalVaultFeatureFlagsTest {
    private val flags: Map<String, FlagKey<*>> =
        FlagKey.activePasswordManagerFlags.associateBy { it.keyName }

    /**
     * The local vault lists the flags that move requests to where the local server does not
     * follow them: into the Bitwarden SDK, whose requests do not pass the local server in this
     * app, or to routes that it refuses. A flag that upstream removed is a code path that is now
     * always taken, so the local server has to be ready for it before the flag is taken out of
     * that list.
     */
    @Test
    fun `the flags that the local vault counts on to be off should be there and off`() {
        val rules = flagsThatMustBeOff(client = VaultClientKind.ANDROID)

        assertEquals(
            emptyList<String>(),
            rules.filter { flags[it.key]?.defaultValue != false }.map { "${it.key}: ${it.reason}" },
        )
    }

    /**
     * A flag that shows here is on for everyone who has a local vault. Look at what it turns on,
     * and whether the local vault has to list it, before it is taken out of here.
     */
    @Test
    fun `every flag should be off by default`() {
        assertEquals(
            emptyList<String>(),
            flags.values.filter { it.defaultValue != false }.map { it.keyName },
        )
    }
}
