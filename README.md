# xyk

A durable webhook gateway in a very small container.

xyk accepts a webhook from anyone who sends one — GitHub, Telegram, Stripe, or anything that can
POST — proves it is genuine, **writes it down before answering**, and then delivers it to every
subscriber with retries, backoff and per-attempt timeouts. A page shows an operator what arrived and
what happened to it, attempt by attempt.

The promise is narrow and load-bearing: the event row and the timer that will deliver it are
committed in **one transaction**, so an accepted webhook cannot go missing between "we said `200`"
and "somebody will deliver it".

It is written in Kotlin/Native on Ktor with SQLite, which is the second reason this repository
exists: to find out, against numbers declared before the code, whether that platform can hold the
promise inside a container small enough to run one per project. The same scenarios are measured
against a Go twin of the ingest path.

## The criteria, declared before the code and answered after it

| Criterion | Answer | Where |
|---|---|---|
| 2 000 rps on ingest over 200 connections, no slow state | **No.** 437 rps on four visible cores; nothing failed, the latency is 300–460 ms where 100 is needed. The Go twin misses it too, at 601 | [three columns](docs/research/measurements-2026-09-16/throughput-three-columns.md) |
| 64 MiB limit, ten runs out of ten | **Yes**, on the shipping configuration — and by reclaim rather than headroom | [memory](docs/research/measurements-2026-09-16/memory-shipping-config.md) |
| image at most 10 MB | **No**, by 1.74 MiB, on purpose — the excess is the charset converters without which the journal page returns `500` | [B-23](docs/backlog/B-23-criterion-image-size.md) |
| cold start to the first `200` under a second on a k0s node | **Yes**, 0.394 s — and indistinguishable from the Go twin's 0.395 | [cold start](docs/research/measurements-2026-09-16/cold-start.md) |
| the kill condition: *if curl makes a static link impossible, that is a result* | Not triggered — all four link modes link; curl costs 8.8 MB | [B-05](docs/backlog/B-05-static-link-probe.md) |

Three of the five are met and two are missed, and the two that are missed are written down as
missed rather than restated to fit. **What the numbers cost to get is the more useful half of this
repository** — several of them were wrong first, and the corrections are kept at the point of
divergence rather than tidied away.

## Where to start

- **[docs/research/research-architecture.md](docs/research/research-architecture.md)** — what was
  verified and against what, which decisions were taken and what was rejected, and twenty-six facts
  that cost something to learn. Read this first; the obvious thing here is frequently wrong.
- **[backlog.md](backlog.md)** — twenty-nine items, all closed, each carrying what it found.
- **[docs/](docs/)** — features, API reference, and how the service is put together.

A few of those facts, as an index of what is inside:

- a storage symptom — a journal growing under concurrent readers — whose cause was **one missing
  composite index** (§1.26);
- a load generator that never started and produced a table of ten survivals of a service nothing
  was talking to (§1.20);
- a virtual user is not a connection, and sizing a pool by one caps the other (§1.23);
- a failure that aborts the runtime cannot be caught downstream of itself (§1.24);
- cold start does not separate Kotlin/Native from Go (§1.25).

## Running it

```bash
make check     # documents + ./gradlew check — what CI runs
make build     # link, image, and the assertion that the process stops in order
```

The service needs one variable, `XYK_DB_PATH`, and refuses to start without it. Everything else has
a default; the ones worth knowing are `XYK_RETENTION_DAYS` (7 — payload bodies are purged after a
week, `0` keeps them for ever) and `XYK_DELIVERY_WORKERS` (4, measured rather than guessed). The
full table is in [services/xyk-server.md](docs/services/xyk-server.md), which also carries the
sentence an operator most needs: **the volume is as sensitive as the secrets in it.**

Outbound HTTPS is a build variant — `-Pxyk.httpClient=true` links `ktor-client-curl`, which is the
only engine that speaks TLS on Kotlin/Native and costs 8.8 MB of image. A build without it accepts
and journals webhooks and says at start-up that it will not deliver them.

## License

MIT — see [LICENSE](LICENSE).
