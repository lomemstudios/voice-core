package com.lomemstudios.voice.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatePolicyTest {
    private val ready = GateFacts(GateMode.ENDS, model = true, print = true, benchP95Ms = 9.8, sco = false, onSco = false)

    @Test fun runsWithModelPrintAndAFastEnoughPhone() = assertNull(GatePolicy.offBecause(ready))

    @Test fun runsWhenTheSpeedTestWasNeverRun() = assertNull(GatePolicy.offBecause(ready.copy(benchP95Ms = null)))

    @Test fun offWithoutTheSetting() = assertEquals("off in settings", GatePolicy.offBecause(ready.copy(mode = GateMode.OFF)))

    @Test fun offWithoutModelOrPrint() {
        assertEquals("no pvad model in this build", GatePolicy.offBecause(ready.copy(model = false)))
        assertEquals("no voice print for the gate", GatePolicy.offBecause(ready.copy(print = false)))
    }

    @Test fun offWhenTheSpeedTestSaysTheBudgetIsMissed() {
        assertNull(GatePolicy.offBecause(ready.copy(benchP95Ms = 15.0)))
        assertEquals("this phone is too slow (speed test p95 15.1 ms)", GatePolicy.offBecause(ready.copy(benchP95Ms = 15.1)))
    }

    @Test fun offOnEarbudsAndTheCarUnlessSwitchedOn() {
        assertEquals("off on earbuds and the car", GatePolicy.offBecause(ready.copy(sco = true)))
        assertNull(GatePolicy.offBecause(ready.copy(sco = true, onSco = true)))
    }

    @Test fun offOnceHealthSaysSo() =
        assertEquals("the track never opened on 5 of your turns", GatePolicy.offBecause(ready.copy(healthOff = true)))

    @Test fun modeWireNames() {
        assertEquals(GateMode.ENDS, GateMode.of("ends"))
        assertEquals(GateMode.OFF, GateMode.of("filters"))
        assertEquals(GateMode.OFF, GateMode.of(null))
    }
}
