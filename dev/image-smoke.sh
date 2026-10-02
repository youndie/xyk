#!/usr/bin/env bash
# The check that an image can actually serve a PAGE, not merely a status code — and that nothing it
# serves or logs carries a secret.
#
# WHY IT EXISTS IN THIS SHAPE. A statically linked Kotlin/Native binary is not self-contained: Ktor's
# charset layer is glibc `iconv`, which loads its converters with `dlopen`, and an image without the
# gconv modules starts and answers `/health/ready` with `200` all the same. Elsewhere in this
# portfolio such an image returned `500` on the first rendered page — the reason this script insists
# on reading a timestamp out of the HTML rather than stopping at a status code. Here no page reaches a
# converter (research §1.14); a request body declared in a charset other than UTF-8 does, and on an
# image without them it is not refused but stored as replacement characters with a `201`. So the
# script also sends one and requires the text back intact.
#
# THE SECOND HALF: NO SECRET IS EVER RENDERED. Secrets are write-only (B-07): `POST` and `PATCH` take
# one, no route returns one, and the journal names a secret by its fingerprint. What holds that is
# structure — `EndpointRecord` has no secret field — and structure is what a later change alters
# without a unit test noticing, because a test asserting that a field is absent passes for as long as
# the field is absent from the one place the test looks. So the search is made from the outside, on
# the image: an endpoint of every scheme an operator can create, each with a secret this script
# invents, one of them rotated, a signed event through every secret; then every page and every JSON
# route — status line, headers and body — and the container's own log, taken after a stop so the
# shutdown is in it, are searched for every one of those secrets. One occurrence fails the run.
#
#   dev/image-smoke.sh [image]
#
# Exit codes: 0 the page rendered and no secret was found, 1 it did not render, 2 the harness could
# not run the subject, 3 a secret appeared in a response or in the log, 4 a body in another charset
# was not decoded — the image is missing its charset converters.
set -uo pipefail

IMAGE=${1:-xyk:dev}
NAME=${NAME:-xyk-image-smoke}
PORT=${PORT:-8087}

# Invented per run, and long enough that a match is never a coincidence: the search is a substring
# match, and a secret like `test` would be "found" in half the pages.
rand() { openssl rand -hex 12; }
SECRET=${SECRET:-github-$(rand)}

# TWO POSITIVE CONTROLS, one per half, because a check that has never failed on purpose has not been
# shown able to notice a failure.
#
#   SIGN_SECRET=wrong dev/image-smoke.sh          # must exit 1
#   SECRET_IN_DESCRIPTION=1 dev/image-smoke.sh    # must exit 3 — `make build` runs this one
#
# SIGN_SECRET exists because the obvious control is not one: changing SECRET alone changes both the
# endpoint and the signature, so the script still passes. SIGN_SECRET breaks only the signing side.
#
# SECRET_IN_DESCRIPTION writes the GitHub secret into that endpoint's description, a field the pages
# and `GET /api/endpoints` render by design. The server does nothing wrong there, and that is the
# point: a secret that IS in a response has to be found, or the search is looking at nothing.
#
# AND ONE NEGATIVE CONTROL, for the failure the script was written for: an image whose charset
# converters are missing. It is `make image-scratch`, which builds the `scratch` image without the
# gconv tree and requires this script to exit exactly 4 on it. B-18 built that image first and it
# rendered every page, so the negative never fired and the target that demanded it failed on every
# run; since 2026-10-02 the charset step below is what it fires on (research §1.14, the correction).
SIGN_SECRET=${SIGN_SECRET:-$SECRET}
DESCRIPTION="image smoke"
[ "${SECRET_IN_DESCRIPTION:-0}" = 1 ] && DESCRIPTION="image smoke $SECRET"

# Every response this script receives — status line, headers and body — is appended here, and the
# container's log after it. It is what the secret search reads.
SEEN=$(mktemp)

# `-v`: the image declares a VOLUME, and every run would otherwise leave an anonymous one behind.
cleanup() { docker rm -f -v "$NAME" >/dev/null 2>&1 || true; }
trap 'cleanup; rm -f "$SEEN"' EXIT
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

# call METHOD PATH [curl options...] — sets STATUS and BODY, and appends the whole response to $SEEN.
# Not a `$(...)`: that would run in a subshell and the two variables would never reach the caller.
call() {
  local method=$1 path=$2 response
  shift 2
  response=$(curl -s -i -X "$method" "$@" "$B$path") || {
    echo "image-smoke: $method $path got no response" >&2; return 2; }
  printf '\n### %s %s\n%s\n' "$method" "$path" "$response" >>"$SEEN"
  STATUS=$(printf '%s\n' "$response" | head -1 | cut -d' ' -f2)
  BODY=${response#*$'\r\n\r\n'}
}
# field NAME — a string field of the last JSON body. Enough for the flat objects this API returns.
field() { printf '%s' "$BODY" | sed -nE "s/.*\"$1\":\"([^\"]+)\".*/\1/p"; }
hmac_hex() { printf %s "$2" | openssl dgst -sha256 -hmac "$1" -hex | sed 's/.*= //'; }

# The empty journal first: it is a rendered page too, and on a fresh install it is the only one an
# operator sees.
call GET /journal || exit 2
case "$STATUS:$BODY" in
  200:*"Nothing has arrived yet"*) ;;
  *) echo "image-smoke: the empty journal did not render ($STATUS)" >&2; printf '%s\n' "$BODY" | head -5 >&2; exit 1 ;;
esac

call POST /api/endpoints -H 'Content-Type: application/json' \
  -d "{\"scheme\":\"github\",\"secret\":\"$SECRET\",\"description\":\"$DESCRIPTION\"}" || exit 2
id=$(field id)
[ "$STATUS" = 201 ] && [ -n "$id" ] || { echo "image-smoke: could not create an endpoint ($STATUS)" >&2; exit 1; }

body='{"zen":"Design for failure."}'
call POST "/hooks/$id" -H "X-Hub-Signature-256: sha256=$(hmac_hex "$SIGN_SECRET" "$body")" \
  --data-binary "$body" || exit 2
[ "$STATUS" = 200 ] || { echo "image-smoke: the webhook was not accepted ($STATUS)" >&2; exit 1; }
EVENTS=("$(field event)")

# A rendered page carrying a rendered date — what a status code does not show. On another service of
# this shape a missing converter failed right here; on this one no page reaches a converter, and the
# check that does is the next one.
call GET /journal || exit 2
page=$BODY
if [ "$STATUS" != 200 ] || ! printf '%s' "$page" | grep -qE '[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}'; then
  echo "image-smoke: the journal rendered no timestamp — the page did not render ($STATUS)" >&2
  printf '%s\n' "$page" | head -20 >&2
  docker logs "$NAME" 2>&1 | tail -20 >&2
  exit 1
fi

# And the detail page, which renders more of the same machinery.
call GET "/journal/$(printf '%s' "$page" | sed -E 's/.*journal\/([0-9a-f]{32}).*/\1/' | head -1)" || exit 2
case "$BODY" in
  *"verified by"*) ;;
  *) echo "image-smoke: the event page did not render" >&2; exit 1 ;;
esac

# --- A body in another charset is decoded. -----------------------------------------------------------
#
# The one place on this service that reaches glibc's converters. JSON is UTF-8 by its RFC, but a
# client may declare another charset in `Content-Type`, Ktor honours the declaration, and for anything
# but UTF-8 it decodes through `iconv`. Without the gconv tree the request is NOT refused: measured on
# 2026-10-02, `windows-1251`, `KOI8-R` and `ISO-8859-1` bodies were answered `201` with every
# non-ASCII character stored as U+FFFD — corruption a status check sails past. So the description
# goes in as windows-1251 bytes and has to come back as the same word in UTF-8. windows-1251 rather
# than Latin-1 because nothing but glibc converts it here: a runtime that one day decodes Latin-1 by
# itself would leave this check passing and proving nothing.
#
# The description keeps its prefix, so `SECRET_IN_DESCRIPTION=1` still finds its secret on the pages.
CP1251_WORD=$'\xef\xf0\xe8\xe2\xe5\xf2'
call PATCH "/api/endpoints/$id" -H 'Content-Type: application/json; charset=windows-1251' \
  --data-binary "{\"description\":\"$DESCRIPTION $CP1251_WORD\"}" || exit 2
case "$STATUS:$BODY" in
  200:*"\"description\":\"$DESCRIPTION привет\""*) ;;
  *)
    echo "image-smoke: a windows-1251 body was not decoded ($STATUS) — the image is missing its charset converters" >&2
    printf '%s\n' "$BODY" | head -5 >&2
    exit 4
    ;;
esac

# --- No secret is ever rendered. ---------------------------------------------------------------------

ROTATED=github-rotated-$(rand)
STRIPE=whsec_$(rand)
TELEGRAM=telegram_$(rand)
HMAC=hmac-$(rand)
NAMES=("GitHub" "rotated GitHub" "Stripe" "Telegram" "hmac-sha256")
VALUES=("$SECRET" "$ROTATED" "$STRIPE" "$TELEGRAM" "$HMAC")

# create SCHEME SECRET [schemeConfig] — prints nothing, sets `created`.
create() {
  local config=${3:+,\"schemeConfig\":$3}
  call POST /api/endpoints -H 'Content-Type: application/json' \
    -d "{\"scheme\":\"$1\",\"secret\":\"$2\",\"description\":\"image smoke, $1\"$config}" || exit 2
  created=$(field id)
  [ "$STATUS" = 201 ] && [ -n "$created" ] || { echo "image-smoke: could not create a $1 endpoint ($STATUS)" >&2; exit 1; }
}
# accepted SCHEME — the last hook was taken; its event joins the ones whose pages are fetched.
accepted() {
  [ "$STATUS" = 200 ] || { echo "image-smoke: the $1 webhook was not accepted ($STATUS)" >&2; exit 1; }
  EVENTS+=("$(field event)")
}

# Rotation ADDS a secret (B-07), so the endpoint now holds two, and an event signed with the new one
# puts the second fingerprint on a page.
call PATCH "/api/endpoints/$id" -H 'Content-Type: application/json' -d "{\"secret\":\"$ROTATED\"}" || exit 2
[ "$STATUS" = 200 ] || { echo "image-smoke: the secret was not rotated ($STATUS)" >&2; exit 1; }
call POST "/hooks/$id" -H "X-Hub-Signature-256: sha256=$(hmac_hex "$ROTATED" "$body")" --data-binary "$body" || exit 2
accepted "rotated GitHub"

create stripe "$STRIPE"; stripe_id=$created
t=$(date +%s)
call POST "/hooks/$stripe_id" -H "Stripe-Signature: t=$t,v1=$(hmac_hex "$STRIPE" "$t.$body")" --data-binary "$body" || exit 2
accepted Stripe

create telegram "$TELEGRAM"; telegram_id=$created
call POST "/hooks/$telegram_id" -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM" --data-binary "$body" || exit 2
accepted Telegram

create hmac-sha256 "$HMAC" '{"header":"X-Smoke-Signature","prefix":"sha256=","encoding":"hex"}'; hmac_id=$created
call POST "/hooks/$hmac_id" -H "X-Smoke-Signature: sha256=$(hmac_hex "$HMAC" "$body")" --data-binary "$body" || exit 2
accepted hmac-sha256

ENDPOINTS=("$id" "$stripe_id" "$telegram_id" "$hmac_id")

# Every route of docs/api/, with every id this run made. `/` is fetched without following the
# redirect, so its own headers are searched too.
for path in / /journal /journal/00000000000000000000000000000000 /api/endpoints /api/events \
  /health/startup /health/ready /health/live /version; do
  call GET "$path" || exit 2
done
for endpoint in "${ENDPOINTS[@]}"; do
  for path in "/journal?endpoint=$endpoint" "/api/endpoints/$endpoint" \
    "/api/endpoints/$endpoint/subscribers" "/api/events?endpoint=$endpoint"; do
    call GET "$path" || exit 2
  done
done
for event in "${EVENTS[@]}"; do
  for path in "/journal/$event" "/api/events/$event" "/api/events/$event/payload"; do
    call GET "$path" || exit 2
  done
done
# Before any subscriber exists, so it answers `409` and leaves no timer behind: the image `make build`
# makes has no outbound engine, and a pending timer there turns readiness off.
call POST "/api/events/${EVENTS[0]}/redeliver" || exit 2

# The writes that are left, each response searched like the rest: a subscriber added, listed and
# removed; an unsigned request to every endpoint, which every scheme refuses and counts; one endpoint
# disabled; the list once more, now with the refusals on it.
call POST "/api/endpoints/$stripe_id/subscribers" -H 'Content-Type: application/json' \
  -d '{"url":"http://127.0.0.1:9/image-smoke"}' || exit 2
subscriber=$(field id)
call GET "/api/endpoints/$stripe_id/subscribers" || exit 2
call DELETE "/api/subscribers/$subscriber" || exit 2
for endpoint in "${ENDPOINTS[@]}"; do
  call POST "/hooks/$endpoint" --data-binary "$body" || exit 2
done
call DELETE "/api/endpoints/$hmac_id" || exit 2
call GET /api/endpoints || exit 2
responses=$(awk '/^### /{n++} END{print n+0}' "$SEEN")

# The log, after a stop, so that whatever the shutdown prints is searched as well.
docker stop -t 10 "$NAME" >/dev/null 2>&1 || true
log=$(docker logs "$NAME" 2>&1)
[ -n "$log" ] || { echo "image-smoke: the container logged nothing — there is no log to search" >&2; exit 2; }
printf '\n### docker logs\n%s\n' "$log" >>"$SEEN"

# A fixed-string search through the environment rather than a pattern: a secret is data, and neither
# grep's dialect nor awk's `-v` escaping gets a say in what it matches.
leaked=0
for i in "${!VALUES[@]}"; do
  hits=$(NEEDLE=${VALUES[$i]} awk '/^### /{at=$0; next} index($0, ENVIRON["NEEDLE"]){print "  " at ": " substr($0, 1, 160)}' "$SEEN")
  if [ -n "$hits" ]; then
    echo "image-smoke: the ${NAMES[$i]} secret appeared:" >&2
    printf '%s\n' "$hits" | head -10 >&2
    leaked=1
  fi
done
[ "$leaked" = 0 ] || exit 3

echo "image-smoke: the journal rendered a timestamp and an event page from $IMAGE," \
  "a windows-1251 body came back decoded," \
  "and none of ${#VALUES[@]} secrets is in $responses responses or the log"
