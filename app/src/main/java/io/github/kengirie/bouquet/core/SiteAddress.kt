package io.github.kengirie.bouquet.core

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEmbed
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.entities.NRelay
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent

/**
 * Bouquet's normalized representation of the three valid input forms
 * (`npub1…`, `nprofile1…`, `naddr1…`). Every site address has a pubkey
 * and may carry relay hints; named addresses additionally carry the
 * NIP-33 identifier (`d` tag).
 */
sealed class SiteAddress {
    abstract val pubkey: String
    abstract val relayHints: List<NormalizedRelayUrl>

    /** `npub` / `nprofile` → fetch kind 15128 root site for the user. */
    data class Root(
        override val pubkey: String,
        override val relayHints: List<NormalizedRelayUrl>,
    ) : SiteAddress()

    /** `naddr` with kind 35128 → fetch the named site under [identifier]. */
    data class Named(
        override val pubkey: String,
        override val relayHints: List<NormalizedRelayUrl>,
        val identifier: String,
    ) : SiteAddress()

    sealed class ParseError(val message: String) {
        data object Empty : ParseError("Please enter a nostr address.")
        data object Invalid : ParseError("The address you used is invalid.")
        data object UnsupportedType : ParseError(
            "Unsupported address type. Please use npub, nprofile, or naddr.",
        )
        data class WrongKind(val kind: Int) : ParseError(
            "Invalid naddr kind: expected ${RootSiteEvent.KIND} or " +
                "${NamedSiteEvent.KIND}, got $kind.",
        )
    }

    /** Result of [parse]: success or a typed parse error. */
    sealed class ParseOutcome {
        data class Success(val address: SiteAddress) : ParseOutcome()
        data class Failure(val error: ParseError) : ParseOutcome()
    }

    companion object {
        /**
         * Decode a raw user input string into a [SiteAddress] or a typed
         * [ParseError]. Trims whitespace; defers to Quartz's [Nip19Parser]
         * for the actual bech32 work.
         */
        fun parse(input: String): ParseOutcome {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                return ParseOutcome.Failure(ParseError.Empty)
            }

            val parsed = Nip19Parser.uriToRoute(trimmed)
                ?: return ParseOutcome.Failure(ParseError.Invalid)

            return when (val entity = parsed.entity) {
                is NPub -> ParseOutcome.Success(
                    Root(pubkey = entity.hex, relayHints = emptyList()),
                )

                is NProfile -> ParseOutcome.Success(
                    Root(pubkey = entity.hex, relayHints = entity.relay),
                )

                is NAddress -> {
                    when (entity.kind) {
                        RootSiteEvent.KIND -> ParseOutcome.Success(
                            Root(
                                pubkey = entity.author,
                                relayHints = entity.relay,
                            ),
                        )

                        NamedSiteEvent.KIND -> ParseOutcome.Success(
                            Named(
                                pubkey = entity.author,
                                relayHints = entity.relay,
                                identifier = entity.dTag,
                            ),
                        )

                        else -> ParseOutcome.Failure(ParseError.WrongKind(entity.kind))
                    }
                }

                is NSec, is NNote, is NEvent, is NRelay, is NEmbed ->
                    ParseOutcome.Failure(ParseError.UnsupportedType)

                else -> ParseOutcome.Failure(ParseError.Invalid)
            }
        }
    }
}
