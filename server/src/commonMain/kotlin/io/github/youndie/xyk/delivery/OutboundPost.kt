package io.github.youndie.xyk.delivery

/**
 * One outbound POST, as a port — **and the reason it is a port is the linker, not testability**.
 *
 * The only engine that speaks HTTPS on Kotlin/Native is `ktor-client-curl`, and it is a build
 * variant here (`-Pxyk.httpClient`): the default binary links no outbound engine at all, which is
 * what keeps the image at the size B-23 quotes. If this signature mentioned an `HttpClient`,
 * `commonMain` would depend on a client that must not be in every build — so nothing in this file,
 * or in the sink that uses it, names a Ktor type. The engine is bound at the composition root, in
 * the variant that has one.
 *
 * Testability comes free with that, and it is the better half: the timeout case, the redirect case
 * and the never-answers case are all one fake away, with no socket to arrange.
 */
fun interface OutboundPost {
    /**
     * POST [body] to [url] and report what came back.
     *
     * **This must not follow redirects.** A `3xx` is an outcome to return, not a hop to take:
     * following one would deliver somebody's payload to an address no operator approved. The engine
     * binding is responsible for turning that off — a fake cannot enforce it, which is why it is
     * said here and asserted in `DeliverySinkTest`.
     *
     * Throwing is allowed and expected: a connection refused, a DNS failure or a TLS error arrive
     * that way, and the sink treats them exactly as it treats a `500`.
     */
    suspend fun post(
        url: String,
        contentType: String?,
        headers: Map<String, String>,
        body: ByteArray,
    ): Response

    /**
     * What the subscriber answered.
     *
     * [bodyPrefix] is the first bytes of the response and nothing more: an operator debugging a
     * rejection needs the error message the subscriber returned, and storing a whole body would put
     * an unbounded string written by somebody else into our database on every failed attempt.
     */
    data class Response(
        val status: Int,
        val bodyPrefix: String,
    )
}

/** How much of a subscriber's response is kept. Enough for a message, far short of a payload. */
const val RESPONSE_PREFIX_BYTES: Int = 512
