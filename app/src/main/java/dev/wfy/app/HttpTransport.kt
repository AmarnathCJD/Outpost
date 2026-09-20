package dev.wfy.app

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val status: Int, message: String) : IOException(message)

fun workspaceHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(200, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
    .pingInterval(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).build()

/** Cancellation closes the actual socket, including searches replaced by a newer query. */
suspend fun executeRequest(client: OkHttpClient, request: Request): String = suspendCancellableCoroutine { continuation ->
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val text = response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) throw ApiFailure(it.code, runCatching { JSONObject(body).getString("error") }.getOrDefault("Server returned ${it.code}: ${body.take(240)}"))
                    body
                }
                if (continuation.isActive) continuation.resume(text)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    })
}
