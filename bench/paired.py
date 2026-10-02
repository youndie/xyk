#!/usr/bin/env python3
"""The paired per-round difference between two arms of a `bench/memory.sh` run, with its interval.

    bench/paired.py RESULTS.csv --arm A0 --base A1 --metric cpu_per_req --offered 6000 --duration-s 30
    bench/paired.py --selftest

WHY PAIRED. `memory.sh` runs every arm once per round, in a rotating order, so a drift of the host
lands on all arms of a round together. The difference taken inside a round cancels it; two means
taken across rounds do not. The estimator is the mean of the per-round differences in percent of
the base, with a two-sided 95 % Student interval over the counted rounds.

WHICH ROUNDS COUNT, and every exclusion is printed rather than applied silently:

* round 1 is discarded — the first run after a build or a pull warms caches the others find warm;
* a round where either arm was killed, died before serving, or carried no load is excluded: there is
  nothing to compare;
* for a metric that is a cost or a latency, a round where either arm got fewer than 95 % of the
  offered requests answered `200` (`--offered`) is excluded: the offered load was not delivered;
* a round where the host's busy time that is NOT the subject exceeds the run's median of that
  quantity by more than one core (`--duration-s` turns microseconds into cores) is excluded as
  shared with a neighbour.

THE WORDS. `distinguishable` when the interval excludes zero, `below resolution, effect under N %`
when it does not, N being the larger end of the interval — never "zero". Whether a distinguishable
difference clears a threshold is the brief's question, not this script's; it prints the numbers that
question needs.
"""
import argparse
import csv
import math
import statistics
import sys

# two-sided 95 % Student quantiles by degrees of freedom
T975 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262,
        10: 2.228, 11: 2.201, 12: 2.179, 14: 2.145, 19: 2.093, 29: 2.045}


def t975(df):
    keys = sorted(k for k in T975 if k <= df)
    return T975[keys[-1]] if keys else float("nan")


def value(row, metric):
    try:
        if metric == "cpu_per_req":
            return int(row["cpu_usec"]) / int(row["reqs_ok"])
        return float(row[metric])
    except (KeyError, ValueError, ZeroDivisionError):
        return None


def alive(row):
    return row["oom_killed"] == "false"


def nonsubject(row):
    try:
        return int(row["host_busy_usec"]) - int(row["cpu_usec"])
    except (KeyError, ValueError):
        return None


def compare(rows, arm, base, metric, offered=None, duration_s=None, discard=1, out=sys.stdout):
    by = {(r["arm"], int(r["round"])): r for r in rows}
    rounds = sorted({int(r["round"]) for r in rows if r["arm"] in (arm, base)})
    noise_limit = None
    if duration_s:
        ns = [nonsubject(r) for r in rows if alive(r) and nonsubject(r) is not None]
        if ns:
            noise_limit = statistics.median(ns) + duration_s * 1_000_000
    diffs, notes = [], []
    for rnd in rounds:
        a, b = by.get((arm, rnd)), by.get((base, rnd))
        if rnd <= discard:
            notes.append(f"round {rnd}: discarded (warm-up)")
            continue
        if not a or not b:
            notes.append(f"round {rnd}: an arm is missing")
            continue
        if not (alive(a) and alive(b)):
            notes.append(f"round {rnd}: excluded, {arm}={a['oom_killed']} {base}={b['oom_killed']}")
            continue
        if offered and metric != "peak_rss_kb":
            short = [r["arm"] for r in (a, b) if not r["reqs_ok"] or int(r["reqs_ok"]) < 0.95 * offered]
            if short:
                notes.append(f"round {rnd}: excluded, under 95 % of {offered} answered: {short}")
                continue
        if noise_limit is not None:
            loud = [r["arm"] for r in (a, b) if (nonsubject(r) or 0) > noise_limit]
            if loud:
                notes.append(f"round {rnd}: excluded, the host was busy beside the subject: {loud}")
                continue
        va, vb = value(a, metric), value(b, metric)
        if va is None or vb is None or vb == 0:
            notes.append(f"round {rnd}: excluded, {metric} not readable")
            continue
        diffs.append((rnd, va, vb, (va - vb) / vb * 100))
    for n in notes:
        print(f"  {n}", file=out)
    for rnd, va, vb, d in diffs:
        print(f"  round {rnd}: {arm}={va:.4g} {base}={vb:.4g} diff={d:+.2f} %", file=out)
    if len(diffs) < 2:
        print(f"{arm} vs {base}, {metric}: {len(diffs)} counted round(s) — no interval", file=out)
        return None
    ds = [d for *_, d in diffs]
    mean = statistics.mean(ds)
    half = t975(len(ds) - 1) * statistics.stdev(ds) / math.sqrt(len(ds))
    lo, hi = mean - half, mean + half
    word = ("distinguishable" if lo > 0 or hi < 0
            else f"below resolution, effect under {max(abs(lo), abs(hi)):.1f} %")
    print(f"{arm} vs {base}, {metric}: {mean:+.2f} % [{lo:+.2f}, {hi:+.2f}] 95 % CI, "
          f"n={len(ds)}, median {arm}={statistics.median(v for _, v, _, _ in diffs):.4g} "
          f"{base}={statistics.median(v for _, _, v, _ in diffs):.4g} — {word}", file=out)
    return mean, lo, hi, len(ds)


def selftest():
    """Known input in, known verdict out: a reader that cannot fail proves nothing."""
    import io
    head = ("arm,limit,round,oom_killed,peak_rss_kb,threads,cpu_usec,reqs_ok,reqs,dropped,p50_ms,"
            "p99_ms,host_busy_usec,arena_env,arenas,load1,subject_cpus").split(",")

    def row(arm, rnd, cpu, killed="false", ok=6000, host=None):
        r = dict.fromkeys(head, "")
        r.update(arm=arm, limit="64m", round=str(rnd), oom_killed=killed, peak_rss_kb="50000",
                 cpu_usec=str(cpu), reqs_ok=str(ok), reqs=str(ok),
                 host_busy_usec=str(host if host is not None else cpu + 10_000_000))
        return r

    jitter = [0, 1.0, -0.8, 0.5, -0.3, 0.9, -0.6]
    # +20 % with a 1 % jitter must be distinguishable and land near 20
    rows = []
    for rnd in range(1, 7):
        rows.append(row("b", rnd, 6_000_000))
        rows.append(row("a", rnd, int(6_000_000 * (1.20 + jitter[rnd] / 100))))
    res = compare(rows, "a", "b", "cpu_per_req", offered=6000, duration_s=30, out=io.StringIO())
    assert res and res[1] > 15 and abs(res[0] - 20) < 2, f"known +20 % read as {res}"
    # the same arm against itself must NOT be distinguishable
    rows = []
    for rnd in range(1, 7):
        rows.append(row("b", rnd, 6_000_000))
        rows.append(row("a", rnd, int(6_000_000 * (1 + jitter[rnd] / 100))))
    res = compare(rows, "a", "b", "cpu_per_req", offered=6000, duration_s=30, out=io.StringIO())
    assert res and res[1] < 0 < res[2], f"an arm against itself read as {res}"
    # a killed round, a short round and a noisy round must each drop out — and round 1 always
    rows = []
    for rnd in range(1, 7):
        rows.append(row("b", rnd, 6_000_000))
        rows.append(row("a", rnd, 6_000_000 * 2 if rnd in (2, 3, 4) else 6_000_000,
                        killed="true" if rnd == 2 else "false",
                        ok=1000 if rnd == 3 else 6000,
                        host=6_000_000 * 2 + 10_000_000 + 60_000_000 if rnd == 4 else None))
    buf = io.StringIO()
    res = compare(rows, "a", "b", "cpu_per_req", offered=6000, duration_s=30, out=buf)
    text = buf.getvalue()
    assert "round 1: discarded" in text and "round 2: excluded" in text, text
    assert "round 3: excluded, under 95 %" in text and "round 4: excluded, the host was busy" in text, text
    assert res and abs(res[0]) < 0.01, f"exclusions leaked a doubled round in: {res}"
    print("paired.py selftest: 3 of 3 known inputs read as they must")
    return 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("csv", nargs="?")
    p.add_argument("--arm")
    p.add_argument("--base")
    p.add_argument("--metric", default="cpu_per_req")
    p.add_argument("--limit", help="only rows at this limit (the control's rows are always ignored)")
    p.add_argument("--offered", type=int)
    p.add_argument("--duration-s", type=float)
    p.add_argument("--selftest", action="store_true")
    a = p.parse_args()
    if a.selftest:
        return selftest()
    rows = list(csv.DictReader(open(a.csv)))
    limits = {r["limit"] for r in rows}
    limit = a.limit or max(limits, key=lambda x: sum(1 for r in rows if r["limit"] == x))
    rows = [r for r in rows if r["limit"] == limit]
    return 0 if compare(rows, a.arm, a.base, a.metric, a.offered, a.duration_s) else 1


if __name__ == "__main__":
    sys.exit(main())
