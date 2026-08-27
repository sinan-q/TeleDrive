package com.sinxn.teledrive.data.telegram

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Kotlin wrapper around TDLib's [Client] providing a coroutine-friendly API.
 *
 * This is a @Singleton so the entire app shares one TDLib client instance.
 * Updates are delivered via an UNLIMITED [Channel] to guarantee no TDLib
 * update is ever silently dropped (unlike SharedFlow with DROP_OLDEST).
 */
@Singleton
class TelegramClient @Inject constructor() {

    companion object {
        private const val TAG = "TelegramClient"
        const val DEFAULT_REQUEST_TIMEOUT_MS = 15_000L

        init {
            try {
                System.loadLibrary("tdjni")
                Log.i(TAG, "TDLib native library (tdjni) loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load tdjni native library", e)
            }
        }
    }

    @Volatile
    private var client: Client? = null

    private val lock = Any()

    // Unbounded SharedFlow guarantees no TDLib update is ever dropped.
    // With extraBufferCapacity = MAX_VALUE, tryEmit() always succeeds
    // (no DROP_OLDEST needed). replay = 0 avoids delivering stale updates
    // to late subscribers. Multiple collectors all receive every update (fan-out).
    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 0,
        extraBufferCapacity = Int.MAX_VALUE
    )
    val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    /**
     * Creates and initializes the TDLib client.
     * Thread-safe: concurrent calls are serialized, and only the first call
     * creates the Client; subsequent calls are no-ops while a Client exists.
     */
    fun create() {
        synchronized(lock) {
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
    }

    /**
     * Sends a TDLib request and suspends until the response arrives.
     * Returns the [TdApi.Object] result or throws on error.
     */
    suspend fun send(function: TdApi.Function<*>): TdApi.Object = withContext(Dispatchers.IO) {
        val currentClient = client ?: throw IllegalStateException("TDLib client not created. Call create() first.")
        suspendCancellableCoroutine { continuation ->
            currentClient.send(function) { result ->
                if (continuation.isActive) {
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
    }

    /**
     * Sends a TDLib request and suspends until the response arrives or the timeout expires.
     * Throws [kotlinx.coroutines.TimeoutCancellationException] if timeout is exceeded.
     */
    suspend fun sendWithTimeout(
        function: TdApi.Function<*>,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS
    ): TdApi.Object = withTimeout(timeoutMillis) {
        send(function)
    }

    /**
     * Sends a request without waiting for a response (fire-and-forget).
     */
    fun sendAsync(function: TdApi.Function<*>, handler: (TdApi.Object) -> Unit = { }) {
        val currentClient = client ?: throw IllegalStateException("TDLib client not created. Call create() first.")
        currentClient.send(function, handler)
    }

    /**
     * Resets the internal client reference (e.g. when closed externally)
     * so it can be re-created via create().
     *
     * Must only be called after receiving [TdApi.AuthorizationStateClosed]
     * to ensure TDLib has finished all internal cleanup.
     */
    fun reset() {
        synchronized(lock) {
            client = null
            Log.i(TAG, "TDLib client reference reset")
        }
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
