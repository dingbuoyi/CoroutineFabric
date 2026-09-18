package com.coroutinefabric

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Mutable input used to prove snapshots are frozen at submission time. */
class MutableSearch {
    var query: String
    val filters: MutableList<String>

    constructor(query: String, filters: List<String>) {
        this.query = query
        this.filters = filters.toMutableList()
    }
}

/** Exception handler that keeps test scopes from forwarding task failures to the test framework. */
internal fun silentExceptionHandler(): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, _ -> }

/** Exception handler that captures failures, for asserting on the original exception. */
internal fun capturingExceptionHandler(): Pair<CoroutineExceptionHandler, AtomicReference<Throwable>> {
    val captured = AtomicReference<Throwable>()
    return Pair(CoroutineExceptionHandler { _, e -> captured.set(e) }, captured)
}

/**
 * A coordinator scope for tests: it reuses the parent test dispatcher (shared
 * TestCoroutineScheduler) but installs an independent SupervisorJob, so a failing
 * execution does not cancel the test scope, and failures are swallowed instead of
 * being forwarded to the test framework.
 *
 * This is NOT SupervisorJob(parentJob): the job lifecycle is independent.
 */
internal fun independentSupervisedTestScope(parent: CoroutineContext): CoroutineScope =
    CoroutineScope(parent + SupervisorJob() + silentExceptionHandler())
