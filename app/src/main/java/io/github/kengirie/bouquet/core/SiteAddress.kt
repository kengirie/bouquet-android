package io.github.kengirie.bouquet.core

import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

/**
 * Bouquet's normalized representation of the two valid NIP-5A input forms:
 *
 *   - `npub1…` (bech32) → [Root]
 *   - `<pubkeyB36><dTag>` (NIP-5A canonical subdomain label) → [Named]
 *
 * Other NIP-19 entities (`naddr`, `nprofile`, `nsec`, `note`, `nevent`,
 * `nrelay`, `nembed`) are not accepted: NIP-5A reserves the on-the-wire
 * forms to those two, and `naddr`'s relay-hint affordance is redundant
 * with the NIP-65 write-relay lookup the gateway already performs.
 */
sealed class SiteAddress {
    abstract val pubkey: String

    /** `npub` → fetch the user's root site manifest (kind 15128). */
    data class Root(override val pubkey: String) : SiteAddress()

    /**
     * Canonical NIP-5A label `<pubkeyB36><dTag>` → fetch the named site
     * manifest (kind 35128) under [identifier].
     */
    data class Named(
        override val pubkey: String,
        val identifier: String,
    ) : SiteAddress()

    sealed class ParseError(val message: String) {
        data object Empty : ParseError("Please enter a nostr address.")
        data object Invalid : ParseError("The address you used is invalid.")
        data object UnsupportedType : ParseError(
            "Unsupported address type. Please use npub or a NIP-5A label.",
        )
    }

    /** Result of [parse]: success or a typed parse error. */
    sealed class ParseOutcome {
        data class Success(val address: SiteAddress) : ParseOutcome()
        data class Failure(val error: ParseError) : ParseOutcome()
    }

    companion object {
        // NIP-5A canonical named-site label: 50-char base36 pubkey + 1-13
        // char identifier, lowercase, not ending in `-`. The end-with-dash
        // check is enforced separately because expressing "no trailing
        // dash" inside the alternation here would be uglier than a
        // dedicated `endsWith` after the regex matches.
        private val CANONICAL_LABEL_REGEX = Regex("^[0-9a-z]{50}[a-z0-9-]{1,13}$")
        private const val PUBKEY_B36_LENGTH = 50

        /**
         * Decode a raw user input string into a [SiteAddress] or a typed
         * [ParseError]. Trims whitespace; tries bech32 (`npub1…`) via
         * Quartz's [Nip19Parser] first, then falls back to the NIP-5A
         * canonical subdomain label (`<pubkeyB36><dTag>`).
         *
         * Bech32 is tried first because the canonical-label regex
         * (`[0-9a-z]{50}[a-z0-9-]{1,13}`) is *more permissive* than any
         * valid `npub1…` string and would greedily swallow it otherwise —
         * an `npub` is also lowercase alphanumeric of compatible length,
         * and we'd silently base36-decode the wrong 50 characters and
         * produce a garbage pubkey.
         */
        fun parse(input: String): ParseOutcome {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                return ParseOutcome.Failure(ParseError.Empty)
            }

            val parsed = Nip19Parser.uriToRoute(trimmed)
                ?: return parseCanonicalLabel(trimmed)
                    ?: ParseOutcome.Failure(ParseError.Invalid)

            return when (val entity = parsed.entity) {
                is NPub -> ParseOutcome.Success(Root(pubkey = entity.hex))
                else -> ParseOutcome.Failure(ParseError.UnsupportedType)
            }
        }

        /**
         * Try to interpret [input] as a NIP-5A canonical subdomain label
         * (`<pubkeyB36><dTag>`). Returns null on no match — that is, the
         * input is not even shaped like a canonical label and should be
         * surfaced as a generic [ParseError.Invalid] by the caller.
         * Returns a typed [ParseOutcome.Failure] only when the input
         * clearly *looks* like a canonical label but is malformed (e.g.
         * trailing `-`, or base36 overflow).
         */
        private fun parseCanonicalLabel(input: String): ParseOutcome? {
            if (!CANONICAL_LABEL_REGEX.matches(input)) return null
            if (input.endsWith('-')) {
                return ParseOutcome.Failure(ParseError.Invalid)
            }
            val pubkey = decodePubkeyB36(input.substring(0, PUBKEY_B36_LENGTH))
                ?: return ParseOutcome.Failure(ParseError.Invalid)
            val identifier = input.substring(PUBKEY_B36_LENGTH)
            return ParseOutcome.Success(
                Named(pubkey = pubkey, identifier = identifier),
            )
        }
    }
}
