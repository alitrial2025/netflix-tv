package com.example.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only detached text leaves the callback, so cancellation cannot leak a response body. */
internal data class HttpTextResponse(
    val request: Request,
    val code: Int,
    val headers: Headers,
    val body: String
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun header(name: String): String? = headers[name]
}

/** Response metadata for requests whose body is irrelevant to the handshake. */
internal data class HttpHeaderResponse(
    val request: Request,
    val code: Int,
    val headers: Headers
)

internal suspend fun OkHttpClient.fetchHeaders(request: Request): HttpHeaderResponse =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (continuation.isActive) {
                        continuation.resume(HttpHeaderResponse(it.request, it.code, it.headers))
                    }
                }
            }
        })
    }

internal suspend fun OkHttpClient.fetchText(request: Request): HttpTextResponse =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!continuation.isActive) return
                        HttpTextResponse(it.request, it.code, it.headers, it.body?.string().orEmpty())
                    }
                    continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
