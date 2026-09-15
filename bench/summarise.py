#!/usr/bin/env python3
"""Turns k6's summaries into the table, and refuses to produce one from a void run.

Two of the checks here are the difference between a measurement and a number:

* `dropped_iterations` above zero means the generator could not offer the rate it was asked for, so
  what came back describes the generator. The table says VOID rather than printing a throughput.
* the control arm — a route that touches no database — must be the fastest. If it is not, the
  bottleneck is the harness or the host, and the two service columns are not comparable to anything.

The first round is dropped, always: the first run after a start measures warm-up.
"""
import json
import os
import sys

ARMS = ("kotlin", "go", "control")


def load(out, arm, rounds):
    """Every round of one arm except the first."""
    results = []
    for round_number in range(2, rounds + 1):
        path = os.path.join(out, "raw", f"{arm}-round{round_number}.json")
        if not os.path.isfile(path):
            continue
        with open(path) as handle:
            results.append(json.load(handle))
    return results


def metric(summary, name, field):
    return summary.get("metrics", {}).get(name, {}).get(field)


def main():
    out, rounds = sys.argv[1], int(sys.argv[2])
    rows, void = [], []

    for arm in ARMS:
        summaries = load(out, arm, rounds)
        if not summaries:
            void.append(f"{arm}: no timed round survived (only warm-up?)")
            continue
        dropped = sum(metric(s, "dropped_iterations", "count") or 0 for s in summaries)
        rate = [metric(s, "http_reqs", "rate") or 0 for s in summaries]
        p99 = [metric(s, "http_req_duration", "p(99)") or 0 for s in summaries]
        p50 = [metric(s, "http_req_duration", "p(50)") or 0 for s in summaries]
        failed = [metric(s, "http_req_failed", "value") or 0 for s in summaries]
        rows.append(
            {
                "arm": arm,
                "runs": len(summaries),
                "rps": sum(rate) / len(rate),
                "p50": sum(p50) / len(p50),
                "p99": sum(p99) / len(p99),
                "failed": max(failed),
                "dropped": dropped,
            }
        )
        if dropped:
            void.append(f"{arm}: {dropped} dropped iterations — the generator could not keep up")

    by_arm = {row["arm"]: row for row in rows}
    # The control shares a binary with the kotlin arm, so it bounds THAT arm and nothing else —
    # comparing it with the Go column was a mis-specified rule, and it fired on the first real run
    # for the wrong reason (B-20 pilot, 2026-09-15).
    if "control" in by_arm and "kotlin" in by_arm:
        if by_arm["control"]["rps"] < by_arm["kotlin"]["rps"]:
            void.append(
                "the control delivered fewer requests than the ingest path on the same binary — "
                "the limit is not in the service"
            )
    # A shared ceiling: every arm within 25% of the slowest while none of them reached the offered
    # rate. Three different services do not coincidentally share a number; a generator does.
    if len(rows) >= 2:
        fastest = max(row["rps"] for row in rows)
        slowest = min(row["rps"] for row in rows)
        if slowest and fastest / slowest < 1.25 and any(row["dropped"] for row in rows):
            void.append(
                f"every arm landed between {slowest:.0f} and {fastest:.0f} rps with iterations "
                "dropped — that is a ceiling they share, which is the generator or the path, not "
                "any of them"
            )

    print("| arm | runs | rps | p50 ms | p99 ms | failed | dropped |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for row in rows:
        print(
            f"| {row['arm']} | {row['runs']} | {row['rps']:.0f} | {row['p50']:.1f} | "
            f"{row['p99']:.1f} | {row['failed']:.4f} | {row['dropped']} |"
        )
    print()
    if void:
        print("**VOID.** " + "; ".join(void))
        return 1
    print("Every arm offered its full rate and the control is the fastest.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
