package com.sinxn.teledrive.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object FileUtils {

    data class PickedFileMetadata(
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long?
    )

    /**
     * Resolves an Android Storage Access Framework Uri to a physical file.
     * Copies the content to a temporary file in the cache directory so TDLib can read it.
     */
    fun resolveUriToTempFile(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        
        // Try to get original filename
        var fileName = "temp_upload_${UUID.randomUUID()}"
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        fileName = name
                    }
                }
            }
        }

        val tempFile = File(context.cacheDir, fileName)
        
        return try {
            resolver.openInputStream(uri)?.use { inputStream: InputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun resolvePickedFileMetadata(context: Context, uri: Uri): PickedFileMetadata {
        val resolver = context.contentResolver
        var fileName = "temp_upload_${UUID.randomUUID()}"
        var mimeType: String? = resolver.getType(uri)
        var sizeBytes: Long? = null

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        fileName = name
                    }
                }
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }

        if (mimeType.isNullOrBlank()) {
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
        }

        return PickedFileMetadata(
            displayName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes
        )
    }

    /**
     * Exports an internal file out to the public Android Downloads/TeleDrive folder.
     */
    fun exportToDownloads(context: Context, sourceFile: File, fileName: String, mimeType: String): Boolean {
        if (!sourceFile.exists()) {
            Log.e("FileUtils", "Source file does not exist: ${sourceFile.absolutePath}")
            return false
        }

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            // Save in a dedicated TeleDrive subfolder inside public Downloads
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TeleDrive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // Mark the file as successfully written and no longer pending
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            
            Log.d("FileUtils", "Successfully exported file to Downloads/TeleDrive: $fileName")
            true
        } catch (e: Exception) {
            Log.e("FileUtils", "Failed to export file to Downloads", e)
            resolver.delete(uri, null, null)
            false
        }
    }
}
