package com.coroutinefabric

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Internal concurrency robustness tests for CoroutineCoordinator.
 *
 * Cross-thread submission safety is not part of the public API contract.
 *
 * This file holds two layers: deterministic virtual-time tests that pin
 * state-transition boundaries (finish <-> submit, promotion, cancellation),
 * and real-thread stress tests that protect the current implementation
 * against internal races introduced by its synchronization/state-transition
 * model.
 *
 * Passing the real-thread stress tests must not be interpreted as expanding
 * the public thread-safety contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineCoordinatorRaceConditionTest {

    @Test
    fun `submission arriving when the running task completes is not lost`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await() }
            runCurrent()

            // Complete A and submit B before A's worker resumes (the completion boundary race).
            aDone.complete(Unit)
            coordinator.launchQueued(key) { order.add("B") }
            advanceUntilIdle()

            assertEquals(listOf("A", "B"), order)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // Concurrent submitters: coroutine creation order is NOT the Coordinator submission order,
    // so no execution-order assertion is made here. The invariants that must hold in any
    // interleaving are: no lost submission, no duplicate execution, no overlap. Strict FIFO
    // is a contract verified by the deterministic test in CoroutineCoordinatorQueuedTest.
    @Test
    fun `concurrent submitters on the same key each execute exactly once, never in parallel`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            val executed = mutableListOf<Int>()
            val activeExecutions = AtomicInteger(0)
            val maxActiveExecutions = AtomicInteger(0)
            val n = 32
            (0 until n).map { i ->
                launch {
                    coordinator.launchQueued(key) {
                        val c = activeExecutions.incrementAndGet()
                        maxActiveExecutions.updateAndGet { maxOf(it, c) }
                        executed.add(i)
                        activeExecutions.decrementAndGet()
                    }
                }
            }
            advanceUntilIdle()

            assertEquals("no lost submission", n, executed.size)
            assertEquals("no duplicate execution", (0 until n).toSet(), executed.toSet())
            assertEquals("same-key executions never overlap", 1, maxActiveExecutions.get())
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // Concurrent submitters: which pending submission ends up last depends on the actual
    // Coordinator submission order, not on coroutine creation order, so the winner is only
    // required to be one of B0..B15. Latest-wins is a contract verified by the
    // deterministic B -> C -> D test in CoroutineCoordinatorCoalescedTest.
    @Test
    fun `concurrent coalesced submitters keep exactly one pending winner`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<String>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            var aCompleted = false
            coordinator.launchCoalesced(key, "A") {
                order.add(it)
                aDone.await()
                aCompleted = true
            }
            runCurrent()
            (0 until 16).map { i ->
                launch { coordinator.launchCoalesced(key, "B$i") { order.add(it) } }
            }
            advanceUntilIdle()

            assertEquals("only A ran while the submitters coalesced", listOf("A"), order)

            aDone.complete(Unit)
            advanceUntilIdle()

            assertTrue("the running execution was not cancelled by the new submissions", aCompleted)
            assertEquals("A plus exactly one pending winner", 2, order.size)
            assertEquals("A ran first", "A", order.first())
            assertTrue(
                "the winner is one of the concurrent pending submissions",
                (0 until 16).any { order.last() == "B$it" },
            )
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `real threads - concurrent launchOnce starts exactly one execution`() {
        repeat(20) {
            runBlocking {
                val job = Job()
                val scope = CoroutineScope(Dispatchers.Default + job)
                try {
                    val coordinator = CoroutineCoordinator(scope)
                    val key = CoordinatorKey.once()
                    val n = 32
                    val executions = AtomicInteger(0)
                    val running = AtomicInteger(0)
                    val maxConcurrent = AtomicInteger(0)
                    val barrier = CompletableDeferred<Unit>()
                    val gate = CompletableDeferred<Unit>()
                    val done = CompletableDeferred<Unit>()

                    val submitters = (0 until n).map {
                        scope.launch {
                            barrier.await()
                            coordinator.launchOnce(key) {
                                val c = running.incrementAndGet()
                                maxConcurrent.updateAndGet { maxOf(it, c) }
                                executions.incrementAndGet()
                                running.decrementAndGet()
                                gate.await()
                                done.complete(Unit)
                            }
                        }
                    }
                    barrier.complete(Unit)
                    submitters.joinAll()
                    // All submitters have entered; keep the accepted execution running so no
                    // late submitter can start a second one.
                    gate.complete(Unit)
                    done.await()

                    assertEquals("exactly one execution started", 1, executions.get())
                    assertEquals(1, maxConcurrent.get())
                } finally {
                    job.cancel()
                }
            }
        }
    }

    @Test
    fun `real threads - queued submissions are all executed, never in parallel`() {
        repeat(10) {
            runBlocking {
                val job = Job()
                val scope = CoroutineScope(Dispatchers.Default + job)
                try {
                    val coordinator = CoroutineCoordinator(scope)
                    val key = CoordinatorKey.queued()
                    val n = 32
                    val executions = AtomicInteger(0)
                    val running = AtomicInteger(0)
                    val maxConcurrent = AtomicInteger(0)
                    val barrier = CompletableDeferred<Unit>()
                    val latch = CountDownLatch(n)

                    (0 until n).map {
                        scope.launch {
                            barrier.await()
                            coordinator.launchQueued(key) {
                                val c = running.incrementAndGet()
                                maxConcurrent.updateAndGet { maxOf(it, c) }
                                executions.incrementAndGet()
                                running.decrementAndGet()
                                latch.countDown()
                            }
                        }
                    }
                    barrier.complete(Unit)
                    latch.await()

                    assertEquals(n, executions.get())
                    assertEquals(1, maxConcurrent.get())
                } finally {
                    job.cancel()
                }
            }
        }
    }

    @Test
    fun `real threads - completion and submission race never loses a submission`() {
        repeat(20) {
            runBlocking {
                val job = Job()
                val scope = CoroutineScope(Dispatchers.Default + job)
                try {
                    val coordinator = CoroutineCoordinator(scope)
                    val key = CoordinatorKey.queued()
                    val n = 8
                    val executed = Collections.synchronizedList(mutableListOf<String>())
                    val running = AtomicInteger(0)
                    val maxConcurrent = AtomicInteger(0)
                    val started = CompletableDeferred<Unit>()
                    val gate = CompletableDeferred<Unit>()
                    val latch = CountDownLatch(n)

                    coordinator.launchQueued(key) {
                        started.complete(Unit)
                        val c = running.incrementAndGet()
                        maxConcurrent.updateAndGet { maxOf(it, c) }
                        executed.add("A")
                        gate.await()
                        running.decrementAndGet()
                    }
                    started.await()

                    (0 until n).map { i ->
                        scope.launch {
                            coordinator.launchQueued(key) {
                                val c = running.incrementAndGet()
                                maxConcurrent.updateAndGet { maxOf(it, c) }
                                executed.add("B$i")
                                running.decrementAndGet()
                                latch.countDown()
                            }
                        }
                    }
                    scope.launch { gate.complete(Unit) }
                    latch.await()

                    assertEquals(n + 1, executed.size)
                    assertTrue(executed.contains("A"))
                    (0 until n).forEach { assertTrue(executed.contains("B$it")) }
                    assertEquals(1, maxConcurrent.get())
                } finally {
                    job.cancel()
                }
            }
        }
    }

    @Test
    fun `real threads - concurrent once calls never overlap, joiners only wait`() {
        // Under a real-thread storm a submitter may arrive after the current execution already
        // finished; it then legitimately starts the next execution. The invariants that must
        // hold in any interleaving are: executions never overlap, and a block only ever runs as
        // the accepted execution of its round (the strict "only the first runs, the rest join"
        // semantics are covered deterministically in CoroutineCoordinatorOnceTest).
        repeat(10) {
            runBlocking {
                val job = Job()
                val scope = CoroutineScope(Dispatchers.Default + job)
                try {
                    val coordinator = CoroutineCoordinator(scope)
                    val key = CoordinatorKey.once()
                    val n = 32
                    val executions = AtomicInteger(0)
                    val running = AtomicInteger(0)
                    val maxConcurrent = AtomicInteger(0)
                    val barrier = CompletableDeferred<Unit>()
                    val latch = CountDownLatch(n)

                    (0 until n).map {
                        scope.launch {
                            barrier.await()
                            try {
                                coordinator.once(key) {
                                    val c = running.incrementAndGet()
                                    maxConcurrent.updateAndGet { maxOf(it, c) }
                                    executions.incrementAndGet()
                                    Thread.sleep(5)
                                    running.decrementAndGet()
                                }
                            } finally {
                                latch.countDown()
                            }
                        }
                    }
                    barrier.complete(Unit)
                    latch.await()

                    assertTrue("at least one execution ran", executions.get() >= 1)
                    assertEquals("executions never overlap", 1, maxConcurrent.get())
                } finally {
                    job.cancel()
                }
            }
        }
    }
}
