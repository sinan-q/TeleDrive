package com.sinxn.teledrive.di

import com.sinxn.teledrive.data.crypto.FileCryptor
import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.telegram.TelegramClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt [EntryPoint] exposing dependencies needed by [TeleDriveDocumentsProvider].
 *
 * DocumentsProvider instances are created by the Android system before Hilt can
 * perform field/constructor injection, so we use [EntryPointAccessors] to pull
 * singletons out of the component manually.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DocumentsProviderEntryPoint {
    fun fileDao(): FileDao
    fun securePrefs(): SecurePrefs
    fun telegramClient(): TelegramClient
    fun fileCryptor(): FileCryptor
}
