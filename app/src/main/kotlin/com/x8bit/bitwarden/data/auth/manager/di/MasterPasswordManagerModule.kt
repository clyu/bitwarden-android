package com.x8bit.bitwarden.data.auth.manager.di

import com.bitwarden.core.data.manager.toast.ToastManager
import com.bitwarden.network.service.AccountsService
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.manager.MasterPasswordManager
import com.x8bit.bitwarden.data.auth.manager.MasterPasswordManagerImpl
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the manager of the master password of an account.
 */
@Module
@InstallIn(SingletonComponent::class)
object MasterPasswordManagerModule {

    @Provides
    @Singleton
    fun providesMasterPasswordManager(
        authDiskSource: AuthDiskSource,
        authSdkSource: AuthSdkSource,
        vaultSdkSource: VaultSdkSource,
        accountsService: AccountsService,
        userLogoutManager: UserLogoutManager,
        toastManager: ToastManager,
    ): MasterPasswordManager = MasterPasswordManagerImpl(
        authDiskSource = authDiskSource,
        authSdkSource = authSdkSource,
        vaultSdkSource = vaultSdkSource,
        accountsService = accountsService,
        userLogoutManager = userLogoutManager,
        toastManager = toastManager,
    )
}
