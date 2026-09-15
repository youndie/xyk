package io.github.youndie.xyk.journal

import io.github.youndie.xyk.journal.domain.EventDetail
import io.github.youndie.xyk.journal.domain.EventLine
import io.github.youndie.xyk.journal.domain.JournalFilter

/**
 * Whether anything is delivering what ingest accepts.
 *
 * The page has to be able to say so: a journal that looks normal while nothing is being delivered is
 * worse than one that is down, because it is believed.
 */
enum class DeliveryStatus {
    /** A worker has ticked recently. */
    RUNNING,

    /** No worker is configured in this build — the outbound half is not wired up yet. */
    NOT_CONFIGURED,

    /** Workers exist and have not completed a tick for several poll intervals. */
    STALLED,
}

/**
 * The journal, rendered by hand.
 *
 * **No template engine and no `kotlinx.html`**, and that is a size decision rather than taste: the
 * image budget is 10 MB against a binary that is already 19 MB linked with its HTTP client
 * (B-05/B-23), and a markup library is paid for in exactly the place that hurts. What it costs is
 * that escaping is ours — so everything that reaches this file goes through [escapeHtml], including
 * the `Content-Type` a sender chose.
 *
 * The styling is one inline block. A stylesheet would be a second request, a second route and a
 * second thing to compress, for a page that is a table.
 */
object JournalPage {
    fun list(
        events: List<EventLine>,
        filter: JournalFilter,
        delivery: DeliveryStatus,
        hookUrlFor: (String) -> String,
        knownEndpointId: String?,
    ): String {
        val body =
            if (events.isEmpty()) {
                emptyState(filter, hookUrlFor, knownEndpointId)
            } else {
                buildString {
                    append("<table><thead><tr>")
                    append("<th>received (UTC)</th><th>event</th><th>endpoint</th><th>scheme</th>")
                    append("<th>bytes</th><th>deliveries</th>")
                    append("</tr></thead><tbody>")
                    for (event in events) {
                        append("<tr class=\"").append(rowClass(event)).append("\">")
                        append("<td>").append(escapeHtml(formatUtc(event.receivedAt))).append("</td>")
                        append("<td><a href=\"/journal/").append(escapeHtml(event.id)).append("\">")
                        append(escapeHtml(event.id.take(12))).append("</a></td>")
                        append("<td>").append(escapeHtml(event.endpointId)).append("</td>")
                        append("<td>").append(escapeHtml(event.scheme)).append("</td>")
                        append("<td>").append(event.bodyBytes).append("</td>")
                        append("<td>").append(deliverySummary(event)).append("</td>")
                        append("</tr>")
                    }
                    append("</tbody></table>")
                }
            }
        return page("xyk — journal", banner(delivery) + body)
    }

    fun detail(
        detail: EventDetail,
        delivery: DeliveryStatus,
    ): String {
        val event = detail.event
        val body =
            buildString {
                append("<p><a href=\"/journal\">&larr; all events</a></p>")
                append("<h2>").append(escapeHtml(event.id)).append("</h2>")
                append("<dl>")
                row("received (UTC)", formatUtc(event.receivedAt))
                row("endpoint", event.endpointId)
                // The scheme by name, never a bare "verified" badge: a Telegram token proves the
                // sender knew a secret and says nothing about the body, and a reader has to be able
                // to tell that from an HMAC over the bytes.
                row("verified by", event.scheme + (event.secretFingerprint?.let { " · secret $it" } ?: ""))
                row("content type", event.contentType ?: "(none)")
                row("size", "${event.bodyBytes} bytes")
                append("</dl>")

                if (event.purgedAt != null) {
                    append("<p class=\"note\">The payload was purged by retention on ")
                    append(escapeHtml(formatUtc(event.purgedAt)))
                    append(". The record of what happened is still here.</p>")
                } else {
                    append("<details><summary>payload (")
                    append(event.bodyBytes)
                    append(" bytes)</summary><p class=\"note\">")
                    append("<a href=\"/api/events/").append(escapeHtml(event.id))
                    append("/payload\">download as received</a>")
                    append("</p></details>")
                }

                append("<h3>deliveries</h3>")
                if (detail.deliveries.isEmpty()) {
                    append("<p class=\"note\">Nobody was subscribed when this arrived, so there is ")
                    append("nothing to deliver. The event is complete, not pending.</p>")
                } else {
                    append("<table><thead><tr><th>subscriber</th><th>state</th><th>attempts</th>")
                    append("<th>created (UTC)</th></tr></thead><tbody>")
                    for (line in detail.deliveries) {
                        append("<tr><td>")
                        append(escapeHtml(line.subscriberUrl ?: "(subscriber removed)"))
                        append("</td><td>").append(escapeHtml(line.state))
                        append("</td><td>").append(line.attempts)
                        append("</td><td>").append(escapeHtml(formatUtc(line.createdAt)))
                        append("</td></tr>")
                    }
                    append("</tbody></table>")
                }
            }
        return page("xyk — ${event.id.take(12)}", banner(delivery) + body)
    }

    /** The id is echoed back because it was pasted from somewhere, and where from is the next question. */
    fun notFound(eventId: String): String =
        page(
            "xyk — not found",
            "<p>No event with id <code>" + escapeHtml(eventId) + "</code>.</p>" +
                "<p class=\"note\">It may have been sent to a different installation, or never " +
                "accepted at all — an endpoint's <code>rejections</code> counters say which.</p>" +
                "<p><a href=\"/journal\">All events</a></p>",
        )

    private fun StringBuilder.row(
        name: String,
        value: String,
    ) {
        append("<dt>")
        append(escapeHtml(name))
        append("</dt><dd>")
        append(escapeHtml(value))
        append("</dd>")
    }

    private fun rowClass(event: EventLine): String =
        when {
            event.dead > 0 -> "dead"
            event.pending > 0 -> "pending"
            else -> ""
        }

    private fun deliverySummary(event: EventLine): String =
        when {
            event.deliveries == 0 -> "&mdash;"
            event.dead > 0 -> "${event.dead} dead of ${event.deliveries}"
            event.pending > 0 -> "${event.pending} pending of ${event.deliveries}"
            else -> "${event.deliveries} delivered"
        }

    /**
     * The empty state, which on a fresh install is the only documentation anybody reads.
     *
     * It answers the two questions that look identical from outside — "is nothing being sent?" and
     * "is everything being refused?" — by pointing at the rejection counters, and it gives a `curl`
     * line so the first webhook can be sent without leaving the page.
     */
    private fun emptyState(
        filter: JournalFilter,
        hookUrlFor: (String) -> String,
        knownEndpointId: String?,
    ): String =
        buildString {
            if (filter.endpointId != null || filter.state != null) {
                append("<p>No events match this filter. <a href=\"/journal\">Show everything</a>.</p>")
                return@buildString
            }
            append("<p>Nothing has arrived yet.</p>")
            if (knownEndpointId == null) {
                append("<p class=\"note\">There are no endpoints. Create one:</p>")
                append("<pre>curl -X POST ")
                append(escapeHtml(hookUrlFor("").removeSuffix("/hooks/")))
                append("/api/endpoints \\\n  -H 'Content-Type: application/json' \\\n")
                append("  -d '{\"scheme\":\"github\",\"secret\":\"choose-one\"}'</pre>")
            } else {
                append("<p class=\"note\">Send one to make sure the path works:</p>")
                // The dollars are the shell's, not Kotlin's: escaped so the snippet a person copies
                // is the snippet that runs.
                append("<pre>BODY='{\"hello\":\"xyk\"}'\n")
                append("SIG=\$(printf %s \"\$BODY\" | openssl dgst -sha256 ")
                append("-hmac 'your-secret' -hex | sed 's/.*= //')\n")
                append("curl -X POST ")
                append(escapeHtml(hookUrlFor(knownEndpointId)))
                append(" \\\n  -H \"X-Hub-Signature-256: sha256=\$SIG\" \\\n  --data-binary \"\$BODY\"</pre>")
                append("<p class=\"note\">If it was refused rather than lost, the endpoint's ")
                append("<code>rejections</code> counters say why: ")
                append("<code>GET /api/endpoints/").append(escapeHtml(knownEndpointId)).append("</code></p>")
            }
        }

    private fun banner(delivery: DeliveryStatus): String =
        when (delivery) {
            DeliveryStatus.RUNNING -> {
                ""
            }

            DeliveryStatus.NOT_CONFIGURED -> {
                "<p class=\"warn\">No delivery worker is running in this build: events are recorded " +
                    "and nothing is being sent on. Everything below is what arrived, not what was " +
                    "delivered.</p>"
            }

            DeliveryStatus.STALLED -> {
                "<p class=\"warn\">The delivery worker has not completed a pass recently. The states " +
                    "below are stale; <code>/health/ready</code> is failing for the same reason.</p>"
            }
        }

    private fun page(
        title: String,
        body: String,
    ): String =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>" + escapeHtml(title) + "</title><style>" + STYLE + "</style></head><body>" +
            "<h1><a href=\"/journal\">xyk</a></h1>" + body + "</body></html>"

    private const val STYLE =
        "body{font:14px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;margin:2rem;max-width:64rem}" +
            "h1 a{color:inherit;text-decoration:none}" +
            "table{border-collapse:collapse;width:100%}" +
            "th,td{text-align:left;padding:.3rem .6rem;border-bottom:1px solid #ddd}" +
            "tr.pending td{background:#fffbe6}tr.dead td{background:#ffecec}" +
            ".note{color:#666}.warn{background:#fff3cd;padding:.6rem;border-left:3px solid #e0a800}" +
            "pre{background:#f6f6f6;padding:.8rem;overflow-x:auto}" +
            "dt{float:left;width:12rem;color:#666}dd{margin:0 0 .2rem 12rem}"
}

/**
 * Everything that reaches the page goes through here, including values a sender chose.
 *
 * `Content-Type` is attacker-controlled, and it is rendered next to an operator's own description —
 * which is the shape of the bug where one field's escaping is forgotten because the field beside it
 * looked trusted.
 */
fun escapeHtml(value: String): String {
    val out = StringBuilder(value.length + 16)
    for (char in value) {
        when (char) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(char)
        }
    }
    return out.toString()
}

/**
 * `YYYY-MM-DD HH:MM`, always UTC.
 *
 * Kotlin/Native resolves the current zone without `/usr/share/zoneinfo` and both images render UTC
 * and ignore `TZ` — checked by reading a rendered value elsewhere in the portfolio. Rendering UTC on
 * purpose is the difference between a timestamp that means the same thing everywhere and one that
 * means whatever the container was built with.
 */
fun formatUtc(epochSeconds: Long): String {
    val days = floorDiv(epochSeconds, SECONDS_PER_DAY)
    val secondOfDay = epochSeconds - days * SECONDS_PER_DAY
    var year = 1970
    var remaining = days
    while (true) {
        val length = if (isLeap(year)) 366 else 365
        if (remaining >= length) {
            remaining -= length
            year++
        } else if (remaining < 0) {
            year--
            remaining += if (isLeap(year)) 366 else 365
        } else {
            break
        }
    }
    val lengths = intArrayOf(31, if (isLeap(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 0
    while (remaining >= lengths[month]) {
        remaining -= lengths[month]
        month++
    }
    val day = remaining + 1
    val hour = secondOfDay / 3600
    val minute = (secondOfDay % 3600) / 60
    return "${year.toString().padStart(4, '0')}-${(month + 1).pad()}-${day.pad()} ${hour.pad()}:${minute.pad()}"
}

private const val SECONDS_PER_DAY = 86_400L

private fun floorDiv(
    value: Long,
    divisor: Long,
): Long {
    val quotient = value / divisor
    return if (value % divisor != 0L && (value xor divisor) < 0) quotient - 1 else quotient
}

private fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun Long.pad(): String = toString().padStart(2, '0')

private fun Int.pad(): String = toString().padStart(2, '0')
