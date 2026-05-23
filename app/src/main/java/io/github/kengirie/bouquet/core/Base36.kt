package io.github.kengirie.bouquet.core

import java.math.BigInteger

/**
 * NIP-5A canonical subdomain helpers: encode/decode a 32-byte pubkey as a
 * fixed-width 50-character lowercase base36 string. Mirrors
 * `nsite-gateway/src/helpers/base36.ts`.
 *
 * Why 50 chars: the max value of a 32-byte integer (2^256 - 1) is exactly
 * 50 digits in base 36, and we left-pad with `0` so every pubkey produces
 * the same width — required so the parser can split a canonical DNS label
 * `<pubkeyB36><dTag>` purely on a fixed offset.
 */
private const val PUBKEY_B36_LENGTH = 50
private const val PUBKEY_HEX_LENGTH = 64
private val PUBKEY_B36_REGEX = Regex("^[0-9a-z]{$PUBKEY_B36_LENGTH}$")
private val PUBKEY_HEX_REGEX = Regex("^[0-9a-f]{$PUBKEY_HEX_LENGTH}$")
private val MAX_32_BYTE_VALUE: BigInteger =
    BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

/**
 * Decode a 50-character lowercase base36 string into a 64-character lowercase
 * hex pubkey. Returns null if the input is malformed or would overflow the
 * 32-byte range (e.g. `"zzzz...z"` for 50 chars is larger than 2^256 - 1).
 */
fun decodePubkeyB36(pubkeyB36: String): String? {
    if (!PUBKEY_B36_REGEX.matches(pubkeyB36)) return null
    val value = BigInteger(pubkeyB36, 36)
    if (value > MAX_32_BYTE_VALUE) return null
    return value.toString(16).padStart(PUBKEY_HEX_LENGTH, '0')
}

/**
 * Encode a 64-character lowercase hex pubkey as a 50-character lowercase
 * base36 string with `0` left-padding. Returns null if the input is not a
 * valid 32-byte hex string. Inverse of [decodePubkeyB36].
 */
fun encodePubkeyB36(pubkeyHex: String): String? {
    if (!PUBKEY_HEX_REGEX.matches(pubkeyHex)) return null
    val value = BigInteger(pubkeyHex, 16)
    return value.toString(36).padStart(PUBKEY_B36_LENGTH, '0')
}
