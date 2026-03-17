package com.sinxn.teledrive.data.repository

import com.sinxn.teledrive.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for user-related data operations.
 * Abstracts the data source (Room local DB) from the rest of the app.
 */
interface UserRepository {

    /** Observe the currently logged-in user reactively. */
    fun getCurrentUser(): Flow<UserEntity?>

    /** Get the current user synchronously (suspend). */
    suspend fun getCurrentUserSync(): UserEntity?

    /** Save/update user info (e.g., after TDLib login). */
    suspend fun saveUser(user: UserEntity)

    /** Clear user data (logout). */
    suspend fun clearUser()
}
