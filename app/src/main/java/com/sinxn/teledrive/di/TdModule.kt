package com.sinxn.teledrive.di

import com.sinxn.teledrive.data.telegram.TelegramClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the [TelegramClient] singleton.
 *
 * Since TelegramClient is annotated with @Inject constructor,
 * Hilt can construct it automatically. This module exists to
 * explicitly document the singleton scope and allow future
 * configuration (e.g., providing TDLib parameters).
 */
@Module
@InstallIn(SingletonComponent::class)
object TdModule {

    @Provides
    @Singleton
    fun provideTelegramClient(): TelegramClient {
        return TelegramClient()
    }
}
