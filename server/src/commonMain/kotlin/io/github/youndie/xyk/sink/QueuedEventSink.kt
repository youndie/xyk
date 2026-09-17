package io.github.youndie.xyk.sink

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The other shape of the same sink: **[publish] returns before the broker has acknowledged**.
 *
 * It exists to be measured, not to ship. The default sink awaits the acknowledgement inside the
 * request, which is a deliberate choice with its cost written down — and a consequence nobody had
 * noticed until [kafkakn's B-19](https://github.com/youndie/kafkakn) measured it: with that shape a
 * record is either inside somebody's `send` or finished, so `close` never has anything to flush, and
 * half of the producer contract's promise about `close` was never exercised by anything.
 *
 * This arm puts a bounded queue in front of the producer, which is what most services on an ingress
 * path actually do, because nobody wants a webhook's `200` to wait for Kafka. It is switched on by
 * `XYK_KAFKA_QUEUE` and off by default; see kafkakn's B-23.
 *
 * **The queue makes a new kind of loss possible, and telling the two apart is the whole point.** A
 * record that reached [EventSink.publish] on the delegate and never arrived is the producer's
 * question. A record that was accepted, queued, and never reached the delegate at all is an
 * **outbox** question — whether a service should record its intent and reconcile later — and that is
 * not about the producer. So [onAsked] is called immediately before the delegate, and the run's log
 * is what classifies every missing record afterwards.
 */
class QueuedEventSink(
    private val delegate: EventSink,
    capacity: Int,
    /** Called with the event id immediately **before** the delegate is asked to publish it. */
    private val onAsked: (String) -> Unit,
    /** Called when the delegate refused. The request is long gone, so nobody else can report this. */
    private val onFailure: (String, Throwable) -> Unit,
    /**
     * Called with how many records were still queued when [close] was cut short, and never on a
     * clean drain.
     *
     * **A third cause of loss, which the classifier would otherwise misfile.** `close` is a shutdown
     * participant with a stage deadline over it, so a drain that needs longer is cancelled, and the
     * records left behind were accepted and queued but never asked. From outside they look exactly
     * like the outbox case — a row with nothing on the topic — but their cause is this deadline and
     * neither the producer nor the absence of an outbox. Counting them here is what keeps the run's
     * arithmetic honest.
     */
    private val onUndrained: (Int) -> Unit = {},
) : EventSink {
    // BOUNDED, so that a slow broker becomes backpressure on the ingress rather than memory. An
    // unbounded queue would turn this arm into a measurement of how fast the machine runs out of RAM.
    private val queue = Channel<AcceptedRecord>(capacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drained = CompletableDeferred<Unit>()

    init {
        scope.launch {
            // IN A `finally`, and that is not tidiness. `close` waits on this deferred, so any exit
            // from the loop that does not complete it — a throwing `onAsked`, a cancellation — is a
            // `close` that never returns. Bounded by the shutdown stage's deadline and therefore
            // indistinguishable, from outside, from the slow drain this arm is meant to measure.
            try {
                // `for (record in queue)` ends when the channel is closed AND empty, which is what
                // makes `close` below a drain rather than a discard.
                for (record in queue) {
                    onAsked(record.eventId)
                    try {
                        delegate.publish(record)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        // Reported, never swallowed, and it must not end the loop: one record the
                        // broker refused is not a reason to drop the ones behind it.
                        onFailure(record.eventId, failure)
                    }
                }
            } finally {
                drained.complete(Unit)
            }
        }
    }

    /** Returns as soon as the record is queued — suspending only while the queue is full. */
    override suspend fun publish(record: AcceptedRecord) {
        queue.send(record)
    }

    /**
     * Stops accepting, **drains what is already queued**, and only then closes the producer.
     *
     * This is the half of the contract B-19 could not reach. It can exceed the shutdown stage's
     * deadline — every queued record still costs a `send`, and against a broker that is not answering
     * each one costs `message.timeout.ms` — and that is a timing defect to report rather than a
     * reason to discard the queue.
     */
    override suspend fun close() {
        queue.close()
        try {
            drained.await()
        } finally {
            // COUNTED HERE BECAUSE THIS IS THE ONLY PLACE THAT STILL RUNS. A stage deadline cancels
            // the `await` above, and everything after it never happens — so the count belongs in a
            // `finally`, and the loop is stopped first so that nothing else is consuming the channel
            // while it is counted. A record the loop had already handed to `delegate.publish` was
            // asked for and is the producer's, not this one's: it left the queue before the count.
            scope.cancel()
            var left = 0
            while (queue.tryReceive().isSuccess) left++
            if (left > 0) onUndrained(left)
        }
        delegate.close()
    }
}
