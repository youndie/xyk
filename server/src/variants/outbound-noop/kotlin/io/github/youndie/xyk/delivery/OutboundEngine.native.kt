package io.github.youndie.xyk.delivery

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

/**
 * **A measurement arm, never a deployment.** Everything the real variant does except the request.
 *
 * It exists for one question, and only a build can answer it.
 * [B-30](../../../../../../../../docs/backlog/B-30-delivery-memory-growth.md) measured a service
 * whose memory grows about 6 kB per delivery in anonymous pages, outside a managed heap that is
 * pinned — so the growth is native allocation somewhere on the delivery path. There are three
 * candidates: the curl engine with the OpenSSL it carries, the Rust half of sqlx4k writing attempt
 * rows, and the worker loop itself. `smaps` cannot separate them, because this binary is statically
 * linked and every one of them lives inside `/app/server` with no mapping of its own.
 *
 * So this variant keeps the whole path and removes exactly one term: the timer fires, the worker
 * leases, the sink runs, the attempt row is written, the state is updated — and no HTTP happens.
 * If the growth survives that, it is not curl.
 *
 * **The client is still constructed and kept for the life of the process**, deliberately. The engine
 * brings up its own dispatcher thread and its own buffers whether or not anything is sent, and an
 * arm that skipped construction would be measuring "no engine at all", which is the `no-curl`
 * variant and is already a row in that table.
 *
 * Selected by `-Pxyk.outbound=noop`, which the build refuses to combine with a shipping image.
 */
actual fun outboundPost(): OutboundPost? {
    // Never used. See above: it is here so the arm differs from the real one by the request alone.
    @Suppress("UNUSED_VARIABLE")
    val client = HttpClient(Curl) { followRedirects = false }

    return OutboundPost { _, _, _, _ ->
        // `200` rather than a failure, because a failure would exercise the retry path and change
        // the number of rows written — which is one of the terms this arm is holding still.
        OutboundPost.Response(status = 200, bodyPrefix = "")
    }
}
