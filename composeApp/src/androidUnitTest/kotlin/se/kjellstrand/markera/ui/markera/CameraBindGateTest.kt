package se.kjellstrand.markera.ui.markera

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CameraBindGateTest {

    @Test
    fun disposedBeforeTheProviderIsReadyMayNotBind() {
        // #12: Back right after opening the scan screen cold.
        val gate = CameraBindGate()
        val session = gate.open()
        assertTrue(session.dispose())
        assertFalse(session.mayBind())
        session.bound()
        assertFalse(gate.bound.value)
    }

    @Test
    fun bindThenDisposeClearsBoundAndAsksForUnbind() {
        val gate = CameraBindGate()
        val session = gate.open()
        assertTrue(session.mayBind())
        session.bound()
        assertTrue(gate.bound.value)
        assertTrue(session.dispose())
        assertFalse(gate.bound.value)
        assertFalse(session.dispose(), "a second dispose is a no-op")
    }

    @Test
    fun aLateDisposeOfAnOldSessionLeavesTheNewBindAlone() {
        val gate = CameraBindGate()
        val old = gate.open()
        old.bound()
        val new = gate.open()
        assertFalse(gate.bound.value, "a new session starts unbound")
        assertFalse(old.mayBind())
        new.bound()
        assertFalse(old.dispose(), "a superseded session must not unbind")
        assertTrue(gate.bound.value)
    }

    @Test
    fun captureWaitsForTheRebind() = runTest {
        // #20: scan right after resuming live from a frozen frame.
        val gate = CameraBindGate()
        gate.open().apply { bound(); dispose() }
        val session = gate.open()
        val waiting = async { gate.awaitBound(3_000) }
        advanceTimeBy(500)
        runCurrent()
        assertFalse(waiting.isCompleted)
        session.bound()
        runCurrent()
        assertTrue(waiting.await())
    }

    @Test
    fun captureGivesUpWhenTheBindNeverComes() = runTest {
        val gate = CameraBindGate()
        gate.open()
        assertFalse(gate.awaitBound(3_000))
    }

    @Test
    fun alreadyBoundReturnsAtOnce() = runTest {
        val gate = CameraBindGate()
        gate.open().bound()
        assertTrue(gate.awaitBound(3_000))
    }
}
