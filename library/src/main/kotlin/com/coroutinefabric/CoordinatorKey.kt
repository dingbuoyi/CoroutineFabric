package com.coroutinefabric

/**
 * Lock/Mutex-style identity object for coordinated executions.
 *
 * A key carries no runtime state: all per-key state (running execution, queue, pending
 * submission) lives inside the [CoroutineCoordinator], so the same key instance used by
 * different coordinators is fully independent, and one key instance can be safely shared as a
 * long-lived `val` across the application.
 *
 * The coordination strategy is bound to the key at creation time by the factory used:
 *
 * - [once] creates a key usable only with [launchOnce] / [joinOnce]
 * - [queued] creates a key usable only with [launchQueued] / [joinQueued]
 * - [coalesced] creates a key usable only with [launchCoalesced]
 *
 * Passing a key to a launch function whose strategy does not match is a compile-time error,
 * so the three strategies can never be mixed for one key.
 */
sealed class CoordinatorKey private constructor() {

    /** Key of the Once strategy: at most one active execution; concurrent requests are dropped or join the active execution. */
    class Once private constructor() : CoordinatorKey() {
        companion object {
            internal fun create(): Once = Once()
        }
    }

    /** Key of the Queued strategy: every submission executes, strictly FIFO, never overlapping. */
    class Queued private constructor() : CoordinatorKey() {
        companion object {
            internal fun create(): Queued = Queued()
        }
    }

    /** Key of the Coalesced strategy: at most one pending submission, the latest one wins. */
    class Coalesced<T> private constructor() : CoordinatorKey() {
        companion object {
            internal fun <T> create(): Coalesced<T> = Coalesced()
        }
    }

    companion object {
        /** Creates a key for the Once strategy. */
        fun once(): Once = Once.create()

        /** Creates a key for the Queued strategy. */
        fun queued(): Queued = Queued.create()

        /** Creates a key for the Coalesced strategy, bound to the input type [T]. */
        fun <T> coalesced(): Coalesced<T> = Coalesced.create()
    }
}
