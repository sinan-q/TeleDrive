package com.sinxn.teledrive.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.sinxn.teledrive.data.crypto.FileCryptor
import com.sinxn.teledrive.data.local.dao.FileDao
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
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

/**
 * Exposes TeleDrive's Telegram-backed virtual file system to the Android
 * Storage Access Framework (SAF).
 *
 * This allows third-party apps (Chrome uploads, email attachments, etc.) and
 * the system "Files" app to browse TeleDrive folders and open files. The
 * provider downloads the encrypted `.enc` file from Telegram via TDLib,
 * decrypts it on the fly, and streams the plaintext through a pipe so no
 * cleartext is ever persisted on disk.
 *
 * **Read-only** for the initial version. `createDocument` / `deleteDocument`
 * may be added in a future sprint.
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

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "TeleDrive")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Telegram cloud files")
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
            )
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
                add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                add(DocumentsContract.Document.COLUMN_SIZE, null)
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, null)
            }
            return result
        }

        val entity = runBlocking(Dispatchers.IO) { fileDao.getFileById(documentId) }
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
        val dbParentId = if (parentDocumentId == ROOT_DOC_ID) null else parentDocumentId

        val children = runBlocking(Dispatchers.IO) {
            fileDao.getFilesByParentSnapshot(dbParentId, userId)
        }

        for (entity in children) {
            addEntityRow(result, entity)
        }

        return result
    }

    // ── File streaming ──────────────────────────────────────────────

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        if (documentId == null) return null

        val entity = runBlocking(Dispatchers.IO) { fileDao.getFileById(documentId) }
            ?: return null

        if (entity.type == "folder") return null

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
                        // Decrypt in-place to the pipe output stream.
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
                        // Not encrypted — stream raw bytes.
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

    /**
     * Adds a row to [cursor] mapping [entity]'s fields to DocumentsContract columns.
     */
    private fun addEntityRow(cursor: MatrixCursor, entity: FileEntity) {
        val mimeType = if (entity.type == "folder") {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            entity.mimeType
                ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(entity.name.substringAfterLast('.', ""))
                ?: "application/octet-stream"
        }

        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, entity.id)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, entity.cleanExportName())
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
            add(DocumentsContract.Document.COLUMN_SIZE, entity.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, entity.updatedAt)
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        }
    }

    /**
     * Strips the `.enc` suffix from the entity name for display purposes.
     */
    private fun FileEntity.cleanExportName(): String {
        if (!this.originalFileName.isNullOrBlank()) return this.originalFileName
        val suffix = ".enc"
        return if (this.name.lowercase().endsWith(suffix)) {
            this.name.dropLast(suffix.length)
        } else {
            this.name
        }
    }

    /**
     * Re-fetches the Telegram message to get the freshest file ID, in case the
     * local Room record has a stale or missing `fileId`.
     */
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

    /**
     * Synchronously downloads a Telegram file, blocking until TDLib reports
     * `isDownloadingCompleted`. Honors the [signal] for cancellation.
     */
    private fun downloadTelegramFile(
        fileId: Int,
        entity: FileEntity,
        signal: CancellationSignal?
    ): TdApi.File? {
        // Open the chat so TDLib has the data context.
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

            // Fire off the download request.
            val downloadResult = telegramClient.send(
                TdApi.DownloadFile(fileId, 32, 0, 0, true)
            )

            if (downloadResult is TdApi.File && downloadResult.local.isDownloadingCompleted) {
                return@runBlocking downloadResult
            }

            // If the synchronous download didn't complete, listen for updates.
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
