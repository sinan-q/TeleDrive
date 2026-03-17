package com.sinxn.teledrive.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.dao.UserDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.entity.UserEntity

@Database(
    entities = [UserEntity::class, FileEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun fileDao(): FileDao
}
