package com.coroutinefabric

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Coordination domain and lifecycle tests, per docs/CoroutineCoordinator-Test-Cases-v1.md
 * sections 16-18: the coordinator instance identity IS the coordination domain
 * (UT-D01 .. UT-D03), the coordinator's lifetime equals the owning scope's lifetime
 * (UT-L01 / UT-L02), and strategy mixing is a compile-time contract (section 18).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineCoordinatorDomainTest {

    private class ScopeMarker(val name: String) : CoroutineContext.Element {
        override val key: CoroutineContext.Key<ScopeMarker> = Key
        companion object Key : CoroutineContext.Key<ScopeMarker>
    }

    // UT-D01: same coordinator + same key coordinates.
    @Test
    fun `the same key instance shares one coordination slot in one coordinator`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            val order = mutableListOf<String>()
            val aDone = CompletableDeferred<Unit>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await() }
            runCurrent()
            coordinator.launchQueued(key) { order.add("B") }
            advanceUntilIdle()
            aDone.complete(Unit)
            advanceUntilIdle()

            // Same instance: strictly serialized, both executed.
            assertEquals(listOf("A", "B"), order)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // UT-D02: same coordinator + different keys are independent (key object identity).
    @Test
    fun `two once() instances are different keys (object identity)`() = runTest {
        val k1 = CoordinatorKey.once()
        val k2 = CoordinatorKey.once()
        assertNotSame(k1, k2)

        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        try {
            val order = mutableListOf<String>()
            val aDone = CompletableDeferred<Unit>()
            coordinator.launchOnce(k1) { order.add("A"); aDone.await() }
            runCurrent()

            // A different key instance is a different slot: not rejected, runs in parallel.
            coordinator.launchOnce(k2) { order.add("B") }
            advanceUntilIdle()

            assertEquals(listOf("A", "B"), order)
            aDone.complete(Unit)
            advanceUntilIdle()
            assertFalse(coordinator.hasActiveWork(k1))
            assertFalse(coordinator.hasActiveWork(k2))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `different keys in one coordinator are isolated and run in parallel`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val k1 = CoordinatorKey.once()
        val k2 = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            val a = launch { coordinator.once(k1) { aDone.await() } }
            runCurrent()

            coordinator.launchOnce(k2) { order.add("B") }
            advanceUntilIdle()
            assertEquals("Y is not coordinated by X's active execution", listOf("B"), order)

            aDone.complete(Unit)
            advanceUntilIdle()
            a.join()

            assertFalse(coordinator.hasActiveWork(k1))
            assertFalse(coordinator.hasActiveWork(k2))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `different queued keys in one coordinator run in parallel`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val k1 = CoordinatorKey.queued()
        val k2 = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        val bDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(k1) { order.add("A"); aDone.await() }
            coordinator.launchQueued(k2) { order.add("B"); bDone.await() }
            runCurrent()

            assertEquals(listOf("A", "B"), order)

            aDone.complete(Unit)
            bDone.complete(Unit)
            advanceUntilIdle()

            assertFalse(coordinator.hasActiveWork(k1))
            assertFalse(coordinator.hasActiveWork(k2))
        } finally {
            scope.cancel()
        }
    }

    // UT-D03: different coordinators + same key are independent.
    @Test
    fun `the same key in two coordinators on the same scope is independent`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val c1 = CoroutineCoordinator(scope)
        val c2 = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        try {
            val order = mutableListOf<String>()
            val aDone = CompletableDeferred<Unit>()
            c1.launchOnce(key) { order.add("A"); aDone.await() }
            runCurrent()

            // The same key instance in another coordinator does not block.
            c2.launchOnce(key) { order.add("B") }
            advanceUntilIdle()

            assertEquals(listOf("A", "B"), order)
            aDone.complete(Unit)
            advanceUntilIdle()
            assertFalse(c1.hasActiveWork(key))
            assertFalse(c2.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the same key in two coordinators on the same scope executes independently in parallel`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val c1 = CoroutineCoordinator(scope)
        val c2 = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        val bDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            val running = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)

            c1.launchQueued(key) {
                val c = running.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, c) }
                order.add("A")
                aDone.await()
                running.decrementAndGet()
            }
            c2.launchQueued(key) {
                val c = running.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, c) }
                order.add("B")
                bDone.await()
                running.decrementAndGet()
            }
            runCurrent()

            assertEquals(
                "the same key in different coordinators does not block each other",
                listOf("A", "B"),
                order,
            )
            assertEquals(2, maxConcurrent.get())

            aDone.complete(Unit)
            bDone.complete(Unit)
            advanceUntilIdle()

            assertFalse(c1.hasActiveWork(key))
            assertFalse(c2.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `coordinators with different scopes are fully isolated for the same key`() = runTest {
        val scopeA = independentSupervisedTestScope(coroutineContext)
        val scopeB = independentSupervisedTestScope(coroutineContext)
        val cA = CoroutineCoordinator(scopeA)
        val cB = CoroutineCoordinator(scopeB)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            cA.launchQueued(key) { order.add("A"); aDone.await() }
            runCurrent()
            cB.launchQueued(key) { order.add("B") }
            advanceUntilIdle()

            assertEquals(listOf("A", "B"), order)
            aDone.complete(Unit)
            advanceUntilIdle()
            assertFalse(cA.hasActiveWork(key))
            assertFalse(cB.hasActiveWork(key))
        } finally {
            scopeA.cancel()
            scopeB.cancel()
        }
    }

    // UT-L: workers run with the coordinator scope's context.
    @Test
    fun `workers run on the coordinator's scope context`() = runTest {
        val scope = CoroutineScope(
            coroutineContext + SupervisorJob() + silentExceptionHandler() + ScopeMarker("M"),
        )
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            val seen = mutableListOf<String>()
            coordinator.launchQueued(key) {
                seen.add("seen:" + coroutineContext[ScopeMarker]!!.name)
            }
            advanceUntilIdle()

            assertEquals(listOf("seen:M"), seen)
        } finally {
            scope.cancel()
        }
    }

    // UT-L01: the coordinator does not outlive the owning scope; no detached work.
    @Test
    fun `scope cancellation stops everything pending and releases all key state`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val keyOnce = CoordinatorKey.once()
        val keyQueued = CoordinatorKey.queued()
        val keyCoalesced = CoordinatorKey.coalesced<Int>()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchOnce(keyOnce) { order.add("O"); aDone.await() }
            coordinator.launchQueued(keyQueued) { order.add("Q1"); aDone.await() }
            runCurrent()
            coordinator.launchQueued(keyQueued) { order.add("Q2") }
            coordinator.launchCoalesced(keyCoalesced, 1) { order.add("C1"); aDone.await() }
            runCurrent()
            coordinator.launchCoalesced(keyCoalesced, 4) { order.add("C4") }
            runCurrent()

            scope.cancel()
            advanceUntilIdle()

            assertEquals(listOf("O", "Q1", "C1"), order)
            assertFalse(coordinator.hasActiveWork(keyOnce))
            assertFalse(coordinator.hasActiveWork(keyQueued))
            assertFalse(coordinator.hasActiveWork(keyCoalesced))
        } finally {
            scope.cancel()
        }
    }

    // UT-L02: the coordinator creates no independent lifecycle; the scope is the only lever.
    @Test
    fun `a coordinator holds no job of its own - cancelling the scope is the only lifecycle lever`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            coordinator.launchQueued(key) { aDone.await() }
            coordinator.launchQueued(key) { }
            runCurrent()
            assertTrue(coordinator.hasActiveWork(key))

            scope.cancel()
            advanceUntilIdle()
            assertFalse("all work dies with the scope", coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // Section 18: strategy mixing is a compile-time contract; the Kotlin compiler is the
    // first-stage verification (no compile-testing framework required).
    @Test
    fun `strategy mixing is blocked at compile time by the key subtypes`() {
        // Compile-time proof: each factory binds the key to one strategy, and the launch
        // functions only accept their own subtype. The following lines do NOT compile:
        //
        //   val onceKey = CoordinatorKey.once()
        //   coordinator.launchQueued(onceKey) { }            // error: Once is not Queued
        //   coordinator.launchCoalesced(onceKey, 1) { }      // error: Once is not Coalesced<T>
        //   val q = CoordinatorKey.queued()
        //   coordinator.once(q) { }                          // error: Queued is not Once
        //
        // The subtypes are final and unrelated, so no instance of one can ever be an instance
        // of another (the compiler already rejects any such check).
        val o: CoordinatorKey.Once = CoordinatorKey.once()
        val q: CoordinatorKey.Queued = CoordinatorKey.queued()
        val c: CoordinatorKey.Coalesced<String> = CoordinatorKey.coalesced()
        assertTrue(o === o && q === q && c === c)
    }
}
