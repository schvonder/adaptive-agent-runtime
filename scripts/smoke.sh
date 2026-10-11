#!/usr/bin/env bash
# T29 — end-to-end smoke over the docker-compose demo stack.
#
#   1. fail fast when .env is missing or a required variable is empty
#   2. docker compose up -d --wait   (healthcheck-gated startup)
#   3. POST /tasks with X-API-Key + a SOLO-band envelope
#   4. follow SSE /tasks/{id}/events to the terminal event (TASK_COMPLETED)
#   5. GET /tasks/{id} asserts SUCCEEDED + non-empty result
#   6. print PASS, tear the stack down (trap), exit 0
#
# Hermetic by default: the app runs with SPRING_PROFILES_ACTIVE=fixture-model
# (deterministic FixedTextChatModel, fixture-backed search tools) — no LLM,
# no outbound network. See .env.example for real-model wiring.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASE_URL="${SMOKE_BASE_URL:-http://localhost:8080}"
SSE_TIMEOUT_SECONDS="${SMOKE_SSE_TIMEOUT_SECONDS:-120}"
REQUIRED_VARS=(RUNTIME_API_KEY POSTGRES_PASSWORD)

log() { printf '[smoke] %s\n' "$*"; }
die() { printf '[smoke] FAIL: %s\n' "$*" >&2; exit 1; }

# --- 1. fail fast on missing .env / empty required keys ----------------------
if [[ ! -f .env ]]; then
  die ".env not found in $ROOT — copy .env.example to .env and fill in the required values (cp .env.example .env)"
fi
set -a
# shellcheck disable=SC1091
source .env
set +a
for var in "${REQUIRED_VARS[@]}"; do
  value="${!var:-}"
  if [[ -z "${value// /}" ]]; then
    die "required environment variable $var is missing or empty in .env"
  fi
done

STACK_UP=0
TMP_DIR="$(mktemp -d)"
cleanup() {
  if (( STACK_UP )); then
    log "tearing the stack down (docker compose down)"
    docker compose down || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

# --- 2. bring the stack up, waiting on healthchecks ---------------------------
log "starting the compose stack (docker compose up -d --wait)"
STACK_UP=1
docker compose up -d --wait

TASK_ID="$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)"
log "task_id: $TASK_ID"

# --- 3. REST submit: SOLO-band research envelope ------------------------------
log "submitting task via POST $BASE_URL/tasks"
SUBMIT_BODY='{"task_id":"'"$TASK_ID"'","task_type":"research","prompt":"Summarize how a single adaptive agent architecture works","breadth_hint":1,"forced_topology":"SOLO"}'
HTTP_CODE="$(curl -sS -o "$TMP_DIR/submit.json" -w '%{http_code}' \
  -X POST "$BASE_URL/tasks" \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $RUNTIME_API_KEY" \
  -d "$SUBMIT_BODY")" || die "POST /tasks request failed"
if [[ "$HTTP_CODE" != "202" && "$HTTP_CODE" != "200" ]]; then
  die "POST /tasks returned HTTP $HTTP_CODE: $(cat "$TMP_DIR/submit.json")"
fi
log "submit accepted (HTTP $HTTP_CODE): $(cat "$TMP_DIR/submit.json")"

# --- 4. SSE to completion ------------------------------------------------------
log "following SSE $BASE_URL/tasks/$TASK_ID/events (timeout ${SSE_TIMEOUT_SECONDS}s)"
SSE_LOG="$TMP_DIR/sse.log"
if ! curl -sS -N --max-time "$SSE_TIMEOUT_SECONDS" \
    -H "X-API-Key: $RUNTIME_API_KEY" \
    -H 'Accept: text/event-stream' \
    "$BASE_URL/tasks/$TASK_ID/events" | tee "$SSE_LOG"; then
  die "SSE stream failed or timed out after ${SSE_TIMEOUT_SECONDS}s (frames so far: $(wc -l < "$SSE_LOG"))"
fi
TERMINAL="$(grep -oE 'event:[[:space:]]*TASK_[A-Z_]+' "$SSE_LOG" | sed -E 's/event:[[:space:]]*//' | tail -1 || true)"
if [[ -z "$TERMINAL" ]]; then
  die "no terminal SSE event received (stream ended before TASK_COMPLETED)"
fi
log "terminal SSE event: $TERMINAL"
if [[ "$TERMINAL" != "TASK_COMPLETED" ]]; then
  die "task did not complete successfully: terminal event $TERMINAL"
fi

# --- 5. final state: SUCCEEDED + non-empty result ------------------------------
log "fetching final task state"
STATE="$(curl -sS -H "X-API-Key: $RUNTIME_API_KEY" "$BASE_URL/tasks/$TASK_ID")" || die "GET /tasks/$TASK_ID request failed"
log "state: $STATE"
grep -q '"status":"SUCCEEDED"' <<<"$STATE" || die "task status is not SUCCEEDED: $STATE"
grep -qE '"result":"[^"]' <<<"$STATE" || die "task result is null/empty: $STATE"

# --- 6. PASS --------------------------------------------------------------------
log "PASS — compose stack smoke succeeded (task $TASK_ID)"
exit 0
