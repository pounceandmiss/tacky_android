package com.example.tackyapk

import com.example.tackyapk.util.formatFingerprint
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure fingerprint formatter: 8-char groups, half per line, lowercased. */
class FingerprintTest {

    @Test
    fun groupsByEightAndWrapsHalfPerLine() {
        // 4 groups -> 2 per line.
        val hex = "AABBCCDD11223344EEFF001055667788"
        assertEquals("aabbccdd 11223344\neeff0010 55667788", formatFingerprint(hex))
    }

    @Test
    fun stripsExistingWhitespace() {
        // Two groups -> one per line; whitespace in the input is removed first.
        assertEquals("aabbccdd\n11223344", formatFingerprint("aabbccdd 11223344"))
    }

    @Test
    fun singleGroupStaysOnOneLine() {
        assertEquals("aabbccdd", formatFingerprint("aabbccdd"))
    }

    @Test
    fun emptyIsUnknown() {
        assertEquals("(unknown)", formatFingerprint(""))
        assertEquals("(unknown)", formatFingerprint("   "))
    }
}
