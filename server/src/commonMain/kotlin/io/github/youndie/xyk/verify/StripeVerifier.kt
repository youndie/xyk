package io.github.youndie.xyk.verify

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

/**
 * Stripe: `Stripe-Signature: t=<unix>,v1=<hex>[,v0=<hex>]`, HMAC-SHA256 over `"<t>" + "." + body`.
 *
 * Read from docs.stripe.com, *Receive Stripe events* → "Verify webhook signatures manually", on
 * 2026-09-15. Three of its rules are part of the contract rather than advice, and each is a line
 * here:
 *
 * * **every scheme that is not `v1` is ignored** — a downgrade defence. Stripe sends a `v0` for test
 *   events, and treating it as a candidate would accept a signature made under a weaker scheme;
 * * **several `v1` values can be present at once**, for up to 24 hours while a secret is rolled, so
 *   each is tried against each active secret;
 * * **the timestamp is checked against a tolerance** — five minutes in the official libraries — and
 *   a signature that matches but is too old is `STALE`, not `INVALID`. The two are different
 *   incidents: one is a clock, the other is a secret.
 */
class StripeVerifier(
    private val defaultToleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
) : Verifier {
    override val scheme: String = SCHEME

    override fun verify(
        request: SignedRequest,
        secrets: List<EndpointSecret>,
        config: SchemeConfig,
    ): Verdict {
        val header = request.headers(HEADER)?.trim()
        if (header.isNullOrEmpty()) return Verdict.Refused(VerificationFailure.MISSING)

        var timestamp: Long? = null
        val candidates = mutableListOf<String>()
        for (element in header.split(",")) {
            val name = element.substringBefore('=', missingDelimiterValue = "").trim()
            val value = element.substringAfter('=', missingDelimiterValue = "").trim()
            when (name) {
                "t" -> timestamp = value.toLongOrNull()

                // `v1` and nothing else. Not `startsWith("v")`.
                "v1" -> candidates += value.lowercase()

                else -> Unit
            }
        }
        if (timestamp == null || candidates.isEmpty()) return Verdict.Refused(VerificationFailure.INVALID)

        val signedPayload = "$timestamp.".encodeToByteArray() + request.body
        var matched: String? = null
        for (secret in secrets) {
            val expected =
                HmacSHA256(secret.secret.encodeToByteArray())
                    .doFinal(signedPayload)
                    .toHex()
                    .encodeToByteArray()
            for (candidate in candidates) {
                if (constantTimeEquals(expected, candidate.encodeToByteArray())) {
                    matched = secret.fingerprint
                    break
                }
            }
            if (matched != null) break
        }
        if (matched == null) return Verdict.Refused(VerificationFailure.INVALID)

        // Only after the signature matched. Reporting "stale" for a payload whose signature is wrong
        // would tell an attacker which half they got right.
        val tolerance = config.toleranceSeconds ?: defaultToleranceSeconds
        val drift = request.nowEpochSeconds - timestamp
        if (drift > tolerance || drift < -tolerance) return Verdict.Refused(VerificationFailure.STALE)

        return Verdict.Passed(matched)
    }

    companion object {
        const val SCHEME: String = "stripe"
        const val HEADER: String = "Stripe-Signature"

        /** What Stripe's own libraries use. */
        const val DEFAULT_TOLERANCE_SECONDS: Long = 300
    }
}
