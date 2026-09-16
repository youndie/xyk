package io.github.youndie.xyk.delivery

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * The one engine that speaks HTTPS on Kotlin/Native, bound as the delivery sink's outbound half.
 *
 * The client is built once and never closed: it lives as long as the process, and closing it per
 * delivery would mean a connection pool per delivery.
 */
actual fun outboundPost(): OutboundPost? {
    // `followRedirects = false` IS THE POINT, and it is set on the client rather than per request so
    // that no future call site can forget it. Following a redirect would POST somebody's payload to
    // an address no operator approved — the subscriber list is the approval, and a `Location` header
    // written by that subscriber is not. The sink treats a `3xx` as a failed attempt; this line is
    // what guarantees there is a `3xx` to treat rather than a silent second request.
    val client = HttpClient(Curl) { followRedirects = false }

    return OutboundPost { url, contentType, headers, body ->
        val response =
            client.post(url) {
                // The sender's own content type, unchanged: a webhook body is bytes whose type was
                // declared by whoever signed them, and substituting ours would misdescribe them.
                contentType?.let { contentType(ContentType.parse(it)) }
                headers {
                    headers.forEach { (name, value) -> append(name, value) }
                    // So a subscriber's logs say who called. Not configurable: an operator debugging
                    // traffic they did not expect needs one string to grep for.
                    append(HttpHeaders.UserAgent, "xyk")
                }
                setBody(body)
            }

        // READ AS BYTES AND CUT, rather than `bodyAsText()`. The response is written by somebody
        // else: it can be megabytes, it can declare a charset it does not use, and it arrives on
        // every failed attempt. A bounded prefix of the bytes, decoded, is the only form of this
        // that a misbehaving subscriber cannot turn into our memory problem.
        val prefix =
            response
                .bodyAsBytes()
                .take(RESPONSE_PREFIX_BYTES)
                .toByteArray()
                .decodeToString()

        OutboundPost.Response(response.status.value, prefix)
    }
}
