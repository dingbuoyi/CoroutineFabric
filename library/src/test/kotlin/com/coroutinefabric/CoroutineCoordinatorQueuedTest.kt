package com.coroutinefabric

import java.util.concurrent.atomic.AtomicInteger
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
 * Queued strategy tests, per docs/CoroutineCoordinator-Test-Cases-v1.md sections 7-11
 * (UT-Q01 .. UT-Q11) plus the owning-scope cancellation and resubmission cases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineCoordinatorQueuedTest {

    // UT-Q01: a single submission executes.
    @Test
    fun `a single queued submission executes`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A") }
            advanceUntilIdle()

            assertEquals(listOf("A"), order)
        } finally {
            scope.cancel()
        }
    }

    // UT-Q02: two submissions execute FIFO.
    @Test
    fun `two queued submissions execute in submission order`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await() }
            runCurrent()
            coordinator.launchQueued(key) { order.add("B") }
            runCurrent()

            assertEquals("B waits for A", listOf("A"), order)

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("A", "B"), order)
        } finally {
            scope.cancel()
        }
    }

    // UT-Q03 + UT-Q06: strict FIFO; submissions are accepted while an execution is active.
    @Test
    fun `submissions accepted while the first is active execute in strict FIFO order`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await() }
            runCurrent()
            coordinator.launchQueued(key) { order.add("B") }
            coordinator.launchQueued(key) { order.add("C") }
            coordinator.launchQueued(key) { order.add("D") }
            runCurrent()

            assertEquals("B/C/D are accepted while A is running", listOf("A"), order)

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals("strict FIFO, no reordering", listOf("A", "B", "C", "D"), order)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // UT-Q04: executions never overlap - at most one active execution at any time.
    @Test
    fun `queued executions never overlap`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        val bDone = CompletableDeferred<Unit>()
        try {
            val activeExecutions = AtomicInteger(0)
            val maxActiveExecutions = AtomicInteger(0)
            val order = mutableListOf<String>()

            fun tracked(name: String, gate: CompletableDeferred<Unit>?): suspend CoroutineScope.() -> Unit =
                {
                    val c = activeExecutions.incrementAndGet()
                    maxActiveExecutions.updateAndGet { maxOf(it, c) }
                    order.add(name)
                    gate?.await()
                    activeExecutions.decrementAndGet()
                }

            coordinator.launchQueued(key, tracked("A", aDone))
            runCurrent()
            coordinator.launchQueued(key, tracked("B", bDone))
            coordinator.launchQueued(key, tracked("C", null))
            runCurrent()

            aDone.complete(Unit)
            bDone.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("A", "B", "C"), order)
            assertEquals("at most one active execution at any time", 1, maxActiveExecutions.get())
        } finally {
            scope.cancel()
        }
    }

    // UT-Q05: launchQueued does not wait.
    @Test
    fun `launchQueued returns immediately while the execution stays active`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); started.complete(Unit); release.await() }
            runCurrent()

            assertTrue("A started", started.isCompleted)
            coordinator.launchQueued(key) { order.add("B") }
            runCurrent()

            assertEquals("A is still active after the call returned, B was accepted into the queue", listOf("A"), order)

            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("A", "B"), order)
        } finally {
            scope.cancel()
        }
    }

    // UT-Q07: queued() waits for its OWN execution, not for the whole queue to drain.
    @Test
    fun `queued suspends until its own execution completes, not until the queue drains`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await() }
            runCurrent()

            val orderAtResume = CompletableDeferred<List<String>>()
            val b = launch {
                coordinator.queued(key) { order.add("B") }
                orderAtResume.complete(order.toList())
            }
            runCurrent()
            coordinator.launchQueued(key) { order.add("C") }
            runCurrent()

            assertEquals("B has not run yet", listOf("A"), order)
            assertFalse("the suspended caller is still waiting", orderAtResume.isCompleted)

            aDone.complete(Unit)
            runCurrent()

            assertEquals(
                "the caller resumed as soon as its own execution finished, before C",
                listOf("A", "B"),
                orderAtResume.getCompleted(),
            )

            advanceUntilIdle()
            b.join()
            assertEquals(listOf("A", "B", "C"), order)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // UT-Q08: multiple queued callers wait independently; each resumes with its own execution.
    @Test
    fun `multiple queued callers each resume with their own execution`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        val cGate = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            val bCallerResumed = CompletableDeferred<List<String>>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await() }
            runCurrent()

            val b = launch {
                coordinator.queued(key) { order.add("B") }
                bCallerResumed.complete(order.toList())
            }
            runCurrent()
            val c = launch { coordinator.queued(key) { order.add("C"); cGate.await() } }
            runCurrent()
            val d = launch { coordinator.queued(key) { order.add("D") } }
            runCurrent()

            aDone.complete(Unit)
            runCurrent()

            assertEquals(
                "B's caller resumed as soon as B finished, while C is still running",
                listOf("A", "B"),
                bCallerResumed.getCompleted(),
            )
            assertFalse("C's caller is still waiting for its own execution", c.isCompleted)
            assertFalse(d.isCompleted)

            cGate.complete(Unit)
            advanceUntilIdle()
            b.join()
            c.join()
            d.join()

            assertEquals(listOf("A", "B", "C", "D"), order)
        } finally {
            scope.cancel()
        }
    }

    // UT-Q09: scope cancellation aborts the pending queue.
    @Test
    fun `scope cancellation aborts the pending queue and wakes the queued waiters`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await() }
            runCurrent()

            val bError = CompletableDeferred<Throwable>()
            val b = launch {
                val e = runCatching { coordinator.queued(key) { order.add("B") } }.exceptionOrNull()
                bError.complete(e!!)
            }
            runCurrent()
            coordinator.launchQueued(key) { order.add("C") }
            runCurrent()

            scope.cancel()
            advanceUntilIdle()
            b.join()

            assertEquals("B/C/D never execute after the scope is cancelled", listOf("A"), order)
            assertTrue(bError.getCompleted() is CancellationException)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // UT-Q10: failure under a normal Job follows structured concurrency.
    @Test
    fun `failure under a regular job scope cancels the scope and aborts the remaining queue`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + Job() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await(); throw IllegalStateException("boom") }
            runCurrent()
            coordinator.launchQueued(key) { order.add("B") }
            coordinator.launchQueued(key) { order.add("C") }
            runCurrent()
            assertEquals(listOf("A"), order)

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals("B and C are aborted with the scope and never execute", listOf("A"), order)
            assertTrue(captured.get() is IllegalStateException)
            assertTrue("the regular parent scope is cancelled", scope.coroutineContext[Job]!!.isCancelled)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // UT-Q11: failure under SupervisorJob - no extra global cancellation from the coordinator.
    @Test
    fun `failure under a supervisor scope does not cancel the scope and the tail continues`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); throw IllegalStateException("boom") }
            coordinator.launchQueued(key) { order.add("B") }
            coordinator.launchQueued(key) { order.add("C") }
            advanceUntilIdle()

            assertEquals("the coordinator adds no extra cancellation", listOf("A", "B", "C"), order)
            assertTrue(captured.get() is IllegalStateException)
            assertFalse("the supervisor scope survives a child failure", scope.coroutineContext[Job]!!.isCancelled)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // Cancellation is not a failure: the queue continues, the scope handler is not invoked.
    @Test
    fun `a block throwing CancellationException is a cancellation and the queue continues`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            val order = mutableListOf<String>()
            val e = CompletableDeferred<Throwable>()
            val job = launch {
                val err = runCatching {
                    coordinator.queued(key) { order.add("A"); throw CancellationException("user cancel") }
                }.exceptionOrNull()
                e.complete(err!!)
            }
            runCurrent()
            coordinator.launchQueued(key) { order.add("B") }
            advanceUntilIdle()
            job.join()

            assertTrue(e.getCompleted() is CancellationException)
            assertEquals("cancellation is not a failure: the queue continues", listOf("A", "B"), order)
            assertNull("the scope handler is not invoked", captured.get())
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `queued cancellation under a regular job scope is not a failure and the queue continues`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + Job() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await(); throw CancellationException("user cancel") }
            runCurrent()
            coordinator.launchQueued(key) { order.add("B") }
            coordinator.launchQueued(key) { order.add("C") }
            runCurrent()

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("A", "B", "C"), order)
            assertNull("cancellation is not a failure: the scope handler is not invoked", captured.get())
            assertTrue("the regular parent scope is still active", !scope.coroutineContext[Job]!!.isCancelled)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `queued cancellation under a supervisor scope is not a failure and the queue continues`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A"); aDone.await(); throw CancellationException("user cancel") }
            runCurrent()
            coordinator.launchQueued(key) { order.add("B") }
            coordinator.launchQueued(key) { order.add("C") }
            runCurrent()

            aDone.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("A", "B", "C"), order)
            assertNull("cancellation is not a failure: the scope handler is not invoked", captured.get())
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // The suspended caller observes its own execution's failure; the queue continues.
    @Test
    fun `a queued caller observes its own execution failure, later submissions continue`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A") }
            advanceUntilIdle()

            val bError = CompletableDeferred<Throwable>()
            val b = launch {
                val e = runCatching {
                    coordinator.queued(key) { order.add("B"); throw IllegalStateException("boom") }
                }.exceptionOrNull()
                bError.complete(e!!)
            }
            runCurrent()
            coordinator.launchQueued(key) { order.add("C") }
            advanceUntilIdle()
            b.join()

            assertEquals(listOf("A", "B", "C"), order)
            val e = bError.getCompleted()
            assertTrue(e is IllegalStateException)
            assertEquals("boom", e.message)
            // The scope handler receives the thrown instance; the suspended caller gets the
            // same failure (kotlinx may hand out a stack-trace-recovered copy on await).
            val c = captured.get()
            assertTrue(c is IllegalStateException)
            assertEquals("boom", c.message)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    // The failure keeps its original type/message and the key stays resubmittable.
    @Test
    fun `a failing block fails with the original exception and the key is resubmittable`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            coordinator.launchQueued(key) { throw IllegalStateException("boom") }
            advanceUntilIdle()

            val e = captured.get()
            assertTrue(e is IllegalStateException)
            assertEquals("boom", e.message)
            assertFalse("failure is not a cancellation", e is CancellationException)

            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("again") }
            advanceUntilIdle()

            assertEquals(listOf("again"), order)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `non-cancellation failure cancels a regular parent scope and releases all keys`() = runTest {
        val (handler, captured) = capturingExceptionHandler()
        val scope = CoroutineScope(coroutineContext + Job() + handler)
        val coordinator = CoroutineCoordinator(scope)
        val key1 = CoordinatorKey.queued()
        val key2 = CoordinatorKey.queued()
        val aDone = CompletableDeferred<Unit>()
        try {
            coordinator.launchQueued(key2) { aDone.await() }
            runCurrent()

            coordinator.launchQueued(key1) { throw IllegalStateException("boom") }
            advanceUntilIdle()

            assertTrue(captured.get() is IllegalStateException)
            assertTrue("parent scope is cancelled", scope.coroutineContext[Job]!!.isCancelled)
            assertFalse(coordinator.hasActiveWork(key1))
            assertFalse(coordinator.hasActiveWork(key2))
        } finally {
            scope.cancel()
        }
    }

    // Owning-scope cancellation at submission time.
    @Test
    fun `queued on a cancelled scope throws CancellationException and registers nothing`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            scope.cancel()
            var caught: Throwable? = null
            try {
                coordinator.queued(key) { error("must not run") }
            } catch (e: Throwable) {
                caught = e
            }
            assertTrue(caught is CancellationException)
            assertTrue(caught!!.message!!.contains("cancelled"))
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `launchQueued on a cancelled scope registers nothing`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        val order = mutableListOf<String>()
        try {
            scope.cancel()
            coordinator.launchQueued(key) { order.add("A") }
            advanceUntilIdle()

            assertTrue(order.isEmpty())
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a new submission after the queue drained executes and returns to idle`() = runTest {
        val scope = independentSupervisedTestScope(coroutineContext)
        val coordinator = CoroutineCoordinator(scope)
        val key = CoordinatorKey.queued()
        try {
            val order = mutableListOf<String>()
            coordinator.launchQueued(key) { order.add("A") }
            coordinator.launchQueued(key) { order.add("B") }
            advanceUntilIdle()
            assertEquals(listOf("A", "B"), order)
            assertFalse(coordinator.hasActiveWork(key))

            coordinator.launchQueued(key) { order.add("C") }
            advanceUntilIdle()
            assertEquals(listOf("A", "B", "C"), order)
            assertFalse(coordinator.hasActiveWork(key))
        } finally {
            scope.cancel()
        }
    }
}
