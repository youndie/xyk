package io.github.youndie.xyk

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking

/**
 * The variant built with the only engine that speaks HTTPS on Kotlin/Native.
 *
 * The client is constructed and closed rather than merely referenced: an unused type is a type the
 * linker is free to remove, and the whole point of this variant is to make it pay for libcurl,
 * libssl and libcrypto, which the engine's cinterop klib carries inside itself as static archives.
 *
 * **`XYK_TLS_PROBE` makes it do one real request**, and that is here because certificates are a
 * deployment fact that nothing else can check: they exist on every developer machine and in no
 * minimal base image, the failure looks like a connection error rather than a missing file, and it
 * only ever appears inside a container. One `GET` from inside the image settles it.
 *
 * It is driven by the environment, never by a request — an operator points it somewhere, a sender
 * cannot.
 */
actual fun httpEngineMarker(): String {
    val client = HttpClient(Curl)
    val probe = readEnv("XYK_TLS_PROBE")
    val outcome =
        if (probe.isNullOrBlank()) {
            "not probed"
        } else {
            runCatching {
                runBlocking { client.get(probe) }.status.value.toString()
            }.fold(
                onSuccess = { status -> "GET $probe -> $status" },
                // The message as well as the type, because the type does not distinguish anything:
                // a missing CA bundle and a name that does not resolve both arrive as
                // `IllegalStateException` (measured, B-17). What tells them apart is the text.
                onFailure = { failure ->
                    "GET $probe failed: ${failure::class.simpleName}: ${failure.message?.take(160)}"
                },
            )
        }
    client.close()
    return "native: ktor-client-curl ($outcome)"
}
