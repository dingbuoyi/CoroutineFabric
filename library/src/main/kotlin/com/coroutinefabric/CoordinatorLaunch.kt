package com.coroutinefabric

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Launches [block] for [key] only if no Once execution is active for the key; otherwise the
 * request is silently dropped and returns without waiting.
 *
 * The key is occupied from the moment the submission is accepted until the corresponding
 * coroutine completes normally, fails or is cancelled.
 *
 * Does nothing when the coordinator's scope is already cancelled.
 *
 * Use this for duplicate-click protection, one-time initialization and repeat-refresh
 * suppression.
 */
fun CoroutineCoordinator.launchOnce(
    key: CoordinatorKey.Once,
    block: suspend CoroutineScope.() -> Unit,
) {
    if (!isActive()) return
    submitOnce(key, block)
}

/**
 * Suspends until the Once execution for [key] has completed.
 *
 * If no execution for the key is active at submission time, this call runs [block] as the
 * execution and suspends until it completes. If an execution for the same key is already
 * active, the caller does not execute its own block and instead waits for the active execution
 * to complete: concurrent calls join the current execution. They never schedule or trigger any
 * additional execution — the Once strategy only ever runs the single active execution for the
 * key.
 *
 * The caller observes the outcome of the joined execution:
 * - returns normally when the execution completes normally,
 * - rethrows the execution's failure (same type and message; kotlinx.coroutines may attach a
 *   recovered stack trace, so the instance is not necessarily identical to the thrown one),
 * - throws [CancellationException] when the execution is cancelled (for example because the
 *   coordinator's scope was cancelled) or when the scope is already cancelled at submission
 *   time.
 */
suspend fun CoroutineCoordinator.once(
    key: CoordinatorKey.Once,
    block: suspend CoroutineScope.() -> Unit,
) {
    if (!isActive()) throw CancellationException("The coordinator's scope is already cancelled")
    submitOnce(key, block).completion.await()
}

/**
 * Launches [block] for [key], appending it to the key's FIFO queue.
 *
 * As long as the owning scope remains active, every accepted submission executes exactly once,
 * submissions for the same key never run in parallel, and the execution order follows the
 * submission order. If the owning scope is cancelled, the coordinator does not guarantee that
 * the remaining queued submissions will execute. Different keys run independently.
 *
 * Returns without waiting. Does nothing when the coordinator's scope is already cancelled.
 *
 * Use this for uploads, writes and other operations that must not be lost.
 */
fun CoroutineCoordinator.launchQueued(
    key: CoordinatorKey.Queued,
    block: suspend CoroutineScope.() -> Unit,
) {
    if (!isActive()) return
    submitQueued(key, block)
}

/**
 * Enqueues [block] for [key] and suspends until this submission's own execution completes.
 *
 * Like [launchQueued], every accepted submission executes exactly once, strictly FIFO and
 * without overlap, as long as the owning scope remains active; unlike it, the caller waits for
 * its own execution (not for the whole queue to drain).
 *
 * The caller observes the outcome of its own execution:
 * - returns normally when the execution completes normally,
 * - rethrows the execution's failure (same type and message; kotlinx.coroutines may attach a
 *   recovered stack trace, so the instance is not necessarily identical to the thrown one),
 * - throws [CancellationException] when the execution is cancelled (for example because the
 *   coordinator's scope was cancelled) or when the scope is already cancelled at submission
 *   time.
 */
suspend fun CoroutineCoordinator.queued(
    key: CoordinatorKey.Queued,
    block: suspend CoroutineScope.() -> Unit,
) {
    if (!isActive()) throw CancellationException("The coordinator's scope is already cancelled")
    submitQueued(key, block).completion.await()
}

/**
 * Launches [block] for [key] with coalescing semantics.
 *
 * - The currently running execution is never cancelled by a new submission.
 * - While an execution is running, at most one pending submission is kept; a new submission
 *   replaces the previous pending one, which is dropped.
 * - The latest pending submission executes after the running execution finishes, provided the
 *   owning scope remains active: with A running and B, C, D submitted in turn, the final
 *   executions are A then D.
 *
 * This is a serialized state update policy: the running operation is not cancelled, intermediate
 * pending values may be dropped, and the latest value must eventually execute. Typical uses:
 * - BLE / hardware control — a temperature set to 20, then 21, 22, 23, 24 in quick succession
 *   while the device is still applying 20, only needs the final executions 20 then 24;
 * - brightness / device state — 10, 20, 30, 40, 50 while 10 is in flight, only needs 10 then 50;
 * - slow persistence — state A is being written when B, C, D, E arrive, only A then E need
 *   to be written;
 * - remote state synchronization — the server is still syncing state A while the local state
 *   moves B, C, D, E; after A finishes, sync E.
 *
 * Unlike `Flow.debounce`, coalescing is not time-based: it keys off whether an execution is
 * running. The two solve different problems and can be composed (debounce first, then submit);
 * for raw input streams such as a search box, prefer Flow operators (`debounce`,
 * `distinctUntilChanged`, `collectLatest`) first.
 *
 * [value] is frozen through [snapshot] at submission time (the snapshot function is invoked
 * once per call, even when the submission is later replaced); the block only ever observes the
 * returned snapshot, never the mutable original.
 *
 * Returns without waiting. Does nothing when the coordinator's scope is already cancelled.
 * A suspend form is deliberately not provided: for a replaced submission the completion
 * boundary is not well defined.
 */
fun <T> CoroutineCoordinator.launchCoalesced(
    key: CoordinatorKey.Coalesced<T>,
    value: T,
    snapshot: (T) -> T = { it },
    block: suspend CoroutineScope.(T) -> Unit,
) {
    if (!isActive()) return
    val snap = snapshot(value)
    submitCoalesced(key) { block(snap) }
}
