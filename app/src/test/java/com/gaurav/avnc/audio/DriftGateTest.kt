package com.gaurav.avnc.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class DriftGateTest {

    @Test
    fun prefillsAfterUnderrun() {
        assertEquals(Decision.Prefill(80), DriftGate().decide(0))
    }

    @Test
    fun writesInRange() {
        val gate = DriftGate()
        assertEquals(Decision.Write, gate.decide(40))
        assertEquals(Decision.Write, gate.decide(150))
    }

    @Test
    fun dropsAboveHighWater() {
        val gate = DriftGate()
        assertEquals(Decision.Drop, gate.decide(170))
        assertEquals(Decision.Drop, gate.decide(120))
        assertEquals(Decision.Write, gate.decide(80))
        assertEquals(Decision.Write, gate.decide(120))
    }

    @Test
    fun underrunClearsDropState() {
        val gate = DriftGate()
        assertEquals(Decision.Drop, gate.decide(170))
        assertEquals(Decision.Prefill(80), gate.decide(0))
        assertEquals(Decision.Write, gate.decide(120))
    }
}
