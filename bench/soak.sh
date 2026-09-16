#!/usr/bin/env bash
# B-24: the journal read while ingest writes, for long enough to find the cliff.
#
#   bench/soak.sh --arm swept|unswept [--minutes 20] [--limit 64m]
#                 [--delivery on|off] [--image TAG] [--rate N]
#
# THE SUBJECT HAS TO BE THE SERVICE THAT SHIPS, AND FOR TWO RUNS IT WAS NOT. The soaks of
# 2026-09-15 and 2026-09-16 ran `xyk-mem:fixed16`, an allocator arm of the memory bench, which
# links no outbound HTTP engine at all: `XYK_BOOTSTRAP_SUBSCRIBERS` was set, nothing could ever
# deliver, and every number they took belongs to a service with half its work missing. That is the
# same defect B-29 found in the memory criterion and fixed there. `--delivery on` is the fix here,
# and it is enforced rather than trusted — the subject is asked whether it can deliver, and a
# subject that says it cannot ends the run instead of producing four hours of flat green.
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
DELIVERY=off
IMAGE=${IMAGE:-xyk-mem:fixed16}
# 9101 rather than delivery.sh's 9100, so a soak and a delivery sweep on one host do not silently
# feed each other's counters.
SINK_PORT=${SINK_PORT:-9101}
SINK_DELAY_MS=${SINK_DELAY_MS:-0}
SINK_NAME=soak-sink
# An address of your own instead of the harness's sink. The arm it exists for is a subscriber that
# is *down*, which is the most ordinary condition a webhook gateway meets and the one nobody had
# measured — see B-31.
SUBSCRIBER_OVERRIDE=${SUBSCRIBER_OVERRIDE:-}

while [ $# -gt 0 ]; do
  case "$1" in
    --arm) ARM=$2; shift 2 ;;
    --minutes) MINUTES=$2; shift 2 ;;
    --limit) LIMIT=$2; shift 2 ;;
    --delivery) DELIVERY=$2; shift 2 ;;
    --image) IMAGE=$2; shift 2 ;;
    --rate) RATE=$2; shift 2 ;;
    --subscriber) SUBSCRIBER_OVERRIDE=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$ARM" in
  # `swept` is 30 s because that is what the B-24 comparison used; `shipping` is the default the
  # service actually carries. They are different numbers and the arm says which one a run took,
  # rather than a measurement of the shipping service quietly using a sweep it does not ship with.
  swept)    CHECKPOINT_SECONDS=30 ;;
  shipping) CHECKPOINT_SECONDS=60 ;;
  unswept)  CHECKPOINT_SECONDS=0 ;;  # the control: SQLite's own PASSIVE behaviour and nothing else
  *) echo "arm must be swept, shipping or unswept" >&2; exit 2 ;;
esac

DATA=/tmp/xyk-soak-$ARM
OUT=${OUT:-/tmp/xyk-soak-$(date +%H%M%S)-$ARM}
NAME=soak-$ARM
mkdir -p "$OUT"
rm -rf "$DATA"; mkdir -p "$DATA"

cleanup() {
  docker rm -f "$NAME" "$SINK_NAME" >/dev/null 2>&1 || true
  pkill -P $$ >/dev/null 2>&1 || true
}
trap cleanup EXIT

# The subscriber. On the generator's cores, because it is instrumentation and must not be paid for
# out of the budget under measurement.
if [ "$DELIVERY" = on ] || [ "$DELIVERY" = noop ]; then
  SINK_STAGE=$(mktemp -d); chmod 755 "$SINK_STAGE"
  cp bench/delivery-sink.py "$SINK_STAGE/sink.py"; chmod 644 "$SINK_STAGE/sink.py"
  docker rm -f "$SINK_NAME" >/dev/null 2>&1
  docker run -d --name "$SINK_NAME" --network host --cpuset-cpus="$GENERATOR_CPUS" \
    -v "$SINK_STAGE":/s -e SINK_DELAY_MS="$SINK_DELAY_MS" -e SINK_PORT="$SINK_PORT" \
    python:3-slim python /s/sink.py >/dev/null || { echo "soak: no sink" >&2; exit 2; }
  for _ in $(seq 1 40); do
    sleep 0.5
    curl -sf -o /dev/null "http://127.0.0.1:$SINK_PORT/" && break
  done
  curl -sf -o /dev/null "http://127.0.0.1:$SINK_PORT/" || {
    echo "soak: the sink never answered on $SINK_PORT" >&2
    docker logs "$SINK_NAME" 2>&1 | tail -5 >&2
    exit 2
  }
  # `host.docker.internal`, NOT `127.0.0.1`. The subject runs on the bridge network — the previous
  # soaks did and changing it would change the subject — so loopback inside it is the container
  # itself and the sink is simply unreachable. Which is not a quiet failure: see the guard below.
  SUBSCRIBER="http://host.docker.internal:$SINK_PORT/hook"
  # An override replaces the address and nothing else: the sink still runs, so the arm differs from
  # the others by where deliveries go and by nothing about the stand.
  [ -n "$SUBSCRIBER_OVERRIDE" ] && SUBSCRIBER="$SUBSCRIBER_OVERRIDE"
else
  # Unreachable on purpose: with delivery off there is nothing to deliver with, and a real address
  # would only make the arm look like it was doing something.
  SUBSCRIBER="https://sink.invalid/a"
fi

docker rm -f "$NAME" >/dev/null 2>&1
docker run -d --name "$NAME" --memory="$LIMIT" --memory-swap="$LIMIT" \
  --cpuset-cpus="$SUBJECT_CPUS" -p 8064:8080 -v "$DATA":/data \
  --add-host=host.docker.internal:host-gateway \
  -e XYK_BOOTSTRAP_ENDPOINT_ID="$ENDPOINT" -e XYK_BOOTSTRAP_SECRET="$SECRET" \
  -e XYK_BOOTSTRAP_SUBSCRIBERS="$SUBSCRIBER" \
  -e XYK_WAL_CHECKPOINT_SECONDS="$CHECKPOINT_SECONDS" \
  -e XYK_HEAP_BYTES="${HEAP_BYTES:-0}" \
  "$IMAGE" >/dev/null || { echo "soak: could not start $IMAGE" >&2; exit 2; }

for _ in $(seq 1 60); do
  sleep 0.5
  curl -sf -o /dev/null http://127.0.0.1:8064/health/ready && break
done

# ASK THE SUBJECT WHETHER IT CAN DELIVER. It says so itself, once, at start-up — and for two runs
# nobody read the line. A build with no outbound engine under `--delivery on` is not a quieter
# measurement, it is a measurement of something else with the same column headings.
if [ "$DELIVERY" = on ] || [ "$DELIVERY" = noop ]; then
  if docker logs "$NAME" 2>&1 | grep -q "delivery is OFF"; then
    echo "soak: $IMAGE links no outbound HTTP engine, so --delivery on measures nothing." >&2
    docker logs "$NAME" 2>&1 | grep "delivery is OFF" >&2
    exit 2
  fi
fi

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
  # ASK WHO IS MISSING BEFORE NAMING THEM. The first version of this guard said "the writer is not
  # writing" whatever the cause, and the first time it fired the writer was writing perfectly well —
  # the subject had died under its limit, which is the opposite finding and the more interesting one.
  # A guard that can only say one thing says it even when it is wrong.
  alive=$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)
  oom=$(docker inspect -f '{{.State.OOMKilled}}' "$NAME" 2>/dev/null)
  exitcode=$(docker inspect -f '{{.State.ExitCode}}' "$NAME" 2>/dev/null)
  if [ "$alive" != "true" ]; then
    echo "soak: the SUBJECT is gone after 30 s (OOMKilled=$oom exit=$exitcode) — not a writer problem." >&2
    docker logs "$NAME" 2>&1 | tail -15 >&2
  else
    echo "soak: nothing has been stored after 30 s and the subject is alive — the writer is not writing." >&2
    echo "      (see $OUT/ingest.log)" >&2
  fi
  exit 1
fi
echo "writer confirmed: events exist after 30 s"

# AND IS ANYTHING ARRIVING AT THE SUBSCRIBER? The writer writing does not mean the outbound half is
# working, and an unreachable subscriber is not a quieter run — it is every delivery failing and
# retrying. This guard exists because the first wiring pointed the subject at `127.0.0.1`, which
# inside a bridge container is the container, and the symptom was the subject OOM-killed in thirty
# seconds at 64 MiB and again at 96 MiB. That is worth knowing (B-31), but it is not this soak.
if [ "$DELIVERY" = noop ]; then
  # The one arm where an empty subscriber is the point rather than a broken stand: the binary is
  # built with `-Pxyk.outbound=noop`, so the whole delivery path runs and the request does not.
  # The banner check above still applies — the engine must be linked — and this one cannot.
  echo "subscriber check skipped: this build is the no-op outbound arm, nothing is meant to arrive"
elif [ -n "$SUBSCRIBER_OVERRIDE" ]; then
  # The harness's sink is not where this arm's deliveries go, so it cannot say whether they arrive.
  # What the arm asserts instead is that attempts are being *made* — see the attempt count below.
  echo "subscriber check skipped: deliveries go to $SUBSCRIBER, not to this harness's sink"
elif [ "$DELIVERY" = on ]; then
  delivered_at_30s=$(curl -s -m 10 "http://127.0.0.1:$SINK_PORT/")
  if [ "${delivered_at_30s:-0}" -eq 0 ]; then
    echo "soak: the subscriber has received nothing after 30 s — the subject cannot reach it." >&2
    echo "      subscriber = $SUBSCRIBER; the sink answers on the host at :$SINK_PORT" >&2
    exit 1
  fi
  echo "subscriber confirmed: $delivered_at_30s deliveries arrived in the first 30 s"
fi

# THE DELIVERY COLUMNS COME OUT OF THE FILE, ONCE A MINUTE RATHER THAN EVERY SAMPLE. Reading the
# database from outside makes this harness one more reader, and a reader is half the mechanism the
# soak was built around — so it is paid rarely and it is written down here rather than discovered in
# the numbers. `sink` is free: it is a counter in the subscriber, off the measured path.
SQLITE=${SQLITE:-sqlite3}
command -v "$SQLITE" >/dev/null || { echo "soak: no $SQLITE on PATH; the delivery columns would be empty" >&2; exit 2; }

delivery_counts() {
  [ "$DELIVERY" = on ] || [ "$DELIVERY" = noop ] || { echo ",,"; return; }
  local pending attempts sink
  pending=$("$SQLITE" "file:$DATA/xyk.db?mode=ro" "select count(*) from deliveries where state='pending';" 2>/dev/null)
  attempts=$("$SQLITE" "file:$DATA/xyk.db?mode=ro" "select count(*) from delivery_attempts;" 2>/dev/null)
  sink=$(curl -s -m 5 "http://127.0.0.1:$SINK_PORT/" 2>/dev/null)
  printf '%s,%s,%s' "${pending:-}" "${attempts:-}" "${sink:-}"
}

echo "second,wal_kb,db_kb,mem_current_kb,threads,alive,ready,pending,attempts,sink" > "$OUT/samples.csv"
cgroup=/sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' "$NAME").scope
started=$(date +%s)
while [ $(( $(date +%s) - started )) -lt $(( MINUTES * 60 )) ]; do
  alive=$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)
  pid=$(docker inspect -f '{{.State.Pid}}' "$NAME" 2>/dev/null)
  elapsed=$(( $(date +%s) - started ))
  if [ $(( elapsed % 60 )) -lt 10 ]; then delivery=$(delivery_counts); else delivery=",,"; fi
  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$elapsed" \
    "$(du -k "$DATA/xyk.db-wal" 2>/dev/null | cut -f1)" \
    "$(du -k "$DATA/xyk.db" 2>/dev/null | cut -f1)" \
    "$(awk '{printf "%d", $1/1024}' "$cgroup/memory.current" 2>/dev/null)" \
    "$(grep Threads "/proc/$pid/status" 2>/dev/null | awk '{print $2}')" \
    "${alive:-false}" \
    "$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8064/health/ready)" \
    "$delivery" \
    >> "$OUT/samples.csv"

  # IS THE RATE ONE THIS SERVICE CAN ACTUALLY SUSTAIN? Asked once, five minutes in. Delivery is
  # sequential per worker, so above some offered rate the backlog grows without bound and the run
  # stops being a soak and becomes a very slow way of rediscovering B-14's arithmetic. More than a
  # minute of ingest sitting in `pending` is that, and it is worth four minutes to find out rather
  # than four hours.
  if [ "$DELIVERY" = on ] && [ "$elapsed" -ge 300 ] && [ "${backlog_checked:-0}" -eq 0 ]; then
    backlog_checked=1
    pending_now=$(echo "$(delivery_counts)" | cut -d, -f1)
    if [ -n "$SUBSCRIBER_OVERRIDE" ]; then
      # A backlog is the expected state when the subscriber is down; the arm is about what that
      # costs, not about whether it happens.
      echo "backlog check skipped: this arm's subscriber is somebody else's address"
    elif [ "${pending_now:-0}" -gt $(( RATE * 60 )) ]; then
      echo "soak: ${pending_now} deliveries pending at five minutes — more than a minute of ingest." \
        | tee -a "$OUT/verdict.txt" >&2
      echo "      At $RATE rps the outbound half is not keeping up, so this run would measure a" >&2
      echo "      growing backlog rather than a steady state. Lower --rate and start again." >&2
      exit 1
    fi
    echo "backlog confirmed bounded at five minutes: ${pending_now} pending"
  fi
  # READ IT WHILE IT EXISTS. `memory.events` lives in the container's cgroup and the cgroup goes when
  # the container does — so reading `oom_kill` after the loop, which is where it used to be read,
  # reports `unknown` at exactly the moment the answer matters. Kept from the last sample instead.
  this_oom=$(awk '/^oom_kill /{print $2}' "$cgroup/memory.events" 2>/dev/null)
  [ -n "$this_oom" ] && killed=$this_oom

  [ "$alive" = "true" ] || {
    echo "soak: THE SUBJECT DIED at ${elapsed}s (oom_kill=${killed:-unknown})" | tee -a "$OUT/verdict.txt"
    docker inspect -f 'OOMKilled={{.State.OOMKilled}} exit={{.State.ExitCode}}' "$NAME" 2>/dev/null | tee -a "$OUT/verdict.txt"
    break
  }
  sleep 10
done
{
  echo "arm=$ARM limit=$LIMIT minutes=$MINUTES rate=$RATE readers=$READERS checkpoint_seconds=$CHECKPOINT_SECONDS"
  echo "image=$IMAGE delivery=$DELIVERY heap_bytes=${HEAP_BYTES:-0} arenas=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$IMAGE" 2>/dev/null | grep MALLOC_ARENA_MAX || echo unset)"
  # DOCKER'S FLAG FIRST, the cgroup counter second. `memory.events` is read on a ten-second sample and
  # the cgroup is gone the moment the container is, so at the one moment the number decides anything
  # it is up to ten seconds stale — it reported `oom_kill=0` beside `OOMKilled=true` on the run this
  # comment comes from, which reads as "it died of something else".
  echo "OOMKilled=$(docker inspect -f '{{.State.OOMKilled}}' "$NAME" 2>/dev/null || echo unknown) (docker)"
  echo "oom_kill=${killed:-unknown} (cgroup, last sample before the end)"
  echo "survived=$( [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = true ] && echo yes || echo NO )"
  echo "wal kB: $(tail -n +2 "$OUT/samples.csv" | cut -d, -f2 | sort -n | tail -1) peak"
  echo "threads: $(tail -n +2 "$OUT/samples.csv" | cut -d, -f5 | sort -n | tail -1) peak"
  if [ "$DELIVERY" = on ]; then
    delivered=$(curl -s -m 10 "http://127.0.0.1:$SINK_PORT/")
    echo "delivered to the subscriber: ${delivered:-unknown}"
    echo "pending at the end: $(echo "$(delivery_counts)" | cut -d, -f1)"
    echo "attempt rows: $(echo "$(delivery_counts)" | cut -d, -f2)"
    # THE SAME GUARD B-29 ADDED TO THE MEMORY BENCH, for the same reason: a subject that delivered
    # nothing produces a beautifully quiet run, and quiet is what this is looking for.
    if [ "${delivered:-0}" -eq 0 ]; then
      echo "SOAK VOID: the subscriber received nothing, so the outbound half was never exercised"
    fi
  fi
} | tee -a "$OUT/verdict.txt"
echo "samples: $OUT/samples.csv"
