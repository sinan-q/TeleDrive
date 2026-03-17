package com.sinxn.teledrive.di

import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.local.prefs.SecurePrefsImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    abstract fun bindSecurePrefs(
        securePrefsImpl: SecurePrefsImpl
    ): SecurePrefs
}
