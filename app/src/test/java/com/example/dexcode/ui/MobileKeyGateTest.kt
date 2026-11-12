package com.example.dexcode.ui

import org.junit.Assert.*
import org.junit.Test

class MobileKeyGateTest {
    @Test fun releasedRepeatCannotSendLateKeyAfterAsynchronousChecks() {
        val gate=MobileKeyGate()
        val ticket=gate.begin(4)!!
        assertTrue(gate.accepts(ticket,4))
        assertFalse(gate.accepts(ticket,5))
        assertTrue(gate.cancelRepeat())
        assertFalse(gate.accepts(ticket,5))
        assertFalse(gate.finish(ticket))
    }
    @Test fun timeoutAndNewRequestRejectAnOldRendererResponse() {
        val gate=MobileKeyGate()
        val old=gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.finish(old))
        val next=gate.begin()!!
        assertFalse(gate.accepts(old,0))
        assertFalse(gate.finish(old))
        assertTrue(gate.accepts(next,0))
    }
    @Test fun repeatCancellationDoesNotDiscardAnUnrelatedNormalAction() {
        val gate=MobileKeyGate()
        val save=gate.begin()!!
        assertFalse(gate.cancelRepeat())
        assertTrue(gate.accepts(save,10))
        gate.cancel()
        assertFalse(gate.accepts(save,10))
        assertNotNull(gate.begin())
    }
}
