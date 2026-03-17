package com.sinxn.teledrive.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object FileUtils {

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
}
