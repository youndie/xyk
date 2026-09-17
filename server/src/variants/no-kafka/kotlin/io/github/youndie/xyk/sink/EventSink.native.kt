package io.github.youndie.xyk.sink

/** Built for a target kafkakn publishes nothing for; `main` says so at start-up rather than at the first event. */
actual fun kafkaEventSink(
    bootstrapServers: String,
    topic: String,
): EventSink? = null
