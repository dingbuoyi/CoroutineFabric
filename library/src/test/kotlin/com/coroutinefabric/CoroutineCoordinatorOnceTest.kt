package com.coroutinefabric

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Once strategy tests, per docs/CoroutineCoordinator-Test-Cases-v1.1.md section 1
 * (UT-O01 .. UT-O08) plus owning-scope cancellation and failure cases beyond the catalog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineCoordinatorOnceTest {

    // UT-O01: the first submission executes.
    @Test
    fun `the first launchOnce executes its block once`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        try {
            val order = mutableListOf<String>()
            coordinator.launchOnce(key) { order.add("A") }
            advanceUntilIdle()

            assertEquals(listOf("A"), order)
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // UT-O02: a duplicate submission is dropped.
    @Test
    fun `a duplicate launchOnce while the execution is active is dropped`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchOnce(key) { order.add("A"); aDone.await() }
            runCurrent()

            coordinator.launchOnce(key) { order.add("B") }
            runCurrent()
            assertEquals("the running execution blocks the second one", listOf("A"), order)

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals("the dropped submission never executes", listOf("A"), order)
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // UT-O02: multiple duplicate submissions are all dropped.
    @Test
    fun `multiple duplicate submissions while the execution is active are all dropped`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchOnce(key) { order.add("A"); aDone.await() }
            runCurrent()

            val submitters = (0 until 8).map {
                launch { coordinator.launchOnce(key) { order.add("X") } }
            }
            advanceUntilIdle()
            submitters.forEach { it.join() }

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals("B/C/D.. are all dropped while A is running", listOf("A"), order)
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // UT-O03: a new submission executes after the previous completion.
    // Once means "at most one ACTIVE execution", not "one execution per key lifetime".
    @Test
    fun `a new submission executes after the previous completion`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        try {
            val order = mutableListOf<String>()
            coordinator.launchOnce(key) { order.add("A") }
            advanceUntilIdle()
            coordinator.launchOnce(key) { order.add("B") }
            advanceUntilIdle()

            assertEquals(listOf("A", "B"), order)
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // Additional beyond the v1.1 catalog (UT-O01 fire-and-forget form): launchOnce does not
    // wait for the execution.
    @Test
    fun `launchOnce returns immediately while the execution stays active`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchOnce(key) { order.add("A"); started.complete(Unit); release.await() }
            runCurrent()

            assertTrue("A started", started.isCompleted)
            coordinator.launchOnce(key) { order.add("B") }
            runCurrent()

            assertEquals("A is still active after the call returned, so B is dropped", listOf("A"), order)

            release.complete(Unit)
            advanceUntilIdle()

            assertEquals("only A executed", listOf("A"), order)
        } finally {
            scope.cancel()
        }
    }

    // UT-O04: the first joinOnce caller executes the block and suspends until it completes.
    @Test
    fun `the first joinOnce caller executes the block and suspends until it completes`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            val returned = CompletableDeferred<Unit>()
            val caller = launch {
                coordinator.joinOnce(key) { order.add("A"); aDone.await() }
                returned.complete(Unit)
            }
            runCurrent()

            assertEquals(listOf("A"), order)
            assertFalse("the caller is still suspended while the execution runs", returned.isCompleted)

            aDone.complete(Unit)
            advanceUntilIdle()
            caller.join()

            assertTrue(returned.isCompleted)
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // UT-O05: a duplicate joinOnce caller joins the active execution; its own block never runs.
    @Test
    fun `a duplicate joinOnce caller joins the active execution and its block never runs`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchOnce(key) { order.add("A"); aDone.await() }
            runCurrent()

            val bError = CompletableDeferred<Throwable?>()
            val waiter = launch {
                coordinator.joinOnce(key) { order.add("X") }
                bError.complete(null)
            }
            runCurrent()
            assertEquals(listOf("A"), order)

            aDone.complete(Unit)
            advanceUntilIdle()
            waiter.join()

            assertEquals("the waiter's block never ran; only A executed", listOf("A"), order)
            assertNull("the joiner resumes normally once the active execution completes", bError.getCompleted())
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // UT-O06: multiple joiners join the same execution; none of their blocks runs.
    @Test
    fun `multiple joinOnce callers join the same execution and none of their blocks run`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            val a = launch { coordinator.joinOnce(key) { order.add("A"); aDone.await() } }
            runCurrent()
            val b = launch { coordinator.joinOnce(key) { order.add("B") } }
            val c = launch { coordinator.joinOnce(key) { order.add("C") } }
            val d = launch { coordinator.joinOnce(key) { order.add("D") } }
            runCurrent()

            assertEquals("only the first block executed", listOf("A"), order)
            assertFalse(b.isCompleted)
            assertFalse(c.isCompleted)
            assertFalse(d.isCompleted)

            aDone.complete(Unit)
            advanceUntilIdle()
            a.join()
            b.join()
            c.join()
            d.join()

            assertEquals("joiners never run their own block", listOf("A"), order)
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // UT-O07 (P0): cancelling one joiner does not cancel the shared execution.
    @Test
    fun `cancelling one joiner does not cancel the shared execution`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            var aCompleted = false
            val a = launch {
                coordinator.joinOnce(key) { order.add("A"); aDone.await(); aCompleted = true }
            }
            runCurrent()
            val b = launch { coordinator.joinOnce(key) { order.add("B") } }
            val c = launch { coordinator.joinOnce(key) { order.add("C") } }
            runCurrent()

            assertEquals("A is the active execution; B and C only join it", listOf("A"), order)

            b.cancel()
            b.join()
            runCurrent()

            assertFalse("the shared execution is still running, not cancelled", aCompleted)
            assertEquals(listOf("A"), order)

            aDone.complete(Unit)
            advanceUntilIdle()
            a.join()

            assertTrue("the shared execution ran to completion", aCompleted)
            assertEquals(listOf("A"), order)
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // UT-O07: cancelling one joiner does not affect the other joiners.
    @Test
    fun `cancelling one joiner does not affect the other joiners`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            val a = launch {
                coordinator.joinOnce(key) { order.add("A"); aDone.await() }
            }
            runCurrent()
            val bError = CompletableDeferred<Throwable>()
            val b = launch {
                bError.complete(runCatching { coordinator.joinOnce(key) { order.add("B") } }.exceptionOrNull()!!)
            }
            val cError = CompletableDeferred<Throwable?>()
            val c = launch {
                coordinator.joinOnce(key) { order.add("C") }
                cError.complete(null)
            }
            runCurrent()

            b.cancel()
            b.join()
            runCurrent()

            assertTrue(
                "joiner B observes only the cancellation of its own wait",
                bError.getCompleted() is CancellationException,
            )
            assertTrue("joiner C is still waiting for A", !c.isCompleted)

            aDone.complete(Unit)
            advanceUntilIdle()
            a.join()
            c.join()

            assertNull("joiner C resumes normally; B's cancellation did not propagate to C", cError.getCompleted())
            assertEquals("no joiner block ever ran", listOf("A"), order)
        } finally {
            scope.cancel()
        }
    }

    // UT-O08: an execution exception reaches the joiners (and the scope handler) and the key
    // state is cleaned up.
    @Test
    fun `an execution exception reaches every joinOnce caller joined to it`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val aDone = CompletableDeferred<Unit>()
        try {
            val aError = CompletableDeferred<Throwable>()
            val bError = CompletableDeferred<Throwable>()
            val a = launch {
                val e = runCatching {
                    coordinator.joinOnce(key) { aDone.await(); throw IllegalStateException("boom") }
                }.exceptionOrNull()
                aError.complete(e!!)
            }
            runCurrent()
            val b = launch {
                val e = runCatching {
                    coordinator.joinOnce(key) { error("joiner block must not run") }
                }.exceptionOrNull()
                bError.complete(e!!)
            }
            runCurrent()

            aDone.complete(Unit)
            advanceUntilIdle()
            a.join()
            b.join()

            assertTrue(aError.getCompleted() is IllegalStateException)
            assertTrue("the joiner observes the same outcome", bError.getCompleted() is IllegalStateException)
            assertTrue("the failure also follows the scope's exception propagation", captured.get() is IllegalStateException)
            assertOnceKeyIdle(coordinator, key)
        } finally {
            scope.cancel()
        }
    }

    // Owning-scope cancellation: a cancelled scope is the source of truth.
    @Test
    fun `joinOnce on a cancelled scope throws CancellationException and registers nothing`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        try {
            scope.cancel()
            var caught: Throwable? = null
            try {
                coordinator.joinOnce(key) { error("must not run") }
            } catch (e: Throwable) {
                caught = e
            }
            assertTrue(caught is CancellationException)
            assertTrue(caught!!.message!!.contains("cancelled"))
            val probe = CompletableDeferred<Unit>()
            coordinator.launchOnce(key) { probe.complete(Unit) }
            advanceUntilIdle()
            assertFalse("a cancelled scope accepts no new work", probe.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `launchOnce on a cancelled scope registers nothing`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        val order = mutableListOf<String>()
        try {
            scope.cancel()
            coordinator.launchOnce(key) { order.add("A") }
            advanceUntilIdle()

            assertTrue(order.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `joinOnce failure under a regular job scope cancels the scope and later submissions are no-ops`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + Job() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.once()
        try {
            coordinator.launchOnce(key) { throw IllegalStateException("boom") }
            advanceUntilIdle()

            assertTrue(captured.get() is IllegalStateException)
            assertTrue("the regular parent scope is cancelled", scope.coroutineContext[Job]!!.isCancelled)

            val order = mutableListOf<String>()
            coordinator.launchOnce(key) { order.add("again") }
            advanceUntilIdle()
            assertTrue("no execution starts after the scope is cancelled", order.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
