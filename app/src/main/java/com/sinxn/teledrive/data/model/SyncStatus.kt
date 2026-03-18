package com.sinxn.teledrive.data.model

/**
 * Represents the current state of the Telegram file sync crawler.
 */
/**
 * Detailed metrics for the sync process.
 */
data class SyncMetrics(
    val messagesScanned: Int = 0,
    val totalDocumentsFound: Int = 0,
    val encFilesMatched: Int = 0,
    val foundFilenames: List<String> = emptyList()
)

sealed class SyncStatus {
    object Idle : SyncStatus()

    data class Syncing(val metrics: SyncMetrics) : SyncStatus()

    data class Completed(val metrics: SyncMetrics) : SyncStatus()

    data class Error(val message: String) : SyncStatus()
}
