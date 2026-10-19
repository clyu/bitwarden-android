package com.x8bit.bitwarden.data.auth.manager.di

import com.bitwarden.network.service.AccountsService
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.manager.KdfSettingsManager
import com.x8bit.bitwarden.data.auth.manager.KdfSettingsManagerImpl
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the manager of how the master key of an account is derived from its master password.
 */
@Module
@InstallIn(SingletonComponent::class)
object KdfSettingsManagerModule {

    @Provides
    @Singleton
    fun providesKdfSettingsManager(
        authDiskSource: AuthDiskSource,
        authSdkSource: AuthSdkSource,
        vaultSdkSource: VaultSdkSource,
        accountsService: AccountsService,
    ): KdfSettingsManager = KdfSettingsManagerImpl(
        authDiskSource = authDiskSource,
        authSdkSource = authSdkSource,
        vaultSdkSource = vaultSdkSource,
        accountsService = accountsService,
    )
}
