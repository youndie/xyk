#!/usr/bin/env python3
"""Survival and peak resident memory per arm, plus the control that had to die."""
import csv
import sys
from collections import defaultdict


def main():
    path, limit = sys.argv[1], sys.argv[2]
    rows = list(csv.DictReader(open(path)))
    control = [r for r in rows if r["limit"] != limit]
    arms = defaultdict(list)
    for row in rows:
        if row["limit"] == limit:
            arms[row["arm"]].append(row)

    died = sum(1 for r in control if r["oom_killed"] != "false")
    print(f"**Control** ({control[0]['limit'] if control else '?'}): {died} of {len(control)} killed.")
    if control and died == 0:
        print("\n**VOID** — nothing died at the control limit, so a survival here means nothing.")
        return 1
    print()
    print("| arm | survived | peak RSS kB (min–max) | threads (max) |")
    print("|---|---|---|---|")
    for arm in ("default", "fixed16", "fixed16-arena2", "std"):
        runs = arms.get(arm, [])
        if not runs:
            continue
        alive = [r for r in runs if r["oom_killed"] == "false"]
        peaks = [int(r["peak_rss_kb"]) for r in alive if r["peak_rss_kb"]]
        threads = [int(r["threads"]) for r in alive if r["threads"]]
        peak_text = f"{min(peaks)}–{max(peaks)}" if peaks else "—"
        print(
            f"| `{arm}` | **{len(alive)}/{len(runs)}** | {peak_text} | "
            f"{max(threads) if threads else '—'} |"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
