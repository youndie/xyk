package io.github.youndie.xyk.delivery

/**
 * The JVM build has no outbound engine, on purpose.
 *
 * This target exists for a fast test cycle over common code and never ships; adding a client engine
 * here would mean a delivery path exercised on a runtime nobody deploys, which is the more expensive
 * kind of green. The sink itself is covered on both targets through [OutboundPost], which is a port
 * precisely so that neither suite needs a socket.
 */
actual fun outboundPost(): OutboundPost? = null
