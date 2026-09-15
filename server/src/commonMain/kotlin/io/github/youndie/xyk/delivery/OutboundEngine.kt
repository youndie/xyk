package io.github.youndie.xyk.delivery

/**
 * The outbound engine this build was linked with, or `null` when it was linked without one.
 *
 * **`null` is a normal, shipping configuration rather than a failure.** The default binary links no
 * HTTP client at all: `ktor-client-curl` is the only engine that speaks HTTPS on Kotlin/Native and it
 * carries libcurl, libssl and libcrypto as static archives, which is 8.8 MB of image
 * ([B-05](../../../../../../../../docs/backlog/B-05-static-link-probe.md)). A deployment that only
 * receives webhooks and reads the journal has no use for that weight.
 *
 * What must not happen is a build that silently accepts deliveries it cannot make. `main` says which
 * it is at start-up, out loud, and the readiness check for the delivery half refuses to report ready
 * when timers exist and there is no engine to serve them.
 */
expect fun outboundPost(): OutboundPost?
