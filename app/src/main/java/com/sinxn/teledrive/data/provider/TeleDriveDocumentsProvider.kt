package com.sinxn.teledrive.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.sinxn.teledrive.data.crypto.FileCryptor
import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.repository.FileRepository
import com.sinxn.teledrive.data.repository.MetadataManager
import com.sinxn.teledrive.data.telegram.TelegramClient
import com.sinxn.teledrive.di.DocumentsProviderEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * Exposes TeleDrive's Telegram-backed virtual file system to the Android
 * Storage Access Framework (SAF).
 *
 * Supports:
 * - Browsing & opening files (`ACTION_OPEN_DOCUMENT`, `ACTION_GET_CONTENT`)
 * - Selecting folders as save destinations (`ACTION_OPEN_DOCUMENT_TREE`)
 * - Creating new files and folders (`ACTION_CREATE_DOCUMENT`)
 * - In-place writing, editing, renaming, and deleting of documents
 *
 * Writes are encrypted with AES-256-GCM via [FileCryptor] and uploaded to
 * Telegram via TDLib upon file descriptor closure.
 */
class TeleDriveDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "TeleDriveDocsProv"

        /** Virtual document ID for the root of TeleDrive's file tree. */
        private const val ROOT_DOC_ID = "teledrive_root"

        /** Unique root ID exposed to SAF. */
        private const val ROOT_ID = "teledrive"

        /** Columns we return for root queries. */
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        )

        /** Columns we return for document queries. */
        private val DEFAULT_DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }

    // ── Lazy dependency resolution via Hilt EntryPoint ──────────────

    private val entryPoint: DocumentsProviderEntryPoint by lazy {
        val appContext = context?.applicationContext
            ?: throw IllegalStateException("DocumentsProvider has no context")
        EntryPointAccessors.fromApplication(appContext, DocumentsProviderEntryPoint::class.java)
    }

    private val fileDao: FileDao by lazy { entryPoint.fileDao() }
    private val securePrefs: SecurePrefs by lazy { entryPoint.securePrefs() }
    private val telegramClient: TelegramClient by lazy { entryPoint.telegramClient() }
    private val fileCryptor: FileCryptor by lazy { entryPoint.fileCryptor() }
    private val metadataManager: MetadataManager by lazy { entryPoint.metadataManager() }
    private val fileRepository: FileRepository by lazy { entryPoint.fileRepository() }

    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Lifecycle ───────────────────────────────────────────────────

    override fun onCreate(): Boolean {
        Log.d(TAG, "TeleDriveDocumentsProvider created")
        return true
    }

    // ── Roots ───────────────────────────────────────────────────────

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveRootProjection(projection))

        // Only expose the root when a user is logged in.
        val userId = securePrefs.getUserId() ?: return result

        val rootFlags = DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "TeleDrive")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Telegram cloud files")
            add(DocumentsContract.Root.COLUMN_FLAGS, rootFlags)
            add(DocumentsContract.Root.COLUMN_ICON, com.sinxn.teledrive.R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }

        return result
    }

    // ── Document metadata ───────────────────────────────────────────

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveDocProjection(projection))
        if (documentId == null) return result

        if (documentId == ROOT_DOC_ID) {
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "TeleDrive")
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                add(
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                )
                add(DocumentsContract.Document.COLUMN_SIZE, null)
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, null)
            }
            return result
        }

        val resolvedId = fileRepository.resolveSafAlias(documentId) ?: documentId
        val entity = runBlocking(Dispatchers.IO) { fileDao.getFileById(resolvedId) }
            ?: return result

        addEntityRow(result, entity)
        return result
    }

    // ── Children listing ────────────────────────────────────────────

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(resolveDocProjection(projection))
        val userId = securePrefs.getUserId() ?: return result

        // Map the virtual ROOT_DOC_ID to null parentId (root level in the DB).
        val resolvedParentDocId = parentDocumentId?.let { fileRepository.resolveSafAlias(it) ?: it }
        val dbParentId = if (resolvedParentDocId == ROOT_DOC_ID) null else resolvedParentDocId

        val children = runBlocking(Dispatchers.IO) {
            fileDao.getFilesByParentSnapshot(dbParentId, userId)
        }

        for (entity in children) {
            addEntityRow(result, entity)
        }

        return result
    }

    // ── Tree validation ─────────────────────────────────────────────

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null) return false
        if (parentDocumentId == documentId) return true

        val resolvedParent = fileRepository.resolveSafAlias(parentDocumentId) ?: parentDocumentId
        val resolvedDoc = fileRepository.resolveSafAlias(documentId) ?: documentId
        if (resolvedParent == resolvedDoc) return true

        val expectedParentId = if (resolvedParent == ROOT_DOC_ID) null else resolvedParent
        var currentDocId: String? = resolvedDoc
        val visited = mutableSetOf<String>()

        while (currentDocId != null && currentDocId != ROOT_DOC_ID && visited.add(currentDocId)) {
            val entity = runBlocking(Dispatchers.IO) { fileDao.getFileById(currentDocId) } ?: break
            if (entity.parentId == expectedParentId) {
                return true
            }
            if (entity.parentId == null && resolvedParent == ROOT_DOC_ID) {
                return true
            }
            currentDocId = entity.parentId
        }
        return false
    }

    // ── Document Creation ───────────────────────────────────────────

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String? {
        val userId = securePrefs.getUserId() ?: return null
        val resolvedParent = parentDocumentId?.let { fileRepository.resolveSafAlias(it) ?: it }
        val targetParentId = if (resolvedParent == ROOT_DOC_ID) null else resolvedParent
        val rawName = displayName?.trim().takeIf { !it.isNullOrEmpty() } ?: "Untitled"

        return runBlocking(Dispatchers.IO) {
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                val folderId = "folder_${UUID.randomUUID()}"
                val folder = FileEntity(
                    id = folderId,
                    name = rawName,
                    type = "folder",
                    userId = userId,
                    parentId = targetParentId,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                fileDao.insertFile(folder)
                triggerRemoteMetadataSync(userId)
                notifyParentChanged(parentDocumentId ?: ROOT_DOC_ID)
                folderId
            } else {
                val fileId = "saf_${UUID.randomUUID()}"
                val fileEntity = FileEntity(
                    id = fileId,
                    name = rawName,
                    type = "file",
                    mimeType = mimeType,
                    userId = userId,
                    parentId = targetParentId,
                    size = 0L,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                fileDao.insertFile(fileEntity)
                notifyParentChanged(parentDocumentId ?: ROOT_DOC_ID)
                fileId
            }
        }
    }

    // ── Document Deletion ───────────────────────────────────────────

    override fun deleteDocument(documentId: String?) {
        if (documentId == null || documentId == ROOT_DOC_ID) return
        val userId = securePrefs.getUserId() ?: return

        runBlocking(Dispatchers.IO) {
            val resolvedId = fileRepository.resolveSafAlias(documentId) ?: documentId
            val entity = fileDao.getFileById(resolvedId) ?: return@runBlocking
            fileRepository.deleteFile(entity)
            notifyParentChanged(entity.parentId ?: ROOT_DOC_ID)
        }
    }

    // ── Document Renaming ───────────────────────────────────────────

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        if (documentId == null || documentId == ROOT_DOC_ID || displayName.isNullOrBlank()) return null

        runBlocking(Dispatchers.IO) {
            val resolvedId = fileRepository.resolveSafAlias(documentId) ?: documentId
            fileRepository.renameFile(resolvedId, displayName.trim())
            notifyDocumentChanged(documentId)
        }
        return documentId
    }

    // ── File streaming / reading / writing ───────────────────────────

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        if (documentId == null) return null

        val resolvedId = fileRepository.resolveSafAlias(documentId) ?: documentId
        val entity = runBlocking(Dispatchers.IO) { fileDao.getFileById(resolvedId) }
            ?: return null

        if (entity.type == "folder") return null

        val isWrite = mode?.contains("w") == true ||
                mode?.contains("rwt") == true ||
                mode?.contains("wa") == true

        return if (isWrite && mode != null) {
            openDocumentForWrite(entity, mode)
        } else {
            openDocumentForRead(entity, signal)
        }
    }

    private fun openDocumentForRead(
        entity: FileEntity,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        // If the file is an empty local placeholder with no Telegram data, return an empty descriptor
        if (entity.fileId.isNullOrBlank() && entity.messageId.isNullOrBlank()) {
            val emptyTemp = File(context!!.cacheDir, "empty_${System.currentTimeMillis()}.tmp")
            emptyTemp.createNewFile()
            return ParcelFileDescriptor.open(emptyTemp, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Resolve the Telegram file ID. Re-fetch from TG if the local record is stale.
        val resolvedEntity = runBlocking(Dispatchers.IO) { resolveLatestFile(entity) }
        val fileIdInt = resolvedEntity.fileId?.toIntOrNull()
            ?: throw IllegalStateException("No Telegram file ID for ${entity.name}")

        // Step 1: Download the encrypted file from Telegram (blocking).
        val tdFile = downloadTelegramFile(fileIdInt, resolvedEntity, signal)
            ?: throw IllegalStateException("Failed to download ${entity.name} from Telegram")

        val encryptedFile = File(tdFile.local.path)
        if (!encryptedFile.exists()) {
            throw IllegalStateException("Downloaded file not found at ${tdFile.local.path}")
        }

        // Step 2: Decrypt to a pipe — no plaintext touches disk.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]  // returned to the calling app
        val writeEnd = pipe[1] // we write decrypted data into this

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Wire cancellation signal to abort the background work.
        signal?.setOnCancelListener {
            scope.cancel()
        }

        scope.launch {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { outputStream ->
                    if (encryptedFile.name.endsWith(".enc")) {
                        val tempDecrypted = File(
                            context!!.cacheDir,
                            "saf_dec_${System.currentTimeMillis()}_${entity.cleanExportName()}"
                        )
                        try {
                            fileCryptor.decryptFile(encryptedFile, tempDecrypted)
                            FileInputStream(tempDecrypted).use { it.copyTo(outputStream) }
                        } finally {
                            tempDecrypted.delete()
                        }
                    } else {
                        FileInputStream(encryptedFile).use { it.copyTo(outputStream) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming decrypted file to SAF pipe", e)
                try {
                    writeEnd.closeWithError(e.message ?: "Decryption failed")
                } catch (_: Exception) { /* write end already closed */ }
            }
        }

        return readEnd
    }

    private fun openDocumentForWrite(
        entity: FileEntity,
        mode: String
    ): ParcelFileDescriptor? {
        val tempFile = File(
            context!!.cacheDir,
            "saf_write_${entity.id}_${System.currentTimeMillis()}.tmp"
        )

        // If appending or read-writing an existing file, populate initial decrypted content
        val isAppendOrReadWrite = (mode.contains("a") || mode == "rw") && !mode.contains("t")
        if (isAppendOrReadWrite && !entity.fileId.isNullOrBlank()) {
            try {
                val resolved = runBlocking(Dispatchers.IO) { resolveLatestFile(entity) }
                resolved.fileId?.toIntOrNull()?.let { fileIdInt ->
                    val tdFile = downloadTelegramFile(fileIdInt, resolved, null)
                    if (tdFile != null && File(tdFile.local.path).exists()) {
                        val enc = File(tdFile.local.path)
                        if (enc.name.endsWith(".enc")) {
                            fileCryptor.decryptFile(enc, tempFile)
                        } else {
                            enc.copyTo(tempFile, overwrite = true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not pre-populate existing file for append mode", e)
            }
        }

        if (!tempFile.exists()) {
            tempFile.createNewFile()
        }

        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(tempFile, pfdMode, mainHandler) { ioException ->
            if (ioException != null) {
                Log.e(TAG, "Write aborted for document ${entity.id}", ioException)
                tempFile.delete()
                return@open
            }

            providerScope.launch(Dispatchers.IO) {
                uploadWrittenSafFile(entity.id, tempFile)
            }
        }
    }

    private suspend fun uploadWrittenSafFile(documentId: String, tempFile: File) {
        try {
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.w(TAG, "Written temp file for $documentId is empty or does not exist")
                tempFile.delete()
                return
            }

            val userId = securePrefs.getUserId() ?: run {
                tempFile.delete()
                return
            }
            val resolvedId = fileRepository.resolveSafAlias(documentId) ?: documentId
            val entity = fileDao.getFileById(resolvedId) ?: run {
                tempFile.delete()
                return
            }

            val parentId = entity.parentId
            val existingMessageId = entity.messageId?.toLongOrNull()

            if (existingMessageId != null && !entity.fileId.isNullOrBlank()) {
                fileRepository.replaceSafFile(entity, tempFile)
            } else {
                fileRepository.uploadSafFile(
                    tempFile = tempFile,
                    displayName = entity.name,
                    mimeType = entity.mimeType,
                    parentId = parentId,
                    userId = userId,
                    placeholderId = entity.id
                )
            }

            notifyDocumentChanged(documentId)
            notifyParentChanged(parentId ?: ROOT_DOC_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload written SAF file for $documentId", e)
            tempFile.delete()
        }
    }

    // ── Search ──────────────────────────────────────────────────────

    override fun querySearchDocuments(
        rootId: String?,
        query: String?,
        projection: Array<out String>?
    ): Cursor {
        val result = MatrixCursor(resolveDocProjection(projection))
        if (query.isNullOrBlank()) return result
        val userId = securePrefs.getUserId() ?: return result

        val matches = runBlocking(Dispatchers.IO) {
            fileDao.searchFilesSnapshot(query, userId)
        }

        for (entity in matches) {
            addEntityRow(result, entity)
        }

        return result
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun addEntityRow(cursor: MatrixCursor, entity: FileEntity) {
        val isFolder = entity.type == "folder"
        val mimeType = if (isFolder) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            entity.mimeType
                ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(entity.name.substringAfterLast('.', ""))
                ?: "application/octet-stream"
        }

        val flags = if (isFolder) {
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        } else {
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }

        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, entity.id)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, entity.cleanExportName())
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
            add(DocumentsContract.Document.COLUMN_SIZE, entity.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, entity.updatedAt)
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
    }

    private fun FileEntity.cleanExportName(): String {
        if (!this.originalFileName.isNullOrBlank()) return this.originalFileName
        val suffix = ".enc"
        return if (this.name.lowercase().endsWith(suffix)) {
            this.name.dropLast(suffix.length)
        } else {
            this.name
        }
    }

    private suspend fun resolveTargetChatId(userId: String): Long {
        val configuredChatId = securePrefs.getStorageChannelId()
        return if (configuredChatId != null) {
            configuredChatId
        } else {
            val chat = telegramClient.send(
                TdApi.CreatePrivateChat(userId.toLong(), true)
            ) as TdApi.Chat
            chat.id
        }
    }

    private fun triggerRemoteMetadataSync(userId: String) {
        providerScope.launch(Dispatchers.IO) {
            try {
                val chatId = resolveTargetChatId(userId)
                val messageId = securePrefs.getMetadataMessageId()
                if (messageId != null && messageId != 0L) {
                    metadataManager.updateMetadataMessage(chatId, messageId)
                } else {
                    val (newMsgId, _) = metadataManager.initializeMetadataMessage(chatId)
                    securePrefs.setMetadataMessageId(newMsgId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync metadata remotely", e)
            }
        }
    }

    private fun notifyDocumentChanged(documentId: String) {
        val authority = "${context?.packageName}.documents"
        val uri = DocumentsContract.buildDocumentUri(authority, documentId)
        context?.contentResolver?.notifyChange(uri, null)
    }

    private fun notifyParentChanged(parentId: String) {
        val authority = "${context?.packageName}.documents"
        val uri = if (parentId == ROOT_DOC_ID) {
            DocumentsContract.buildRootsUri(authority)
        } else {
            DocumentsContract.buildChildDocumentsUri(authority, parentId)
        }
        context?.contentResolver?.notifyChange(uri, null)
    }

    private suspend fun resolveLatestFile(file: FileEntity): FileEntity {
        val messageId = file.messageId?.toLongOrNull() ?: return file
        val chatId = file.chatId
            ?: securePrefs.getStorageChannelId()
            ?: return file

        return try {
            val message = telegramClient.send(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message
                ?: return file
            val doc = (message.content as? TdApi.MessageDocument)?.document ?: return file
            val freshFileId = doc.document.id.toString()

            if (freshFileId != file.fileId) {
                val updated = file.copy(fileId = freshFileId)
                fileDao.updateFile(updated)
                updated
            } else {
                file
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not re-fetch message for ${file.name}, using cached fileId", e)
            file
        }
    }

    private fun downloadTelegramFile(
        fileId: Int,
        entity: FileEntity,
        signal: CancellationSignal?
    ): TdApi.File? {
        val chatId = entity.chatId
            ?: securePrefs.getStorageChannelId()

        return runBlocking(Dispatchers.IO) {
            if (chatId != null) {
                try {
                    telegramClient.send(TdApi.OpenChat(chatId))
                } catch (e: Exception) {
                    Log.w(TAG, "OpenChat failed, continuing download anyway", e)
                }
            }

            val downloadResult = telegramClient.send(
                TdApi.DownloadFile(fileId, 32, 0, 0, true)
            )

            if (downloadResult is TdApi.File && downloadResult.local.isDownloadingCompleted) {
                return@runBlocking downloadResult
            }

            val deferred = CompletableDeferred<TdApi.File>()

            signal?.setOnCancelListener {
                deferred.cancel()
            }

            val job = Job()
            CoroutineScope(job + Dispatchers.IO).launch {
                telegramClient.updates.collect { update ->
                    if (update is TdApi.UpdateFile && update.file.id == fileId) {
                        if (update.file.local.isDownloadingCompleted && update.file.local.path.isNotEmpty()) {
                            deferred.complete(update.file)
                        }
                    }
                }
            }

            try {
                deferred.await()
            } finally {
                job.cancel()
            }
        }
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> {
        return projection?.toList()?.toTypedArray() ?: DEFAULT_ROOT_PROJECTION
    }

    private fun resolveDocProjection(projection: Array<out String>?): Array<String> {
        return projection?.toList()?.toTypedArray() ?: DEFAULT_DOC_PROJECTION
    }
}
