package io.github.youndie.xyk.sink

import io.github.youndie.kafkakn.ProducerConfig
import io.github.youndie.kafkakn.ProducerRecord
import io.github.youndie.kafkakn.RecordHeader
import io.github.youndie.kafkakn.kafkaProducer

/**
 * Accepted events onto a Kafka topic, through kafkakn.
 *
 * **`send` returns when the broker has acknowledged**, so a publish is part of the request that is
 * waiting for it rather than something left behind afterwards. That is deliberate: an accepted
 * webhook whose record is still in a queue somewhere is the state this sink must never be in when
 * the process is asked to stop, and the cheapest way not to be in it is never to be in it at all.
 * What it costs is ingest latency, which a deployment that does not want to pay turns off by not
 * setting `XYK_KAFKA_BOOTSTRAP_SERVERS`.
 */
actual fun kafkaEventSink(
    bootstrapServers: String,
    topic: String,
): EventSink? = KafkaEventSink(bootstrapServers, topic)

private class KafkaEventSink(
    bootstrapServers: String,
    private val topic: String,
) : EventSink {
    private val producer =
        kafkaProducer(
            ProducerConfig(
                "bootstrap.servers" to bootstrapServers,
                // Every replica, because the record stands for a webhook this service has already
                // told its sender it accepted. A leader-only acknowledgement would make that claim
                // survive fewer failures than the row in SQLite behind it.
                "acks" to "all",
                // TEN SECONDS, AND IT IS A DEADLINE ON A REQUEST rather than a tuning knob.
                // librdkafka's default is 300 000 ms: with a broker that is down, an accepted
                // webhook would sit inside this call for five minutes, past every shutdown deadline
                // this process has and past any sender's patience. Ten seconds fails loudly instead,
                // and the event is still stored.
                "message.timeout.ms" to "10000",
            ),
        )

    override suspend fun publish(record: AcceptedRecord) {
        producer.send(
            ProducerRecord(
                topic = topic,
                // The event id, so that every record for one event lands on one partition and a
                // reader that only wants the latest can compact. It is the same id the journal shows
                // and the same id the sender was answered with, which is what makes the topic and
                // this service's own tables reconcilable at all.
                key = record.eventId.encodeToByteArray(),
                value = record.toEnvelopeBytes(),
                // xyk has no trace id to carry — nothing here propagates one yet — so the headers
                // carry what a router would actually route on. When tracing lands, it belongs here
                // beside them rather than in the body.
                headers =
                    buildList {
                        add(RecordHeader("xyk-endpoint", record.endpointId.encodeToByteArray()))
                        record.contentType?.let { type ->
                            add(RecordHeader("xyk-content-type", type.encodeToByteArray()))
                        }
                    },
            ),
        )
    }

    override suspend fun close() {
        producer.close()
    }
}
