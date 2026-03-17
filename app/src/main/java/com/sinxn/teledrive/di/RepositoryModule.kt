package com.sinxn.teledrive.di

import com.sinxn.teledrive.data.repository.FileRepository
import com.sinxn.teledrive.data.repository.FileRepositoryImpl
import com.sinxn.teledrive.data.repository.UserRepository
import com.sinxn.teledrive.data.repository.UserRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding repository interfaces to their implementations.
 * This allows ViewModels to depend on the interface, making testing easy
 * (swap in a fake/mock implementation).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository
}
