#!/usr/bin/env bash
# g1-read.sh SERIES OFFERED — every comparison the brief names, from raw/SERIES/results.csv
S=$1 OFF=$2; shift 2
p() { python3 wt-xyk-g1/bench/paired.py "raw/$S/results.csv" --offered "$OFF" --duration-s 30 "$@"; }
for m in ${METRICS:-cpu_per_req p99_ms p50_ms peak_rss_kb reqs_ok}; do
  echo "## $S $m"
  p --arm A0b --base A0 --metric "$m"
  p --arm A0 --base A1 --metric "$m"
  [ -n "${WITH_C:-}" ] && { p --arm A1 --base C --metric "$m"; p --arm A0 --base C --metric "$m"; }
done
