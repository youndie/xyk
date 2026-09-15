# xyk

A durable webhook gateway in a very small container.

xyk accepts a webhook from anyone who sends one — GitHub, Telegram, Stripe, or anything that can
POST — proves it is genuine, **writes it down before answering**, and then delivers it to every
subscriber with retries, backoff and per-attempt timeouts. A page shows an operator what arrived and
what happened to it, attempt by attempt.

The promise is narrow and load-bearing: the event row and the timers that will deliver it are
committed in **one transaction**, so an accepted webhook cannot go missing between "we said `200`"
and "somebody will deliver it".

It is written in Kotlin/Native on Ktor with SQLite, which is the second reason this repository
exists: to find out, against numbers declared before the code, whether that platform can hold the
promise inside a container small enough to run one per project. The same scenario is measured against
a Go twin of the ingest path, in three columns.

**Status: nothing is built yet.** The documentation describes what will be, and says so — every
document is `status: draft`, every code path named in it is a path the backlog is about to create.

## Where to start

- **[docs/](docs/)** — the layered documentation. Start with
  [the research](docs/research/research-architecture.md): what was verified and against what, which
  decisions were taken and what was rejected.
- **[backlog.md](backlog.md)** — the order of work, and the five criteria declared before the code,
  each with the measurement that will decide it and one prediction recorded so it can be wrong in
  public.

## License

MIT — see [LICENSE](LICENSE).
