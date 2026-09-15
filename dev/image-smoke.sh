#!/usr/bin/env bash
# The check that an image can actually serve a PAGE, not merely a status code.
#
# WHY IT EXISTS IN THIS SHAPE. A statically linked Kotlin/Native binary is not self-contained: Ktor's
# charset layer is glibc `iconv`, which loads its converters with `dlopen`, and an image without the
# gconv modules starts, answers `/health/ready` with `200`, and returns `500` on the first rendered
# page. A smoke test that stops at a status code passes on an image that cannot render anything —
# which is exactly what happened elsewhere in this portfolio, and the reason this script insists on
# reading a timestamp out of the HTML.
#
#   dev/image-smoke.sh [image]
#
# Exit codes: 0 the page rendered, 1 it did not, 2 the harness could not run the subject.
set -uo pipefail

IMAGE=${1:-xyk:dev}
NAME=${NAME:-xyk-image-smoke}
PORT=${PORT:-8087}
SECRET=${SECRET:-smoke-secret}
# THE POSITIVE CONTROL, and it exists because the obvious one is not one: changing SECRET alone
# changes both the endpoint and the signature, so the script still passes. SIGN_SECRET breaks only
# the signing side, which is the only way to make this script fail on purpose.
#
#   SIGN_SECRET=wrong dev/image-smoke.sh    # must exit 1
#
# The branch this does NOT exercise is the one the script was written for: an image that starts,
# answers `200`, and renders nothing because its charset converters are missing. The natural negative
# for that is a `scratch` image without the gconv tree, which arrives with B-18.
SIGN_SECRET=${SIGN_SECRET:-$SECRET}

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

docker run -d --name "$NAME" -p "$PORT:8080" \
  -e XYK_PUBLIC_BASE_URL="http://127.0.0.1:$PORT" "$IMAGE" >/dev/null || {
  echo "image-smoke: could not start $IMAGE" >&2; exit 2; }

for _ in $(seq 1 60); do
  sleep 0.5
  curl -sf -o /dev/null "http://127.0.0.1:$PORT/health/ready" && ready=yes && break
done
[ "${ready:-no}" = yes ] || { echo "image-smoke: never became ready" >&2; docker logs "$NAME" >&2; exit 2; }

B="http://127.0.0.1:$PORT"

# The empty journal first: it is a rendered page too, and on a fresh install it is the only one an
# operator sees.
empty=$(curl -sf "$B/journal") || { echo "image-smoke: /journal did not answer" >&2; exit 1; }
case "$empty" in
  *"Nothing has arrived yet"*) ;;
  *) echo "image-smoke: the empty journal did not render" >&2; printf '%s\n' "$empty" | head -5 >&2; exit 1 ;;
esac

id=$(curl -sf -X POST "$B/api/endpoints" -H 'Content-Type: application/json' \
      -d "{\"scheme\":\"github\",\"secret\":\"$SECRET\",\"description\":\"image smoke\"}" \
     | sed -E 's/.*"id":"([^"]+)".*/\1/')
[ -n "$id" ] || { echo "image-smoke: could not create an endpoint" >&2; exit 1; }

body='{"zen":"Design for failure."}'
sig=$(printf %s "$body" | openssl dgst -sha256 -hmac "$SIGN_SECRET" -hex | sed 's/.*= //')
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/hooks/$id" \
        -H "X-Hub-Signature-256: sha256=$sig" --data-binary "$body")
[ "$code" = 200 ] || { echo "image-smoke: the webhook was not accepted ($code)" >&2; exit 1; }

# THE ACTUAL CHECK: a rendered page carrying a rendered date. Anything that goes through a charset
# conversion fails here and nowhere earlier.
page=$(curl -sf "$B/journal") || { echo "image-smoke: /journal did not answer after ingest" >&2; exit 1; }
if ! printf '%s' "$page" | grep -qE '[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}'; then
  echo "image-smoke: the journal rendered no timestamp — the page did not render" >&2
  printf '%s\n' "$page" | head -20 >&2
  docker logs "$NAME" 2>&1 | tail -20 >&2
  exit 1
fi

# And the detail page, which renders more of the same machinery.
detail=$(curl -sf "$B/journal/$(printf '%s' "$page" | sed -E 's/.*journal\/([0-9a-f]{32}).*/\1/' | head -1)")
case "$detail" in
  *"verified by"*) ;;
  *) echo "image-smoke: the event page did not render" >&2; exit 1 ;;
esac

echo "image-smoke: the journal rendered a timestamp and an event page from $IMAGE"
