package io.github.youndie.xyk.verify

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import kotlin.io.encoding.Base64

/**
 * Everyone else: HMAC-SHA256 over the raw body, in a header the endpoint names.
 *
 * The three named schemes are the three whose details were read from a vendor's documentation. This
 * one is for the provider nobody has heard of, and it is deliberately the only configurable
 * verifier: a new vendor whose scheme is "HMAC-SHA256 hex in a header of our own" needs a row, not a
 * class.
 *
 * A vendor that does something genuinely different — signs a timestamp, uses an asymmetric key —
 * gets a class next to [StripeVerifier], and that is the intended boundary.
 */
class GenericHmacVerifier : Verifier {
    override val scheme: String = SCHEME

    override fun verify(
        request: SignedRequest,
        secrets: List<EndpointSecret>,
        config: SchemeConfig,
    ): Verdict {
        val headerName = config.header ?: return Verdict.Refused(VerificationFailure.INVALID)
        val raw = request.headers(headerName)?.trim()
        if (raw.isNullOrEmpty()) return Verdict.Refused(VerificationFailure.MISSING)

        val prefix = config.prefix.orEmpty()
        if (prefix.isNotEmpty() && !raw.startsWith(prefix)) {
            return Verdict.Refused(VerificationFailure.INVALID)
        }
        val offered = raw.removePrefix(prefix)

        for (secret in secrets) {
            val mac = HmacSHA256(secret.secret.encodeToByteArray()).doFinal(request.body)
            val expected =
                when (config.encoding?.lowercase()) {
                    // Base64 is compared case-sensitively and hex is not: hex has two spellings of
                    // the same value and base64 does not.
                    ENCODING_BASE64 -> Base64.Default.encode(mac)

                    else -> mac.toHex()
                }
            val matches =
                if (config.encoding?.lowercase() == ENCODING_BASE64) {
                    constantTimeEquals(expected.encodeToByteArray(), offered.encodeToByteArray())
                } else {
                    constantTimeEquals(expected.encodeToByteArray(), offered.lowercase().encodeToByteArray())
                }
            if (matches) return Verdict.Passed(secret.fingerprint)
        }
        return Verdict.Refused(VerificationFailure.INVALID)
    }

    companion object {
        const val SCHEME: String = "hmac-sha256"
        const val ENCODING_BASE64: String = "base64"
        const val ENCODING_HEX: String = "hex"
    }
}

/**
 * Verifies nothing, on purpose, and it takes two deliberate acts to reach: `XYK_ALLOW_UNVERIFIED` at
 * start-up and an explicit scheme at creation.
 *
 * It exists because "we will point a sender at it and work out its scheme afterwards" is a real
 * first afternoon with a new integration, and the alternative people reach for otherwise is a shared
 * secret in a query string.
 */
class NoVerificationVerifier : Verifier {
    override val scheme: String = SCHEME

    override fun verify(
        request: SignedRequest,
        secrets: List<EndpointSecret>,
        config: SchemeConfig,
    ): Verdict = Verdict.Passed(fingerprint = null)

    companion object {
        const val SCHEME: String = "none"
    }
}
