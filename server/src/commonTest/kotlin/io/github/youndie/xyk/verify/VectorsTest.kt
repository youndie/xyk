package io.github.youndie.xyk.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The schemes against **recorded** vectors.
 *
 * Every digest below was produced by `openssl`, not by this codebase, and the command that produced
 * it is written next to it. That is the whole point: a scheme checked only against signatures our
 * own HMAC computed proves that the code agrees with itself, which it would do just as convincingly
 * with the concatenation the wrong way round.
 *
 * Reproduce any line with:
 *
 *     printf %s '<the payload>' | openssl dgst -sha256 -hmac '<the secret>' -hex
 */
class VectorsTest {
    private fun secrets(vararg values: String) = values.map { EndpointSecret(it, "fp-$it") }

    private fun request(
        body: String,
        now: Long = 1_700_000_000,
        headers: Map<String, String>,
    ) = SignedRequest(
        headers = { name -> headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value },
        body = body.encodeToByteArray(),
        nowEpochSeconds = now,
    )

    // ---------------------------------------------------------------- GitHub

    private val githubBody = """{"zen":"Keep it logically awesome.","hook_id":1}"""
    private val githubSecret = "gh-secret"

    /** `printf %s '<githubBody>' | openssl dgst -sha256 -hmac 'gh-secret' -hex` */
    private val githubDigest = "ccfcf8f554fe45b6fffa3e6a32eb13ac37b3a1f337d0bbc97afee1ff38f1c81e"

    @Test
    fun `a recorded GitHub signature verifies`() {
        val verdict =
            GithubVerifier().verify(
                request(githubBody, headers = mapOf(GithubVerifier.HEADER to "sha256=$githubDigest")),
                secrets(githubSecret),
                SchemeConfig.EMPTY,
            )
        assertEquals("fp-$githubSecret", assertIs<Verdict.Passed>(verdict).fingerprint)
    }

    @Test
    fun `every single-byte mutation of the GitHub vector is refused`() {
        // Body, digest and prefix in turn. A scheme that accepts any of these accepts a forgery.
        val mutations =
            listOf(
                request(githubBody + " ", headers = mapOf(GithubVerifier.HEADER to "sha256=$githubDigest")),
                request(
                    githubBody,
                    headers = mapOf(GithubVerifier.HEADER to "sha256=" + githubDigest.dropLast(1) + "f"),
                ),
                request(githubBody, headers = mapOf(GithubVerifier.HEADER to "sha512=$githubDigest")),
                request(githubBody, headers = mapOf(GithubVerifier.HEADER to githubDigest)),
            )
        for (mutation in mutations) {
            val verdict = GithubVerifier().verify(mutation, secrets(githubSecret), SchemeConfig.EMPTY)
            assertEquals(
                VerificationFailure.INVALID,
                assertIs<Verdict.Refused>(verdict).failure,
                "a mutated vector was accepted",
            )
        }
    }

    // ---------------------------------------------------------------- Stripe

    private val stripeBody = """{"id":"evt_1","object":"event"}"""
    private val stripeSecret = "whsec_TEST"
    private val stripeTimestamp = 1_700_000_000L

    /** `printf %s '1700000000.<stripeBody>' | openssl dgst -sha256 -hmac 'whsec_TEST' -hex` */
    private val stripeV1 = "50b5e517ff9e1ef104450f99f293fb9aa399e1ce5a63bc9a7426ab689bb55e28"

    /** The same body **without** the timestamp prefix: `printf %s '<stripeBody>' | openssl …` */
    private val stripeBodyOnly = "c88f6339befa8ce5c398172e9a3be317ddfd04db2eaf6f6bdb6647e3998b3e11"

    private fun stripeHeader(vararg parts: String) = mapOf(StripeVerifier.HEADER to parts.joinToString(","))

    @Test
    fun `a recorded Stripe signature verifies and the concatenation is part of the contract`() {
        val good =
            StripeVerifier().verify(
                request(stripeBody, headers = stripeHeader("t=$stripeTimestamp", "v1=$stripeV1")),
                secrets(stripeSecret),
                SchemeConfig.EMPTY,
            )
        assertEquals("fp-$stripeSecret", assertIs<Verdict.Passed>(good).fingerprint)

        // Signed over the body alone — the mistake anybody writing this from memory makes.
        val bodyOnly =
            StripeVerifier().verify(
                request(stripeBody, headers = stripeHeader("t=$stripeTimestamp", "v1=$stripeBodyOnly")),
                secrets(stripeSecret),
                SchemeConfig.EMPTY,
            )
        assertEquals(VerificationFailure.INVALID, assertIs<Verdict.Refused>(bodyOnly).failure)
    }

    @Test
    fun `a v0 scheme never verifies anything even when its digest would match`() {
        // The downgrade defence, stated by Stripe: ignore everything that is not v1. The digest here
        // is the one that WOULD pass as v1.
        val verdict =
            StripeVerifier().verify(
                request(stripeBody, headers = stripeHeader("t=$stripeTimestamp", "v0=$stripeV1")),
                secrets(stripeSecret),
                SchemeConfig.EMPTY,
            )
        assertEquals(VerificationFailure.INVALID, assertIs<Verdict.Refused>(verdict).failure)
    }

    @Test
    fun `several v1 values are all candidates`() {
        val verdict =
            StripeVerifier().verify(
                request(
                    stripeBody,
                    headers =
                        stripeHeader(
                            "t=$stripeTimestamp",
                            "v1=" + "0".repeat(64),
                            "v1=$stripeV1",
                            "v0=$stripeBodyOnly",
                        ),
                ),
                secrets(stripeSecret),
                SchemeConfig.EMPTY,
            )
        assertTrue(verdict is Verdict.Passed, "a valid v1 next to a wrong one was not tried")
    }

    @Test
    fun `an old but genuine Stripe signature is stale rather than invalid`() {
        val verdict =
            StripeVerifier(defaultToleranceSeconds = 300).verify(
                request(
                    stripeBody,
                    now = stripeTimestamp + 400,
                    headers = stripeHeader("t=$stripeTimestamp", "v1=$stripeV1"),
                ),
                secrets(stripeSecret),
                SchemeConfig.EMPTY,
            )
        // A clock and a secret are different incidents, and an operator who cannot tell them apart
        // debugs the wrong one.
        assertEquals(VerificationFailure.STALE, assertIs<Verdict.Refused>(verdict).failure)
    }

    @Test
    fun `a clock ahead of the sender is stale in the other direction too`() {
        val verdict =
            StripeVerifier(defaultToleranceSeconds = 300).verify(
                request(
                    stripeBody,
                    now = stripeTimestamp - 400,
                    headers = stripeHeader("t=$stripeTimestamp", "v1=$stripeV1"),
                ),
                secrets(stripeSecret),
                SchemeConfig.EMPTY,
            )
        assertEquals(VerificationFailure.STALE, assertIs<Verdict.Refused>(verdict).failure)
    }

    @Test
    fun `the endpoint's own tolerance wins over the install default`() {
        val verdict =
            StripeVerifier(defaultToleranceSeconds = 10).verify(
                request(
                    stripeBody,
                    now = stripeTimestamp + 400,
                    headers = stripeHeader("t=$stripeTimestamp", "v1=$stripeV1"),
                ),
                secrets(stripeSecret),
                SchemeConfig(toleranceSeconds = 600),
            )
        assertTrue(verdict is Verdict.Passed)
    }

    // ---------------------------------------------------------------- Telegram

    @Test
    fun `Telegram compares the token and says nothing about the body`() {
        val token = "A-token_1"
        val passed =
            TelegramVerifier().verify(
                request("anything at all", headers = mapOf(TelegramVerifier.HEADER to token)),
                secrets(token),
                SchemeConfig.EMPTY,
            )
        assertEquals("fp-$token", assertIs<Verdict.Passed>(passed).fingerprint)

        val wrong =
            TelegramVerifier().verify(
                request("anything at all", headers = mapOf(TelegramVerifier.HEADER to token + "x")),
                secrets(token),
                SchemeConfig.EMPTY,
            )
        assertEquals(VerificationFailure.INVALID, assertIs<Verdict.Refused>(wrong).failure)

        val missing =
            TelegramVerifier().verify(
                request("anything at all", headers = emptyMap()),
                secrets(token),
                SchemeConfig.EMPTY,
            )
        assertEquals(VerificationFailure.MISSING, assertIs<Verdict.Refused>(missing).failure)
    }

    // ---------------------------------------------------------------- Generic

    private val genericBody = "payload=whatever&x=1"
    private val genericSecret = "generic-secret"

    /** `printf %s '<genericBody>' | openssl dgst -sha256 -hmac 'generic-secret' -hex` */
    private val genericHex = "04c61489a0f3fb794df8ae83cd3588598669a6c55c382f1f2c0be0e4948bca59"

    /** The same, `-binary | base64`. */
    private val genericBase64 = "BMYUiaDz+3lN+K6DzTWIWYZppsVcOC8fLAvg5JSLylk="

    @Test
    fun `the generic scheme reads the header it is configured with`() {
        val config = SchemeConfig(header = "X-Provider-Signature", prefix = "v1=", encoding = "hex")
        val verdict =
            GenericHmacVerifier().verify(
                request(genericBody, headers = mapOf("X-Provider-Signature" to "v1=$genericHex")),
                secrets(genericSecret),
                config,
            )
        assertEquals("fp-$genericSecret", assertIs<Verdict.Passed>(verdict).fingerprint)

        // The same digest in the wrong header is not a signature at all.
        val elsewhere =
            GenericHmacVerifier().verify(
                request(genericBody, headers = mapOf("X-Other" to "v1=$genericHex")),
                secrets(genericSecret),
                config,
            )
        assertEquals(VerificationFailure.MISSING, assertIs<Verdict.Refused>(elsewhere).failure)
    }

    @Test
    fun `the generic scheme can be told the digest is base64`() {
        val verdict =
            GenericHmacVerifier().verify(
                request(genericBody, headers = mapOf("X-Sig" to genericBase64)),
                secrets(genericSecret),
                SchemeConfig(header = "X-Sig", encoding = "base64"),
            )
        assertTrue(verdict is Verdict.Passed)

        // And that hex is not silently accepted in its place.
        val hexInstead =
            GenericHmacVerifier().verify(
                request(genericBody, headers = mapOf("X-Sig" to genericHex)),
                secrets(genericSecret),
                SchemeConfig(header = "X-Sig", encoding = "base64"),
            )
        assertEquals(VerificationFailure.INVALID, assertIs<Verdict.Refused>(hexInstead).failure)
    }

    @Test
    fun `an unconfigured generic endpoint refuses rather than accepts`() {
        val verdict =
            GenericHmacVerifier().verify(
                request(genericBody, headers = mapOf("X-Sig" to genericHex)),
                secrets(genericSecret),
                SchemeConfig.EMPTY,
            )
        assertEquals(VerificationFailure.INVALID, assertIs<Verdict.Refused>(verdict).failure)
    }

    // ---------------------------------------------------------------- none

    @Test
    fun `the none scheme passes without a fingerprint`() {
        val verdict =
            NoVerificationVerifier().verify(
                request("", headers = emptyMap()),
                secrets(),
                SchemeConfig.EMPTY,
            )
        // No fingerprint, so the journal cannot show one — an event that proved nothing must not
        // look like an event that proved something.
        assertEquals(null, assertIs<Verdict.Passed>(verdict).fingerprint)
    }

    // ---------------------------------------------------------------- the compare itself

    @Test
    fun `constant-time compare is still a correct compare`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertTrue(!constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertTrue(!constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(!constantTimeEquals(byteArrayOf(0), byteArrayOf(-128)))
        assertTrue(constantTimeEquals(byteArrayOf(), byteArrayOf()))
    }
}
