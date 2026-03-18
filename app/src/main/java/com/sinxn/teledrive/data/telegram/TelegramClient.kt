package com.sinxn.teledrive.data.telegram

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Kotlin wrapper around TDLib's [Client] providing a coroutine-friendly API.
 *
 * This is a @Singleton so the entire app shares one TDLib client instance.
 * Actual authentication flows (phone/OTP/2FA/QR) will be built on top
 * of this in Sprint 2.
 */
@Singleton
class TelegramClient @Inject constructor() {

    companion object {
        private const val TAG = "TelegramClient"

        init {
            try {
                System.loadLibrary("tdjni")
                Log.i(TAG, "TDLib native library (tdjni) loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load tdjni native library", e)
            }
        }
    }

    private var client: Client? = null
    
    // Broadcasts all unsolicited updates from Telegram (new messages, auth state, file progress).
    // replay = 100 ensures that subscribers who attach slightly late (due to coroutine dispatch delays)
    // don't miss the initial burst of initialization updates from TDLib.
    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 100, 
        extraBufferCapacity = 100, 
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val updates = _updates.asSharedFlow()

    /**
     * Creates and initializes the TDLib client.
     */
    fun create() {
        if (client != null) {
            Log.w(TAG, "TDLib client already created, ignoring duplicate create()")
            return
        }

        // Set TDLib log verbosity (1 = errors only, 5 = debug)
        Client.execute(TdApi.SetLogVerbosityLevel(2))

        client = Client.create(
            /* updateHandler = */ { update -> _updates.tryEmit(update) },
            /* updateExceptionHandler = */ { e ->
                Log.e(TAG, "TDLib update exception", e)
            },
            /* defaultExceptionHandler = */ { e ->
                Log.e(TAG, "TDLib default exception", e)
            }
        )
        Log.i(TAG, "TDLib client created successfully")
    }

    /**
     * Sends a TDLib request and suspends until the response arrives.
     * Returns the [TdApi.Object] result or throws on error.
     */
    suspend fun send(function: TdApi.Function<*>): TdApi.Object = withContext(Dispatchers.IO) {
        val currentClient = client ?: throw IllegalStateException("TDLib client not created. Call create() first.")
        suspendCoroutine { continuation ->
            currentClient.send(function) { result ->
                if (result is TdApi.Error) {
                    continuation.resumeWithException(
                        TdLibException(result.code, result.message)
                    )
                } else {
                    continuation.resume(result)
                }
            }
        }
    }

    /**
     * Sends a request without waiting for a response (fire-and-forget).
     */
    fun sendAsync(function: TdApi.Function<*>, handler: (TdApi.Object) -> Unit = { }) {
        val currentClient = client ?: throw IllegalStateException("TDLib client not created. Call create() first.")
        currentClient.send(function, handler)
    }

    /**
     * Destroys the TDLib client and releases resources.
     */
    fun destroy() {
        client?.send(TdApi.Close()) { }
        client = null
        Log.i(TAG, "TDLib client destroyed")
    }

    /**
     * Returns true if the client has been created and is active.
     */
    val isActive: Boolean get() = client != null
}

/**
 * Exception thrown when TDLib returns a [TdApi.Error].
 */
class TdLibException(
    val code: Int,
    override val message: String
) : Exception("TDLib error $code: $message")
