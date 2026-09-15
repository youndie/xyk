package io.github.youndie.xyk.journal

import io.github.youndie.xyk.journal.domain.DeliveryLine
import io.github.youndie.xyk.journal.domain.EventDetail
import io.github.youndie.xyk.journal.domain.EventLine
import io.github.youndie.xyk.journal.domain.JournalFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The page is a pure function of a model, which is the only reason its five states can be tested at
 * all — two of them (Purged, Degraded) cannot be reached from the database yet.
 */
class JournalPageTest {
    private fun event(
        id: String = "abcdef0123456789",
        received: Long = 1_757_000_000,
        contentType: String? = "application/json",
        deliveries: Int = 1,
        pending: Int = 1,
        dead: Int = 0,
        purgedAt: Long? = null,
    ) = EventLine(
        id = id,
        endpointId = "endpoint-1",
        receivedAt = received,
        scheme = "github",
        secretFingerprint = "1a2b3c4d",
        contentType = contentType,
        bodyBytes = 47,
        deliveries = deliveries,
        pending = pending,
        dead = dead,
        purgedAt = purgedAt,
    )

    private fun hookUrl(id: String) = "https://hooks.example.test/hooks/$id"

    @Test
    fun `an empty journal with no endpoints tells you how to make one`() {
        val html =
            JournalPage.list(
                events = emptyList(),
                filter = JournalFilter(),
                delivery = DeliveryStatus.NOT_CONFIGURED,
                hookUrlFor = ::hookUrl,
                knownEndpointId = null,
            )
        assertTrue("Nothing has arrived yet" in html)
        assertTrue("/api/endpoints" in html, "the empty state does not say how to create an endpoint")
    }

    @Test
    fun `an empty journal with an endpoint gives a curl line and points at the counters`() {
        val html =
            JournalPage.list(
                events = emptyList(),
                filter = JournalFilter(),
                delivery = DeliveryStatus.RUNNING,
                hookUrlFor = ::hookUrl,
                knownEndpointId = "endpoint-1",
            )
        assertTrue(hookUrl("endpoint-1") in html)
        // "Nothing is being sent" and "everything is being refused" look identical without this.
        assertTrue("rejections" in html, "the empty state does not point at the rejection counters")
        assertTrue("\$SIG" in html, "the shell snippet lost its variable")
    }

    @Test
    fun `an empty filtered journal offers to clear the filter rather than blaming the sender`() {
        val html =
            JournalPage.list(
                events = emptyList(),
                filter = JournalFilter(state = "dead"),
                delivery = DeliveryStatus.RUNNING,
                hookUrlFor = ::hookUrl,
                knownEndpointId = "endpoint-1",
            )
        assertTrue("No events match this filter" in html)
        assertFalse("Nothing has arrived yet" in html)
    }

    @Test
    fun `the list renders a timestamp the image smoke can look for`() {
        val html =
            JournalPage.list(
                listOf(event()),
                JournalFilter(),
                DeliveryStatus.RUNNING,
                ::hookUrl,
                null,
            )
        // The smoke test greps for exactly this shape, so it is asserted here rather than assumed.
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").containsMatchIn(html))
        assertTrue("1 pending of 1" in html)
    }

    @Test
    fun `a page with no worker running says so before anything else`() {
        val html =
            JournalPage.list(listOf(event()), JournalFilter(), DeliveryStatus.NOT_CONFIGURED, ::hookUrl, null)
        assertTrue("No delivery worker is running" in html)
        // Above the table: a reader must see it before the states it explains.
        assertTrue(html.indexOf("No delivery worker") < html.indexOf("<table"))
    }

    @Test
    fun `a stalled worker is a different sentence from an unconfigured one`() {
        val html = JournalPage.list(listOf(event()), JournalFilter(), DeliveryStatus.STALLED, ::hookUrl, null)
        assertTrue("has not completed a pass recently" in html)
        assertTrue("/health/ready" in html)
    }

    @Test
    fun `the detail page names the scheme rather than showing a bare verified badge`() {
        val html =
            JournalPage.detail(
                EventDetail(
                    event(),
                    listOf(DeliveryLine("d1", "https://sink.invalid/x", "pending", 0, 1_757_000_000)),
                ),
                DeliveryStatus.RUNNING,
            )
        assertTrue("github" in html)
        assertTrue("secret 1a2b3c4d" in html)
        assertFalse("verified</" in html, "a bare verified badge overstates what Telegram proves")
    }

    @Test
    fun `a purged payload is not a missing event`() {
        val html =
            JournalPage.detail(
                EventDetail(event(purgedAt = 1_757_100_000), emptyList()),
                DeliveryStatus.RUNNING,
            )
        assertTrue("purged by retention" in html)
        assertFalse("download as received" in html, "a purged payload still offered a download link")
    }

    @Test
    fun `an event nobody was subscribed to is complete rather than pending`() {
        val html =
            JournalPage.detail(
                EventDetail(event(deliveries = 0, pending = 0), emptyList()),
                DeliveryStatus.RUNNING,
            )
        assertTrue("complete, not pending" in html)
    }

    @Test
    fun `a removed subscriber keeps its attempts attributed to something`() {
        val html =
            JournalPage.detail(
                EventDetail(event(), listOf(DeliveryLine("d1", null, "dead", 5, 1_757_000_000))),
                DeliveryStatus.RUNNING,
            )
        assertTrue("(subscriber removed)" in html)
        assertTrue(">5<" in html)
    }

    @Test
    fun `a content type chosen by the sender cannot close a tag`() {
        // The one field on this page an attacker controls completely, rendered beside one an
        // operator wrote — which is the shape of the bug where the trusted-looking neighbour gets
        // the escaping and this one does not.
        val html =
            JournalPage.detail(
                EventDetail(event(contentType = "text/html\"><script>alert('x')</script>"), emptyList()),
                DeliveryStatus.RUNNING,
            )
        assertFalse("<script>" in html, "an unescaped sender-chosen value reached the page")
        assertTrue("&lt;script&gt;" in html)
    }

    @Test
    fun `the id on a not-found page is echoed but escaped`() {
        val html = JournalPage.notFound("<img src=x onerror=1>")
        assertFalse("<img" in html)
        assertTrue("&lt;img" in html)
    }

    @Test
    fun `timestamps are UTC and the arithmetic holds at the edges`() {
        // Every constant below was produced by `date -u -d @<seconds> '+%Y-%m-%d %H:%M'`, not by this
        // code — the same rule as the signature vectors. A hand-rolled calendar checked against its
        // own output would agree with itself about February.
        assertEquals("1970-01-01 00:00", formatUtc(0))
        assertEquals("2000-02-29 12:00", formatUtc(951_825_600))
        assertEquals("2024-12-31 23:59", formatUtc(1_735_689_540))
        assertEquals("2026-09-15 16:53", formatUtc(1_789_491_180))
        // Before the epoch, because an operator's clock can be wrong and a page must not throw.
        assertEquals("1969-12-31 23:59", formatUtc(-60))
    }

    @Test
    fun `escaping covers the five characters that matter and leaves the rest alone`() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", escapeHtml("&<>\"'"))
        assertEquals("héllo ✓", escapeHtml("héllo ✓"))
    }
}
