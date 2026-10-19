package com.bitwarden.network.local

import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

private const val HTTP_BAD_REQUEST: Int = 400

/**
 * Create how a request fails that the local vault refused with `400 Bad Request` and the
 * given [message].
 */
fun createMockBadRequestThrowable(
    message: String = "Invalid master password.",
): Throwable =
    HttpException(
        Response.error<Unit>(
            HTTP_BAD_REQUEST,
            """{"message":"$message","validationErrors":null,"object":"error"}""".toResponseBody(),
        ),
    )
