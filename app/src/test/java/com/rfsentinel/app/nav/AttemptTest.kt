package com.rfsentinel.app.nav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A request replaced by a newer one must not be reported as a failure. */
class AttemptTest {

    @Test
    fun aResultAndAnOrdinaryFailureAreReturned() {
        assertEquals(5, attempt { 5 }.getOrNull())
        val failed = attempt<Int> { throw java.io.IOException("offline") }
        assertTrue(failed.isFailure)
        assertEquals("offline", failed.exceptionOrNull()?.message)
    }

    @Test
    fun aCancelledCoroutineStaysCancelledAndNeverReachesTheFailureBranch() = runBlocking {
        var reachedFailure = false
        var finished = false
        val job = launch {
            attempt { delay(10_000) }.onFailure { reachedFailure = true }
            finished = true
        }
        delay(50)
        job.cancel(); job.join()
        assertFalse("cancellation is not a failure to report", reachedFailure)
        assertFalse("nothing after it runs", finished)
        assertTrue(job.isCancelled)
    }

    @Test
    fun runCatchingWouldHaveGotItWrong() = runBlocking {
        // The behaviour being avoided: plain runCatching turns cancellation into a failure and carries on.
        var reachedFailure = false
        val job = launch { runCatching { delay(10_000) }.onFailure { reachedFailure = it is CancellationException } }
        delay(50)
        job.cancel(); job.join()
        assertTrue(reachedFailure)
    }
}
