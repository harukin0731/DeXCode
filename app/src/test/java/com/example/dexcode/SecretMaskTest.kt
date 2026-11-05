package com.example.dexcode

import org.junit.Assert.assertEquals
import org.junit.Test

class SecretMaskTest {
    @Test fun masksSavedAndNewlyTypedCharacters() {
        val source = StringBuilder("Saved-password")
        val masked = SecretMask.getTransformation(source, null)
        assertEquals("•".repeat(source.length), masked.toString())
        source.append('!')
        assertEquals(source.length, masked.length)
        assertEquals('•', masked[masked.length - 1])
        assertEquals("•••", masked.subSequence(0, 3))
        source.clear()
        assertEquals("", masked.toString())
    }
}
