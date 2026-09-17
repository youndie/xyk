package io.github.youndie.xyk.sink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What goes onto the topic, asserted where it can be asserted.
 *
 * The sink itself needs a broker and only exists on one target; the shape it publishes is common
 * code precisely so that the agreement with whoever reads the topic is checked on every build rather
 * than on the one machine that has Kafka in front of it.
 */
class SinkEnvelopeTest {
    private val record =
        AcceptedRecord(
            eventId = "ev-1",
            endpointId = "hook-1",
            receivedAt = 1_700_000_000,
            bodyBytes = 42,
            contentType = "application/json",
        )

    @Test
    fun `the envelope carries the identity and the shape of the event`() {
        val json = Json.parseToJsonElement(record.toEnvelopeBytes().decodeToString()).jsonObject

        assertEquals(
            setOf("event", "endpoint", "receivedAt", "bodyBytes", "contentType"),
            json.keys,
            "the envelope's keys are a contract with whoever reads the topic",
        )
        assertEquals("\"ev-1\"", json.getValue("event").toString())
        assertEquals("\"hook-1\"", json.getValue("endpoint").toString())
        assertEquals("1700000000", json.getValue("receivedAt").toString())
        assertEquals("42", json.getValue("bodyBytes").toString())
    }

    @Test
    fun `the body is not on the topic`() {
        // The bytes are in `events`, behind a retention horizon this service enforces. A copy on a
        // topic would outlive that horizon somewhere nothing here can reach, so the absence is the
        // feature and it is asserted rather than assumed.
        val published = record.toEnvelopeBytes().decodeToString()

        assertFalse(published.contains("body\""), "the envelope grew a body field")
        assertTrue(published.contains("bodyBytes"), "the length is what replaces it, and it is missing")
    }

    @Test
    fun `an event with no content type still carries the key`() {
        // Written as an explicit null rather than omitted: a reader should not have to tell an absent
        // key from a sender that declared nothing.
        val json =
            Json
                .parseToJsonElement(
                    AcceptedRecord("ev-2", "hook-1", 1, 0, contentType = null).toEnvelopeBytes().decodeToString(),
                ).jsonObject

        assertTrue(json.containsKey("contentType"))
        assertEquals("null", json.getValue("contentType").toString())
    }
}
