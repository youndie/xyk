package io.github.youndie.xyk.verify

/**
 * What a verifier is handed, and it is the whole request rather than `(body, secret)`.
 *
 * Anything narrower cannot express Stripe, which signs `"<timestamp>.<body>"`, carries several
 * candidate signatures while a secret is being rolled, and rejects a timestamp outside a tolerance.
 * Telegram at the other end needs no body at all. One shape for all of them, decided once.
 */
class SignedRequest(
    val headers: (String) -> String?,
    val body: ByteArray,
    val nowEpochSeconds: Long,
)

/** A secret as stored, with the fingerprint the journal shows instead of the value. */
class EndpointSecret(
    val secret: String,
    val fingerprint: String,
)

/** Why a request was refused. Three different incidents, three different things to go and fix. */
enum class VerificationFailure {
    /** The scheme's header is not there at all. */
    MISSING,

    /** It is there and it does not match. */
    INVALID,

    /** It matches the bytes but the timestamp is outside the tolerance — a clock, not a secret. */
    STALE,
}

/** The verdict: which secret matched, or why nothing did. */
sealed class Verdict {
    class Passed(
        val fingerprint: String?,
    ) : Verdict()

    class Refused(
        val failure: VerificationFailure,
    ) : Verdict()
}

/**
 * One scheme.
 *
 * Implementations are per-vendor and their details were read from the vendors' own documentation
 * rather than from memory — the headers, prefixes and concatenations are exactly the kind of detail
 * memory gets subtly wrong, and the failure is a `401` on genuine traffic.
 */
interface Verifier {
    /** The value stored in `endpoints.scheme`. */
    val scheme: String

    fun verify(
        request: SignedRequest,
        secrets: List<EndpointSecret>,
        config: SchemeConfig,
    ): Verdict
}
