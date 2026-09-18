package com.coroutinefabric

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates same-key executions on one [CoroutineScope].
 *
 * A coordinator instance defines the coordination domain: the registry, per-key runtime state,
 * queues and pending submissions are its private implementation details. It is not a
 * [CoroutineScope]: it creates no Job of its own, owns no lifecycle of its own and provides no
 * independent cancel/close. All executions run as children of [scope] with [scope]'s context;
 * when [scope] is cancelled everything it started is cancelled and no work is left behind.
 *
 * The state of one [CoordinatorKey] inside this coordinator is fully independent from the same
 * key inside any other coordinator.
 *
 * Thread safety for concurrent cross-thread submissions is not part of the public API
 * contract: the internal synchronization is an implementation detail and must not be relied
 * upon by callers.
 */
class CoroutineCoordinator(private val scope: CoroutineScope) {

    private val lock = Any()
    private val states = HashMap<CoordinatorKey, KeyState>()

    /**
     * Fast-path liveness check. This is not a correctness guarantee: the parent scope (and its
     * Job) remains the source of truth for cancellation, and a submission accepted right after
     * a `true` result may still be cancelled before its execution starts.
     */
    internal fun isActive(): Boolean = scope.isActive

    /** Test/inspection hook: whether [key] currently has running, queued or pending work. */
    internal fun hasActiveWork(key: CoordinatorKey): Boolean = synchronized(lock) {
        states.containsKey(key)
    }

    /**
     * Registers a Once submission and returns the submission whose completion bounds the current
     * execution: the newly registered submission when the key was idle, otherwise the running
     * submission the call joins.
     */
    internal fun submitOnce(key: CoordinatorKey.Once, block: suspend CoroutineScope.() -> Unit): Submission {
        val submission: Submission
        synchronized(lock) {
            val state = states[key] as? KeyState.Once
            val running = state?.running
            if (running != null) return running
            val st = state ?: KeyState.Once().also { states[key] = it }
            submission = Submission(block)
            st.running = submission
        }
        start(key, submission)
        return submission
    }

    /**
     * Registers a Queued submission: started immediately when the key is idle, appended to the
     * key's FIFO queue otherwise. Returns the registered submission.
     */
    internal fun submitQueued(key: CoordinatorKey.Queued, block: suspend CoroutineScope.() -> Unit): Submission {
        val submission = Submission(block)
        val shouldStart: Boolean = synchronized(lock) {
            val state = states.getOrPut(key) { KeyState.Queued() } as KeyState.Queued
            if (state.running == null) {
                state.running = submission
                true
            } else {
                state.queue.addLast(submission)
                false
            }
        }
        if (shouldStart) start(key, submission)
        return submission
    }

    /**
     * Registers a Coalesced submission: started immediately when the key is idle, otherwise
     * replaces the previous pending submission, which is dropped.
     */
    internal fun submitCoalesced(key: CoordinatorKey.Coalesced<*>, block: suspend CoroutineScope.() -> Unit) {
        val submission = Submission(block)
        var toStart: Submission? = null
        var replaced: Submission? = null
        synchronized(lock) {
            val state = states.getOrPut(key) { KeyState.Coalesced() } as KeyState.Coalesced
            if (state.running == null) {
                state.running = submission
                toStart = submission
            } else {
                replaced = state.latest
                state.latest = submission
            }
        }
        // A replaced pending submission never executes: outside the lock it is terminalized so no
        // submission is ever left permanently incomplete and unreachable.
        replaced?.let { it.completion.completeExceptionally(submissionReplaced()) }
        toStart?.let { start(key, it) }
    }

    /**
     * Launches the worker for [submission] on the coordinator's scope. Always called outside
     * [lock]: the caller has already committed the submission to the running slot under the
     * lock, so concurrent submissions observe it as running and can no longer replace it.
     *
     * The job's completion callback is the single completion path for the execution: it runs
     * exactly once with the outcome (null on normal completion, the exception on failure, a
     * [CancellationException] on cancellation — including a worker cancelled before its body
     * ever started), completes [Submission.completion] with it and drives [finish]. The worker
     * body itself only runs the block; its exception (if any) is the job's completion cause and
     * also propagates to the owning scope's exception handler by structured concurrency.
     */
    private fun start(key: CoordinatorKey, submission: Submission) {
        val job = scope.launch {
            submission.block(this)
        }
        job.invokeOnCompletion { cause ->
            if (cause == null) submission.completion.complete(Unit)
            else submission.completion.completeExceptionally(cause)
            finish(key, submission)
        }
    }

    /**
     * The single cleanup/transition path for completion, failure and cancellation. Idempotent:
     * [KeyState.claim] releases the running slot only for the submission that actually occupied
     * it, so a repeated call for the same submission is a no-op.
     *
     * The state transition (claim, promotion, cleanup) happens under [lock]; the completion side
     * effects (waking the waiters of aborted submissions, launching the promoted submission)
     * happen outside it, after the state has been committed.
     */
    private fun finish(key: CoordinatorKey, submission: Submission) {
        var aborted: List<Submission>? = null
        val next: Submission? = synchronized(lock) {
            val state = states[key] ?: return@synchronized null
            if (!state.claim(submission)) return@synchronized null
            if (!scope.isActive) {
                // The scope is gone: detach everything still referenced (queue including its
                // head, pending latest) instead of launching workers that would be born dead.
                // Completing their gates (waking suspended waiters) happens outside the lock.
                states.remove(key)
                aborted = state.abortRemaining()
                return@synchronized null
            }
            val promoted = state.takeNext()
            if (promoted == null) states.remove(key)
            promoted
        }
        aborted?.forEach { it.completion.completeExceptionally(scopeCancelled()) }
        if (next != null) start(key, next)
    }

    /** Per-key runtime state, one shape per strategy. */
    private sealed class KeyState {

        var running: Submission? = null

        /** Removes [submission] from the running slot; false when it no longer occupies it. */
        fun claim(submission: Submission): Boolean {
            if (running !== submission) return false
            running = null
            return true
        }

        /**
         * Removes and returns the next submission to run, making it the running one. This
         * promotion is the commitment point: from the moment a submission becomes running it is
         * committed to execute and can no longer be replaced or dropped, even though its worker
         * coroutine has not started yet.
         */
        abstract fun takeNext(): Submission?

        /**
         * Detaches and returns every submission that will never execute, without touching their
         * completion gates. The caller completes the gates outside [lock].
         */
        abstract fun abortRemaining(): List<Submission>

        class Once : KeyState() {
            override fun takeNext(): Submission? = null

            override fun abortRemaining(): List<Submission> = emptyList()
        }

        class Queued : KeyState() {
            val queue = ArrayDeque<Submission>()

            override fun takeNext(): Submission? = queue.removeFirstOrNull()?.also { running = it }

            override fun abortRemaining(): List<Submission> {
                val remainder = queue.toList()
                queue.clear()
                return remainder
            }
        }

        class Coalesced : KeyState() {
            var latest: Submission? = null

            override fun takeNext(): Submission? = latest?.also {
                latest = null
                running = it
            }

            override fun abortRemaining(): List<Submission> {
                val pending = latest
                latest = null
                return if (pending == null) emptyList() else listOf(pending)
            }
        }
    }
}

private fun scopeCancelled() =
    CancellationException("The coordinator's scope was cancelled")

private fun submissionReplaced() =
    CancellationException("The submission was replaced by a newer one and will not execute")

/**
 * A single submission: the execution request registered with the coordinator, carrying its
 * execution block and the gate suspended callers await. Not every submission becomes an
 * execution: depending on the strategy, the coordinator may drop, join, queue or replace it.
 */
internal class Submission(internal val block: suspend CoroutineScope.() -> Unit) {

    val completion: CompletableDeferred<Unit> = CompletableDeferred()
}
