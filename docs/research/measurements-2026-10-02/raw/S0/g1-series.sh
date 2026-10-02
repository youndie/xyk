#!/usr/bin/env bash
# One series of B-32 from the Mac: wait for a quiet box (30 min at most), run, ask the runner again.
#   g1-series.sh NAME LIMIT RATE CONNECTIONS "ARMS" [ROUNDS] [DURATION]
set -uo pipefail
cd "$(dirname "$0")"
NAME=$1 LIMIT=$2 RATE=$3 CONN=$4 ARMS=$5 ROUNDS=${6:-6} DURATION=${7:-30s}
LOG=g1-logs/$NAME.log
: > "$LOG"
quiet=no
for i in $(seq 1 31); do
  out=$(./g1-quiet.sh); echo "quiet-check: $(head -1 <<<"$out") -> $(tail -1 <<<"$out")" | tee -a "$LOG"
  grep -q '^QUIET' <<<"$out" && { quiet=yes; break; }
  [ "$i" -lt 31 ] && sleep 60
done
if [ "$quiet" != yes ]; then echo "NOT MEASURED: the box did not become quiet within 30 min" | tee -a "$LOG"; exit 3; fi
echo "series start $(date '+%F %T')" | tee -a "$LOG"
ssh -p 2222 youndie@127.0.0.1 'touch /tmp/g1-series-start' 
ssh -o ServerAliveInterval=30 -p 2222 youndie@127.0.0.1 "cd ~/xyk-g1 && rm -rf ~/xyk-g1-raw/$NAME && mkdir -p ~/xyk-g1-raw && \
  OUT=\$HOME/xyk-g1-raw/$NAME ARMS='$ARMS' CONTROL_ARM=A0 RATE=$RATE CONNECTIONS=$CONN \
  bash bench/memory.sh --limit $LIMIT --rounds $ROUNDS --duration $DURATION" >> "$LOG" 2>&1
echo "series rc=$? end $(date '+%F %T')" | tee -a "$LOG"
echo "post-check: $(./g1-quiet.sh | head -1)" | tee -a "$LOG"
echo "runner jobs that started during the series: $(ssh -p 2222 youndie@127.0.0.1 'find ~/actions-runner/_diag -name "Worker_*.log" -newer /tmp/g1-series-start | wc -l')" | tee -a "$LOG"
