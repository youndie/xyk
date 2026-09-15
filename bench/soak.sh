#!/usr/bin/env bash
# B-24: the journal read while ingest writes, for long enough to find the cliff.
#
#   bench/soak.sh --arm swept|unswept [--minutes 20] [--limit 64m]
#
# WHY THIS ONE IS LONG AND THE OTHERS ARE NOT. The failure it looks for does not appear in a short
# run: elsewhere in this portfolio it arrived at minute 33 with a checkpoint on a timer and at minute
# 65 without one. A ten-second measurement of it would be a measurement of nothing, and — worse — a
# green one.
#
# THE MECHANISM, so that the columns below are read for what they are: SQLite's automatic checkpoint
# is only ever PASSIVE, and it does not truncate the journal while any reader is alive. The journal
# page is that reader. So the `-wal` file grows, **the database file stops growing** because the data
# is in the journal, reads get more expensive, the number of requests in flight rises, threads follow,
# and the process is killed on memory with a small heap. Watching the database size shows nothing at
# all; the columns here are the journal, the threads and the cgroup's own accounting.
#
# THE CONTROL IS THE `unswept` ARM. If it does not misbehave, this soak has not reproduced the
# mechanism and the `swept` arm's good behaviour says nothing about the mitigation.
set -uo pipefail

ARM=swept
MINUTES=20
LIMIT=64m
RATE=${RATE:-200}
READERS=${READERS:-4}
SUBJECT_CPUS=${SUBJECT_CPUS:-0-3}
GENERATOR_CPUS=${GENERATOR_CPUS:-12-19}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.54.0}
SECRET=bench-secret
ENDPOINT=hook-1

while [ $# -gt 0 ]; do
  case "$1" in
    --arm) ARM=$2; shift 2 ;;
    --minutes) MINUTES=$2; shift 2 ;;
    --limit) LIMIT=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$ARM" in
  swept)   CHECKPOINT_SECONDS=30 ;;
  unswept) CHECKPOINT_SECONDS=0 ;;   # the control: SQLite's own PASSIVE behaviour and nothing else
  *) echo "arm must be swept or unswept" >&2; exit 2 ;;
esac

DATA=/tmp/xyk-soak-$ARM
OUT=${OUT:-/tmp/xyk-soak-$(date +%H%M%S)-$ARM}
NAME=soak-$ARM
mkdir -p "$OUT"
rm -rf "$DATA"; mkdir -p "$DATA"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; pkill -P $$ >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker rm -f "$NAME" >/dev/null 2>&1
docker run -d --name "$NAME" --memory="$LIMIT" --memory-swap="$LIMIT" \
  --cpuset-cpus="$SUBJECT_CPUS" -p 8064:8080 -v "$DATA":/data \
  -e XYK_BOOTSTRAP_ENDPOINT_ID="$ENDPOINT" -e XYK_BOOTSTRAP_SECRET="$SECRET" \
  -e XYK_BOOTSTRAP_SUBSCRIBERS="https://sink.invalid/a" \
  -e XYK_WAL_CHECKPOINT_SECONDS="$CHECKPOINT_SECONDS" \
  xyk-mem:fixed16 >/dev/null || { echo "soak: could not start" >&2; exit 2; }

for _ in $(seq 1 60); do
  sleep 0.5
  curl -sf -o /dev/null http://127.0.0.1:8064/health/ready && break
done

BODY='{"zen":"Non-blocking is better than blocking."}'
SIGNATURE=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/.*= //')

# THE SCENARIO IS STAGED SOMEWHERE THE GENERATOR CAN READ IT. The working tree here is a mutagen
# replica whose files are mode 0600, and the k6 image runs as a non-root user: mounting `bench/`
# directly gives `stat /bench/ingest.js: permission denied`, the writer exits immediately, and the
# sampler then records a perfectly flat, perfectly green twenty minutes of nothing happening.
STAGE=$(mktemp -d); chmod 755 "$STAGE"
SCENARIO=$STAGE/ingest.js
cp bench/ingest.js "$SCENARIO"
chmod 644 "$SCENARIO"

# The writer.
docker run --rm --network host --cpuset-cpus="$GENERATOR_CPUS" -v "$SCENARIO":/ingest.js \
  -e TARGET="http://127.0.0.1:8064/hooks/$ENDPOINT" -e ARM=ingest -e RATE="$RATE" \
  -e DURATION="${MINUTES}m" -e CONNECTIONS=50 -e BODY="$BODY" -e SIGNATURE="$SIGNATURE" \
  "$K6_IMAGE" run --quiet /ingest.js > "$OUT/ingest.log" 2>&1 &

# The readers, which are the point: without an overlapping reader SQLite checkpoints on its own and
# there is nothing to measure.
for _ in $(seq 1 "$READERS"); do
  ( while true; do curl -s -m 30 -o /dev/null http://127.0.0.1:8064/journal; sleep 1; done ) &
done

# IS THE WRITER WRITING? Asked thirty seconds in, before twenty minutes are spent on a graph of
# nothing. The first version of this soak had a writer that never started, and every sample it took
# was flat, alive and `200` — a green run that measured the absence of load.
sleep 30
events_at_30s=$(curl -s -m 10 "http://127.0.0.1:8064/api/events?limit=1" | grep -o '"id"' | wc -l)
if [ "${events_at_30s:-0}" -eq 0 ]; then
  echo "soak: nothing has been stored after 30 s — the writer is not writing. Aborting." >&2
  echo "      (see $OUT/ingest.log)" >&2
  exit 1
fi
echo "writer confirmed: events exist after 30 s"

echo "second,wal_kb,db_kb,mem_current_kb,threads,alive,ready" > "$OUT/samples.csv"
cgroup=/sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' "$NAME").scope
started=$(date +%s)
while [ $(( $(date +%s) - started )) -lt $(( MINUTES * 60 )) ]; do
  alive=$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)
  pid=$(docker inspect -f '{{.State.Pid}}' "$NAME" 2>/dev/null)
  printf '%s,%s,%s,%s,%s,%s,%s\n' \
    "$(( $(date +%s) - started ))" \
    "$(du -k "$DATA/xyk.db-wal" 2>/dev/null | cut -f1)" \
    "$(du -k "$DATA/xyk.db" 2>/dev/null | cut -f1)" \
    "$(awk '{printf "%d", $1/1024}' "$cgroup/memory.current" 2>/dev/null)" \
    "$(grep Threads "/proc/$pid/status" 2>/dev/null | awk '{print $2}')" \
    "${alive:-false}" \
    "$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8064/health/ready)" \
    >> "$OUT/samples.csv"
  [ "$alive" = "true" ] || { echo "soak: the container is gone at $(( $(date +%s) - started ))s" | tee -a "$OUT/verdict.txt"; break; }
  sleep 10
done

killed=$(awk '/^oom_kill /{print $2}' "$cgroup/memory.events" 2>/dev/null)
{
  echo "arm=$ARM limit=$LIMIT minutes=$MINUTES rate=$RATE readers=$READERS checkpoint_seconds=$CHECKPOINT_SECONDS"
  echo "oom_kill=${killed:-unknown}"
  echo "wal kB: $(tail -n +2 "$OUT/samples.csv" | cut -d, -f2 | sort -n | tail -1) peak"
  echo "threads: $(tail -n +2 "$OUT/samples.csv" | cut -d, -f5 | sort -n | tail -1) peak"
} | tee -a "$OUT/verdict.txt"
echo "samples: $OUT/samples.csv"
