package io.github.kengirie.bouquet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mirrors `nsite-gateway/src/helpers/__tests__/base36.test.ts` plus a few
 * extra boundary checks (overflow, padding, rejection of malformed input)
 * that are easy to express in JUnit.
 */
class Base36Test {

    @Test
    fun `encode then decode round-trips a 32-byte hex value`() {
        val hex = "0123456789abcdef".repeat(4)
        val encoded = encodePubkeyB36(hex)
        assertEquals(50, encoded!!.length)
        assertEquals(hex, decodePubkeyB36(encoded))
    }

    @Test
    fun `encode pads zero value to 50 chars`() {
        assertEquals("0".repeat(50), encodePubkeyB36("0".repeat(64)))
    }

    @Test
    fun `decode pads small value back to 64 hex chars`() {
        // "0...01" in base36 → BigInteger 1 → "0...01" in hex (64-char padded).
        val decoded = decodePubkeyB36("0".repeat(49) + "1")
        assertEquals("0".repeat(63) + "1", decoded)
    }

    @Test
    fun `decode rejects wrong length`() {
        assertNull(decodePubkeyB36("0".repeat(49))) // too short
        assertNull(decodePubkeyB36("0".repeat(51))) // too long
        assertNull(decodePubkeyB36(""))
    }

    @Test
    fun `decode rejects uppercase`() {
        // Canonical labels are lowercase per NIP-5A; case-insensitive
        // accept would mask DNS-label normalization bugs upstream.
        assertNull(decodePubkeyB36("A".repeat(50)))
    }

    @Test
    fun `decode rejects out-of-range base36 chars`() {
        // '-' and '_' are valid in DNS labels but not in base36.
        assertNull(decodePubkeyB36("-".repeat(50)))
    }

    @Test
    fun `decode rejects overflow above 32 bytes`() {
        // "z".repeat(50) is the max 50-digit base36 number, which exceeds
        // 2^256 - 1. nsite-gateway also rejects this; we mirror that.
        assertNull(decodePubkeyB36("z".repeat(50)))
    }

    @Test
    fun `encode rejects malformed hex`() {
        assertNull(encodePubkeyB36(""))
        assertNull(encodePubkeyB36("0".repeat(63))) // 63 chars
        assertNull(encodePubkeyB36("0".repeat(65))) // 65 chars
        assertNull(encodePubkeyB36("g".repeat(64))) // non-hex char
    }
}
