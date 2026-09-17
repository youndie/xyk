package io.github.youndie.xyk.sink

/**
 * The JVM build has no sink, for the same reason it has no outbound engine: this target exists for a
 * fast cycle over common code and never ships, and a publish path exercised on a runtime nobody
 * deploys is the more expensive kind of green.
 *
 * It is not merely unimplemented. The native sink's producer configuration carries
 * `message.timeout.ms`, which is librdkafka's name; `kafka-clients` has no such key, so a JVM arm
 * that copied that configuration would be a different producer wearing the same words. Whichever arm
 * is added here later translates it rather than reusing it.
 */
actual fun kafkaEventSink(
    bootstrapServers: String,
    topic: String,
): EventSink? = null
