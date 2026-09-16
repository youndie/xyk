#!/usr/bin/env bash
# Asserts that the process stops in the order it is supposed to, against a real image.
#
# WHY THIS EXISTS AS A CHECK AND NOT AS A PARAGRAPH. `EmbeddedServer.stop` runs its steps in the
# opposite order on Kotlin/Native and on the JVM, from identical source, and nothing reports it. The
# failure that ordering produces here is a webhook answered `200` and never delivered — invisible in
# every test, visible only in a transcript nobody reads twice. So it is read by a machine, on every
# push.
#
#   dev/shutdown-check.sh [image] [container-name]
#
# Exit codes: 0 the order held, 1 it did not, 2 the harness could not run the subject at all.
set -uo pipefail

IMAGE=${1:-xyk:dev}
NAME=${2:-xyk-shutdown-check}
PORT=${PORT:-8097}
# How long `docker stop` waits before SIGKILL. 30 s is longer than the plan's own sum, so a run
# killed at this timeout is a real failure rather than an impatient harness. It is a variable for
# one reason: `STOP_TIMEOUT=0` is this check's POSITIVE CONTROL — the process is killed outright,
# the transcript cannot complete, and the check must go red. A guard that has never failed on
# purpose has not been shown able to notice a failure.
STOP_TIMEOUT=${STOP_TIMEOUT:-30}

# The stages, in the order kore must run them. RELEASE_POOLS after DRAIN is the whole point: the
# pool must close after the engine has finished answering, not before.
STAGES=(SIGNAL ANNOUNCE DRAIN RELEASE_CONSUMERS RELEASE_POOLS EXIT)

# On a volume, because the stop order is only half the assertion: the other half is that the last
# checkpoint DID something, and that is a file on disk. It has to be visible from outside the
# container — a participant that throws is recorded by kore as a failure and the stage still reports
# COMPLETED, so a checkpoint that quietly stopped working would pass every check above.
DATA=$(mktemp -d)
chmod 777 "$DATA"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$DATA"; }
trap cleanup EXIT

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -v "$DATA:/data" -p "$PORT:8080" "$IMAGE" >/dev/null || {
  echo "shutdown-check: could not start $IMAGE" >&2; exit 2; }

ready=false
for _ in $(seq 1 60); do
  sleep 0.5
  if curl -sf -o /dev/null "http://127.0.0.1:$PORT/health/ready"; then ready=true; break; fi
done
$ready || { echo "shutdown-check: $IMAGE never became ready" >&2; docker logs "$NAME" >&2; exit 2; }

# `docker stop` sends SIGTERM and waits (see STOP_TIMEOUT above).
docker stop -t "$STOP_TIMEOUT" "$NAME" >/dev/null || { echo "shutdown-check: stop failed" >&2; exit 2; }

log=$(docker logs "$NAME" 2>&1)

failed=0
previous=-1
for stage in "${STAGES[@]}"; do
  line=$(printf '%s\n' "$log" | grep -n "^$stage COMPLETED" | head -1)
  if [ -z "$line" ]; then
    echo "shutdown-check: $stage did not complete" >&2
    failed=1
    continue
  fi
  position=${line%%:*}
  if [ "$position" -le "$previous" ]; then
    echo "shutdown-check: $stage ran out of order (line $position, after line $previous)" >&2
    failed=1
  fi
  previous=$position
done

# THE JOURNAL, AFTER THE STOP. `wal_checkpoint(TRUNCATE)` has to wait for every other connection to
# the database, and the pool's own second connection is one of them — so a checkpoint taken while
# the pool is still open waits, and either misses its deadline or gives up and leaves the log where
# it was. Either way the next process starts by replaying a journal this one was supposed to have
# folded away, and nothing in the transcript says so. The file does.
wal=$(ls "$DATA"/*-wal 2>/dev/null | head -1)
if [ -n "$wal" ] && [ -s "$wal" ]; then
  echo "shutdown-check: the journal survived the stop — $(wc -c <"$wal") bytes in $(basename "$wal")" >&2
  failed=1
fi
if printf '%s\n' "$log" | grep -q "last checkpoint left"; then
  echo "shutdown-check: the last checkpoint could not reset the log" >&2
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  echo "--- transcript ---" >&2
  printf '%s\n' "$log" >&2
  exit 1
fi

echo "shutdown-check: ${STAGES[*]} — in order, all COMPLETED; the journal is folded away"
