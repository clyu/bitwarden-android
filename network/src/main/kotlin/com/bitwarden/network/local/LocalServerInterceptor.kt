package com.bitwarden.network.local

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import okio.sink
import okio.source
import java.io.IOException

private const val HEADER_KEY_CONTENT_TYPE: String = "Content-Type"
private const val SUCCESS_CODE_START: Int = 200
private const val SUCCESS_CODE_END: Int = 299

/**
 * An [Interceptor] that has the [localServer] answer requests, so that they never leave the
 * device. A request is handed over as it is, and what it asks for is worked out by the local
 * vault: it runs before the address of the server is filled in, which the local vault does not
 * need, so that it answers every request of a client without a [host] whatever its address is.
 *
 * @param host If set, only requests for this host are answered by the [localServer] and all
 * others are passed on.
 */
internal class LocalServerInterceptor(
    private val localServer: LocalServer,
    private val host: String? = null,
) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (host != null && request.url.host != host) {
            return chain.proceed(request = request)
        }
        val localResponse = localServer.handle(request = request.toLocalRequest())
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(localResponse.code)
            .message(if (localResponse.isSuccessful) "OK" else "Error")
            .body(localResponse.toResponseBody())
            .build()
    }
}

private val LocalResponse.isSuccessful: Boolean
    get() = code in SUCCESS_CODE_START..SUCCESS_CODE_END

private fun LocalResponse.toResponseBody(): ResponseBody {
    val mediaType = contentType?.toMediaTypeOrNull()
    return stream
        ?.source()
        ?.buffer()
        ?.asResponseBody(contentType = mediaType)
        ?: body.orEmpty().toResponseBody(contentType = mediaType)
}

private fun Request.toLocalRequest(): LocalRequest {
    val requestBody = body
    return LocalRequest(
        method = method,
        url = url.toString(),
        headers = headersWithContentType(),
        readBody = { Buffer().also { requestBody?.writeTo(it) }.readByteArray() },
        writeBodyTo = { output ->
            val sink = output.sink().buffer()
            requestBody?.writeTo(sink)
            sink.flush()
        },
    )
}

/**
 * The headers of the request, with the media type of its body, which OkHttp only adds to them
 * after the interceptors of the app.
 */
private fun Request.headersWithContentType(): Map<String, String> {
    val contentType = body?.contentType() ?: return headers.toMap()
    return headers
        .toMap()
        .filterKeys { !it.equals(HEADER_KEY_CONTENT_TYPE, ignoreCase = true) }
        .plus(HEADER_KEY_CONTENT_TYPE to contentType.toString())
}
