package com.rfsentinel.app.nav

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching] for code that may suspend: a failure is returned, but a cancelled coroutine stays
 * cancelled (the [CancellationException] is thrown on). With plain runCatching a request replaced by a
 * newer one - or abandoned when its card was closed - would land in the failure branch and show a
 * "no connection" message over the newer request's state.
 */
internal inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
