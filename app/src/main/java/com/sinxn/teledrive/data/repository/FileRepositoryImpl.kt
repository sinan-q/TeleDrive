package com.sinxn.teledrive.data.repository

import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [FileRepository] backed by Room [FileDao].
 * In later sprints, this will also coordinate with TDLib for remote operations
 * (upload, download, sync).
 */
@Singleton
class FileRepositoryImpl @Inject constructor(
    private val fileDao: FileDao
) : FileRepository {

    override fun getFilesByParent(parentId: String?, userId: String): Flow<List<FileEntity>> =
        fileDao.getFilesByParent(parentId, userId)

    override suspend fun getFileById(id: String): FileEntity? =
        fileDao.getFileById(id)

    override suspend fun getFileByMessageId(messageId: String): FileEntity? =
        fileDao.getFileByMessageId(messageId)

    override suspend fun saveFile(file: FileEntity) =
        fileDao.insertFile(file)

    override suspend fun saveFiles(files: List<FileEntity>) =
        fileDao.insertFiles(files)

    override suspend fun updateFile(file: FileEntity) =
        fileDao.updateFile(file)

    override suspend fun deleteFile(id: String) =
        fileDao.softDeleteFile(id)

    override fun searchFiles(query: String, userId: String): Flow<List<FileEntity>> =
        fileDao.searchFiles(query, userId)

    override suspend fun getStorageUsed(userId: String): Long =
        fileDao.getStorageUsed(userId)
}
