package com.bitwarden.network.local

import com.bitwarden.network.util.NetworkErrorCode
import retrofit2.HttpException

/**
 * Returns `true` if this is how a request failed that the local vault refused with
 * `400 Bad Request`, which is how it refuses a request that it cannot do, such as one that proves
 * the master password with another hash than the one that it knows the master password by.
 */
fun Throwable.isBadRequest(): Boolean =
    this is HttpException && code() == NetworkErrorCode.BAD_REQUEST.code
