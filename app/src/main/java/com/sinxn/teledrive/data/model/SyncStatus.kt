package com.sinxn.teledrive.data.model

/**
 * Represents the current state of the Telegram file sync crawler.
 */
/**
 * Detailed metrics for the sync process.
 */
data class SyncMetrics(
    val filesImported: Int = 0,
    val foldersImported: Int = 0,
    val messagesScanned: Int = 0,
    val additionalFiles: List<String> = emptyList(),
    val deletedFiles: List<String> = emptyList()
)

sealed class SyncStatus {
    object Idle : SyncStatus()

    data class Syncing(val metrics: SyncMetrics) : SyncStatus()

    data class MetadataImported(val metrics: SyncMetrics) : SyncStatus()

    data class Reconciling(val metrics: SyncMetrics) : SyncStatus()

    data class Completed(val metrics: SyncMetrics) : SyncStatus()

    data class Error(val message: String) : SyncStatus()
}
