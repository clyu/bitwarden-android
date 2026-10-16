package com.x8bit.bitwarden.data.platform.manager.localvault.di

import android.app.Application
import android.content.SharedPreferences
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.data.datasource.disk.di.UnencryptedPreferences
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.platform.datasource.disk.LocalVaultDiskSource
import com.x8bit.bitwarden.data.platform.datasource.disk.LocalVaultDiskSourceImpl
import com.x8bit.bitwarden.data.platform.manager.localvault.LocalVaultStorageManager
import com.x8bit.bitwarden.data.platform.manager.localvault.LocalVaultStorageManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the dependencies that keep the vault on the device.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalVaultModule {

    @Provides
    @Singleton
    fun provideLocalVaultDiskSource(
        @UnencryptedPreferences sharedPreferences: SharedPreferences,
    ): LocalVaultDiskSource =
        LocalVaultDiskSourceImpl(
            sharedPreferences = sharedPreferences,
        )

    @Provides
    @Singleton
    fun provideLocalVaultStorageManager(
        application: Application,
        localVaultDiskSource: LocalVaultDiskSource,
        authDiskSource: AuthDiskSource,
        dispatcherManager: DispatcherManager,
    ): LocalVaultStorageManager =
        LocalVaultStorageManagerImpl(
            context = application.applicationContext,
            localVaultDiskSource = localVaultDiskSource,
            appIdProvider = authDiskSource,
            dispatcherManager = dispatcherManager,
        )
}
