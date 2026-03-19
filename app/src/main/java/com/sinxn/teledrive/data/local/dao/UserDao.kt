package com.sinxn.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.sinxn.teledrive.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Upsert
    suspend fun upsertUser(user: UserEntity)

    @Upsert
    suspend fun upsertUsers(users: List<UserEntity>)

    @Query("SELECT * FROM users LIMIT 1")
    fun getCurrentUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getCurrentUserSync(): UserEntity?

    @Query("SELECT * FROM users ORDER BY created_at ASC")
    suspend fun getAllUsers(): List<UserEntity>

    @Query("DELETE FROM users")
    suspend fun deleteAllUsers()
}
