package io.github.kengirie.bouquet.core

import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure unit tests for [SiteAddress.parse], focused on the NIP-5A canonical
 * subdomain label code path. Bech32 (`npub` / `naddr`) parsing is exercised
 * indirectly through [GatewayTest] and not re-tested here.
 */
class SiteAddressTest {

    // A representative 32-byte pubkey used across the canonical-label tests.
    // Chosen so it survives the b36 round-trip without surprises.
    private val pubkeyHex = "0123456789abcdef".repeat(4)
    private val pubkeyB36 = encodePubkeyB36(pubkeyHex)!!

    // ── Canonical label: happy paths ──────────────────────────────────────

    @Test
    fun `parse accepts canonical label with single-char dTag`() {
        val outcome = SiteAddress.parse(pubkeyB36 + "x")
        val success = outcome as? SiteAddress.ParseOutcome.Success
            ?: fail("expected Success, got $outcome").let { return }
        val named = success.address as? SiteAddress.Named
            ?: fail("expected Named, got ${success.address}").let { return }
        assertEquals(pubkeyHex, named.pubkey)
        assertEquals("x", named.identifier)
    }

    @Test
    fun `parse accepts canonical label with 13-char dTag`() {
        val dTag = "abcdefghijklm" // exactly 13 chars
        val outcome = SiteAddress.parse(pubkeyB36 + dTag)
        val named = (outcome as SiteAddress.ParseOutcome.Success).address as SiteAddress.Named
        assertEquals(dTag, named.identifier)
    }

    @Test
    fun `parse accepts canonical label with digits and hyphens in dTag`() {
        val dTag = "blog-2024"
        val outcome = SiteAddress.parse(pubkeyB36 + dTag)
        val named = (outcome as SiteAddress.ParseOutcome.Success).address as SiteAddress.Named
        assertEquals(dTag, named.identifier)
    }

    @Test
    fun `parse trims whitespace before matching canonical label`() {
        val outcome = SiteAddress.parse("   $pubkeyB36" + "blog\n")
        val named = (outcome as SiteAddress.ParseOutcome.Success).address as SiteAddress.Named
        assertEquals("blog", named.identifier)
    }

    // ── Canonical label: rejections ───────────────────────────────────────

    @Test
    fun `parse rejects canonical label ending with hyphen`() {
        val outcome = SiteAddress.parse(pubkeyB36 + "blog-")
        assertTrue("expected Failure, got $outcome", outcome is SiteAddress.ParseOutcome.Failure)
    }

    @Test
    fun `parse rejects bare pubkeyB36 with no dTag`() {
        // NIP-5A: root sites are only addressable via npub, not via a
        // bare 50-char b36 string. This MUST be rejected even though it
        // is a valid pubkey encoding.
        val outcome = SiteAddress.parse(pubkeyB36)
        assertTrue("expected Failure, got $outcome", outcome is SiteAddress.ParseOutcome.Failure)
    }

    @Test
    fun `parse rejects label longer than 63 chars`() {
        // 50 + 14 = 64, exceeds the DNS-label limit and the NIP-5A dTag
        // budget of 13. The regex anchors should reject this.
        val outcome = SiteAddress.parse(pubkeyB36 + "a".repeat(14))
        assertTrue("expected Failure, got $outcome", outcome is SiteAddress.ParseOutcome.Failure)
    }

    @Test
    fun `parse rejects uppercase canonical label`() {
        // DNS is case-insensitive but NIP-5A pins the on-the-wire form to
        // lowercase. Accepting uppercase here would let two distinct
        // strings address the same site, which we want to avoid.
        val outcome = SiteAddress.parse(pubkeyB36.uppercase() + "blog")
        assertTrue("expected Failure, got $outcome", outcome is SiteAddress.ParseOutcome.Failure)
    }

    // ── Bech32 still works ────────────────────────────────────────────────

    @Test
    fun `parse still accepts npub for root sites`() {
        val npub = NPub.create(pubkeyHex)
        val outcome = SiteAddress.parse(npub)
        val root = (outcome as SiteAddress.ParseOutcome.Success).address as SiteAddress.Root
        assertEquals(pubkeyHex, root.pubkey)
    }
}
