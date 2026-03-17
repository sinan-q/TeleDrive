package com.sinxn.teledrive.data.repository

import com.sinxn.teledrive.data.local.dao.UserDao
import com.sinxn.teledrive.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [UserRepository] backed by Room [UserDao].
 */
@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao
) : UserRepository {

    override fun getCurrentUser(): Flow<UserEntity?> =
        userDao.getCurrentUser()

    override suspend fun getCurrentUserSync(): UserEntity? =
        userDao.getCurrentUserSync()

    override suspend fun saveUser(user: UserEntity) =
        userDao.insertUser(user)

    override suspend fun clearUser() =
        userDao.deleteAllUsers()
}
