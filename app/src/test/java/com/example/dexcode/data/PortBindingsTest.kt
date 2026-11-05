package com.example.dexcode.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortBindingsTest {
    @Test fun oldSettingsAndInvalidPortsAreIgnored() {
        assertTrue(PortBindings.decode(null).isEmpty())
        assertEquals(mapOf(3000 to 13000), PortBindings.decode(JSONObject("""{"3000":13000,"0":1234,"70000":1234,"4000":0,"5000":70000,"bad":1234}""")))
    }

    @Test fun savedMappingsRoundTripAndCanBeRemovedIndependently() {
        val saved = PortBindings.updated(mapOf(8080 to 18080), 3000, 13000)
        assertEquals(saved, PortBindings.decode(JSONObject(PortBindings.encode(saved).toString())))
        assertEquals(mapOf(8080 to 18080), PortBindings.updated(saved, 3000, 0))
    }

    @Test fun duplicateLocalPortCannotReplaceAnotherMapping() {
        val saved = mapOf(3000 to 13000)
        assertThrows(IllegalArgumentException::class.java) { PortBindings.updated(saved, 4000, 13000) }
        assertEquals(mapOf(3000 to 13000), saved)
        assertThrows(IllegalArgumentException::class.java) { PortBindings.updated(saved, 3000, 65536) }
    }
}
