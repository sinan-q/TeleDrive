package com.sinxn.teledrive.data.model

/**
 * Represents the current state of the Telegram file sync crawler.
 */
sealed class SyncStatus {
    /** Crawler is not running. */
    object Idle : SyncStatus()

    /** Crawler is currently scanning chat history. */
    data class Syncing(val processedCount: Int) : SyncStatus()

    /** Crawler finished successfully. */
    data class Completed(val importedCount: Int) : SyncStatus()

    /** Crawler failed with an error. */
    data class Error(val message: String) : SyncStatus()
}
