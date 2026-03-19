package com.sinxn.teledrive.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE users ADD COLUMN photo_file_id INTEGER")
        database.execSQL("ALTER TABLE users ADD COLUMN photo_path TEXT")
    }
}
