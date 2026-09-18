package com.coroutinefabric

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Coalesced strategy tests, per docs/CoroutineCoordinator-Test-Cases-v1.1.md section 1
 * (UT-C01 .. UT-C06) plus owning-scope cancellation and failure cases beyond the catalog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineCoordinatorCoalescedTest {

    // UT-C01: a single submission executes.
    @Test
    fun `a single coalesced submission executes immediately`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<String>()
        try {
            val executed = mutableListOf<String>()
            coordinator.launchCoalesced(key, "A") { executed.add(it) }
            advanceUntilIdle()

            assertEquals(listOf("A"), executed)
            assertCoalescedKeyIdle(coordinator, key, "probe")
        } finally {
            scope.cancel()
        }
    }

    // UT-C02: a pending submission executes after the running one (single-pending form).
    @Test
    fun `a pending submission executes after the running one`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<Int>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<Int>()
            coordinator.launchCoalesced(key, 1) { order.add(it); aDone.await() }
            runCurrent()
            coordinator.launchCoalesced(key, 2) { order.add(it) }
            runCurrent()

            assertEquals(listOf(1), order)

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(1, 2), order)
            assertCoalescedKeyIdle(coordinator, key, 0)
        } finally {
            scope.cancel()
        }
    }

    // UT-C02 + UT-C03: only the latest pending executes (B/C never do) and the running
    // execution is not cancelled by the new submissions.
    @Test
    fun `only the latest pending executes and the running execution is not cancelled`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<Int>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<Int>()
            var aCompleted = false
            coordinator.launchCoalesced(key, 1) {
                order.add(it)
                aDone.await()
                aCompleted = true
            }
            runCurrent()
            coordinator.launchCoalesced(key, 2) { order.add(it) }
            coordinator.launchCoalesced(key, 3) { order.add(it) }
            coordinator.launchCoalesced(key, 4) { order.add(it) }
            runCurrent()

            assertEquals(listOf(1), order)

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals("B and C never execute; only the first and the latest do", listOf(1, 4), order)
            assertTrue("the running execution was not cancelled by the new submissions", aCompleted)
            assertCoalescedKeyIdle(coordinator, key, 0)
        } finally {
            scope.cancel()
        }
    }

    // Coalesced executions never overlap (Layer 1 invariant, beyond the catalog).
    @Test
    fun `coalesced executions never overlap`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<Int>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val running = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)
            coordinator.launchCoalesced(key, 1) {
                val c = running.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, c) }
                aDone.await()
                running.decrementAndGet()
            }
            runCurrent()
            coordinator.launchCoalesced(key, 2) {
                val c = running.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, c) }
                running.decrementAndGet()
            }
            aDone.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, maxConcurrent.get())
        } finally {
            scope.cancel()
        }
    }

    // UT-C04: the submission uses the submission-time snapshot, not later mutations.
    @Test
    fun `the latest pending executes with its submission-time snapshot`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<MutableSearch>()
        val aDone = CompletableDeferred<Unit>()
        val original = MutableSearch("q1", listOf("f1"))
        val snap = { source: MutableSearch -> MutableSearch(source.query, source.filters) }
        try {
            val seen = mutableListOf<String>()
            coordinator.launchCoalesced(key, original, snapshot = snap) {
                seen.add(it.query)
                aDone.await()
            }
            runCurrent()
            original.query = "q2"
            coordinator.launchCoalesced(key, original, snapshot = snap) { seen.add(it.query) }
            original.query = "q3"
            coordinator.launchCoalesced(key, original, snapshot = snap) { seen.add(it.query) }

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("q1", "q3"), seen)
            assertCoalescedKeyIdle(coordinator, key, MutableSearch("probe", emptyList()))
        } finally {
            scope.cancel()
        }
    }

    // UT-C04: snapshot is invoked once per submission at submission time, even when superseded.
    @Test
    fun `snapshot is invoked once per submission at submission time, even when superseded`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<MutableSearch>()
        val aDone = CompletableDeferred<Unit>()
        val original = MutableSearch("q", emptyList())
        try {
            val calls = AtomicInteger(0)
            val snap = { source: MutableSearch ->
                calls.incrementAndGet()
                MutableSearch(source.query, source.filters)
            }
            coordinator.launchCoalesced(key, original, snapshot = snap) { aDone.await() }
            runCurrent()
            coordinator.launchCoalesced(key, original, snapshot = snap) { }
            coordinator.launchCoalesced(key, original, snapshot = snap) { }
            runCurrent()

            assertEquals("every submission forms its snapshot immediately", 3, calls.get())

            aDone.complete(Unit)
            advanceUntilIdle()
            assertCoalescedKeyIdle(coordinator, key, MutableSearch("probe", emptyList()))
        } finally {
            scope.cancel()
        }
    }

    // UT-C04: mutable input is frozen at submit time, mutations after submit are not observed.
    @Test
    fun `mutable input is frozen at submit time, mutations after submit are not observed`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<MutableSearch>()
        try {
            val original = MutableSearch("initial", listOf("f1"))
            val received = AtomicReference<MutableSearch>()

            coordinator.launchCoalesced(
                key,
                original,
                snapshot = { MutableSearch(it.query, it.filters) },
            ) { received.set(it) }

            original.query = "mutated"
            original.filters.add("f2")

            advanceUntilIdle()

            val snap = received.get()
            assertNotSame(original, snap)
            assertEquals("initial", snap.query)
            assertEquals(listOf("f1"), snap.filters)
        } finally {
            scope.cancel()
        }
    }

    // UT-C04: snapshot is frozen even when the pending submission is superseded.
    @Test
    fun `snapshot is frozen even when the pending submission is superseded`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<MutableSearch>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val original = MutableSearch("initial", listOf("f1"))
            val received = AtomicReference<MutableSearch>()

            coordinator.launchCoalesced(key, original, snapshot = { MutableSearch(it.query, it.filters) }) {
                aDone.await()
            }
            runCurrent()

            original.query = "mutated"
            coordinator.launchCoalesced(key, original, snapshot = { MutableSearch(it.query, it.filters) }) {
                received.set(it)
            }

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals("mutated", received.get()?.query)
        } finally {
            scope.cancel()
        }
    }

    // Additional beyond the v1.1 catalog: default snapshot passes immutable values through.
    @Test
    fun `default snapshot passes immutable values through unchanged`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<String>()
        try {
            val received = AtomicReference<String>()
            coordinator.launchCoalesced(key, "hello") { received.set(it) }
            advanceUntilIdle()
            assertEquals("hello", received.get())
        } finally {
            scope.cancel()
        }
    }

    // UT-C05 (commitment point): a promoted pending submission cannot be replaced.
    // A running; B/C/D pending (latest = D); A completes -> D promoted to running (committed);
    // E arrives -> A -> D -> E, never A -> E.
    @Test
    fun `a promoted pending submission cannot be replaced`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<Int>()
        val aDone = CompletableDeferred<Unit>()
        val bDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<Int>()
            coordinator.launchCoalesced(key, 1) { order.add(it); aDone.await() }
            runCurrent()
            coordinator.launchCoalesced(key, 2) { order.add(it); bDone.await() }
            runCurrent()

            aDone.complete(Unit)
            runCurrent()
            assertEquals("D was promoted to running: it is committed and runs", listOf(1, 2), order)

            coordinator.launchCoalesced(key, 3) { order.add(it) }
            bDone.complete(Unit)
            advanceUntilIdle()

            assertEquals("A -> D -> E, never A -> E", listOf(1, 2, 3), order)
            assertCoalescedKeyIdle(coordinator, key, 0)
        } finally {
            scope.cancel()
        }
    }

    // UT-C06: scope cancellation drops the latest pending.
    @Test
    fun `scope cancellation stops the latest pending submission from starting`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<Int>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<Int>()
            coordinator.launchCoalesced(key, 1) { order.add(it); aDone.await() }
            runCurrent()
            coordinator.launchCoalesced(key, 4) { order.add(it) }
            runCurrent()

            scope.cancel()
            advanceUntilIdle()

            assertEquals("the latest pending does not execute after the scope is cancelled", listOf(1), order)
        } finally {
            scope.cancel()
        }
    }

    // Failure under a supervisor scope: the pending one continues after the failure.
    @Test
    fun `a failing execution under a supervisor scope is followed by the pending one`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<Int>()
        try {
            val order = mutableListOf<Int>()
            coordinator.launchCoalesced(key, 1) { order.add(it); throw IllegalStateException("boom") }
            coordinator.launchCoalesced(key, 2) { order.add(it) }
            advanceUntilIdle()

            assertEquals(listOf(1, 2), order)
            assertTrue(captured.get() is IllegalStateException)
            assertCoalescedKeyIdle(coordinator, key, 0)
        } finally {
            scope.cancel()
        }
    }

    // Failure under a regular Job scope: the latest pending is aborted with the scope.
    @Test
    fun `a failing execution under a regular job scope aborts the latest pending`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + Job() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<Int>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<Int>()
            coordinator.launchCoalesced(key, 1) { order.add(it); aDone.await(); throw IllegalStateException("boom") }
            runCurrent()
            coordinator.launchCoalesced(key, 2) { order.add(it) }
            coordinator.launchCoalesced(key, 3) { order.add(it) }
            runCurrent()

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals("the latest pending is aborted with the scope", listOf(1), order)
            assertTrue(captured.get() is IllegalStateException)
            assertTrue("the regular parent scope is cancelled", scope.coroutineContext[Job]!!.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    // Cancellation under a supervisor scope: the pending one continues, no failure.
    @Test
    fun `a cancelled execution under a supervisor scope is not a failure and the pending continues`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<Int>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<Int>()
            coordinator.launchCoalesced(key, 1) { order.add(it); aDone.await(); throw CancellationException("user cancel") }
            runCurrent()
            coordinator.launchCoalesced(key, 2) { order.add(it) }
            runCurrent()

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(1, 2), order)
            assertNull("cancellation is not a failure: the scope handler is not invoked", captured.get())
            assertCoalescedKeyIdle(coordinator, key, 0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `launchCoalesced on a cancelled scope registers nothing and does not snapshot`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.coalesced<String>()
        try {
            val calls = AtomicInteger(0)
            val order = mutableListOf<String>()
            scope.cancel()
            coordinator.launchCoalesced(
                key,
                "A",
                snapshot = { calls.incrementAndGet(); it },
            ) { order.add(it) }
            advanceUntilIdle()

            assertEquals(0, calls.get())
            assertTrue(order.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
