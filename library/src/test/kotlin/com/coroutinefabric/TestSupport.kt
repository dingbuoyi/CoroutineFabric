package com.coroutinefabric

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertTrue

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

/**
 * Asserts via observable behavior only that [key] is idle: a new execution submitted to it is
 * accepted and runs to completion. The coordinator's internal state is never inspected.
 * Must be used while the coordinator's scope is still active.
 */
internal fun TestScope.assertOnceKeyIdle(coordinator: CoroutineCoordinator, key: CoordinatorKey.Once) {
    val probe = CompletableDeferred<Unit>()
    coordinator.launchOnce(key) { probe.complete(Unit) }
    advanceUntilIdle()
    assertTrue("the key is idle: a new Once execution is accepted and runs", probe.isCompleted)
}

/** Asserts via observable behavior only that [key] is idle (see [assertOnceKeyIdle]). */
internal fun TestScope.assertQueuedKeyIdle(coordinator: CoroutineCoordinator, key: CoordinatorKey.Queued) {
    val probe = CompletableDeferred<Unit>()
    coordinator.launchQueued(key) { probe.complete(Unit) }
    advanceUntilIdle()
    assertTrue("the key is idle: a new Queued execution is accepted and runs", probe.isCompleted)
}

/** Asserts via observable behavior only that [key] is idle (see [assertOnceKeyIdle]). */
internal fun <T> TestScope.assertCoalescedKeyIdle(
    coordinator: CoroutineCoordinator,
    key: CoordinatorKey.Coalesced<T>,
    value: T,
) {
    val probe = CompletableDeferred<Unit>()
    coordinator.launchCoalesced(key, value) { probe.complete(Unit) }
    advanceUntilIdle()
    assertTrue("the key is idle: a new Coalesced execution is accepted and runs", probe.isCompleted)
}
