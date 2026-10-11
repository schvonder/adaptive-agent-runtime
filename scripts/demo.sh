#!/usr/bin/env bash
# T31 — guided demo over the docker-compose stack.
#
#   1. fail fast when .env is missing or a required variable is empty
#      (values are sourced SILENTLY and only ever echoed masked)
#   2. docker compose up -d --wait                (trap tears the stack down)
#   3. three research tasks of rising complexity via REST+SSE:
#        SOLO  (demo task 1 band)  -> single-agent NODE_START + TASK_COMPLETED
#        FAN_OUT (demo task 9 band) -> 3 parallel branch NODE_STARTs
#        SUPERVISOR (demo task 11 band) -> researcher/writer/critic loop
#      each: POST /tasks -> curl -N SSE to completion -> GET shows SUCCEEDED + topology
#   4. Kafka roundtrip: console-producer envelope on adaptive-tasks-request,
#      console-consumer shows the ACK on adaptive-tasks-reply (timeout-bounded)
#   5. MCP streamable-http with plain curl: initialize (session id captured),
#      notifications/initialized, tools/call submit_task, tools/call get_task
#   6. print DEMO PASS, tear the stack down (trap), exit 0
#
# Hermetic by default: the app runs SPRING_PROFILES_ACTIVE=fixture-model
# (deterministic FixedTextChatModel, fixture-backed search tools) — no LLM,
# no outbound network. Fresh task_ids on every run (the global fixture
# fallbacks resolve search queries offline; see docs/TASKS.md).
#
# No secret ever reaches stdout: .env is sourced with values unechoed and the
# config banner prints masked placeholders only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASE_URL="${DEMO_BASE_URL:-http://localhost:8080}"
SSE_TIMEOUT_SECONDS="${DEMO_SSE_TIMEOUT_SECONDS:-120}"
KAFKA_TIMEOUT_SECONDS="${DEMO_KAFKA_TIMEOUT_SECONDS:-45}"
REQUIRED_VARS=(RUNTIME_API_KEY POSTGRES_PASSWORD)

banner() { printf '\n\033[1;36m================ %s ================\033[0m\n' "$*"; }
log()    { printf '[demo] %s\n' "$*"; }
die()    { printf '[demo] FAIL: %s\n' "$*" >&2; exit 1; }
mask()   { printf '******** (value hidden)'; }

# --- 1. fail fast on missing .env / empty required keys -----------------------
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
log ".env loaded (values sourced silently): RUNTIME_API_KEY=$(mask), POSTGRES_PASSWORD=$(mask)"

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

new_task_id() { uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid; }

# --- 2. bring the stack up, waiting on healthchecks ----------------------------
banner "adaptive-agent-runtime — compose stack"
log "starting the compose stack (docker compose up -d --wait)"
STACK_UP=1
docker compose up -d --wait
log "stack healthy: app $BASE_URL · kafka · postgres · jaeger (hermetic fixture-model profile)"

# --- 3. three REST+SSE demos of rising complexity ------------------------------
# run_task <title> <topology> <breadth> <prompt>  — fresh task_id + matching override
run_task() {
  local title="$1" topology="$2" breadth="$3" prompt="$4"
  local task_id task_body http_code sse_log state
  task_id="$(new_task_id)"
  task_body="$(printf '{"task_id":"%s","task_type":"research","prompt":"%s","breadth_hint":%s,"forced_topology":"%s"}' \
    "$task_id" "$prompt" "$breadth" "$topology")"

  banner "$title — forced_topology=$topology"
  log "task_id: $task_id"
  log "POST $BASE_URL/tasks   (X-API-Key: $(mask))"
  http_code="$(curl -sS -o "$TMP_DIR/submit-$task_id.json" -w '%{http_code}' \
    -X POST "$BASE_URL/tasks" \
    -H 'Content-Type: application/json' \
    -H "X-API-Key: $RUNTIME_API_KEY" \
    -d "$task_body")" || die "POST /tasks request failed"
  if [[ "$http_code" != "202" && "$http_code" != "200" ]]; then
    die "POST /tasks returned HTTP $http_code: $(cat "$TMP_DIR/submit-$task_id.json")"
  fi
  log "accepted (HTTP $http_code): $(cat "$TMP_DIR/submit-$task_id.json")"

  log "SSE stream $BASE_URL/tasks/$task_id/events — NODE_START frames, then the terminal event:"
  sse_log="$TMP_DIR/sse-$task_id.log"
  if ! curl -sS -N --max-time "$SSE_TIMEOUT_SECONDS" \
      -H "X-API-Key: $RUNTIME_API_KEY" \
      -H 'Accept: text/event-stream' \
      "$BASE_URL/tasks/$task_id/events" | tee "$sse_log"; then
    die "SSE stream failed or timed out after ${SSE_TIMEOUT_SECONDS}s (frames so far: $(wc -l < "$sse_log"))"
  fi
  grep -q 'NODE_START' "$sse_log" || die "no NODE_START frames captured in the SSE stream"
  grep -q 'TASK_COMPLETED' "$sse_log" || die "no TASK_COMPLETED terminal event in the SSE stream"
  log "SSE complete: $(grep -c 'NODE_START' "$sse_log") NODE_START frame(s), terminal TASK_COMPLETED"

  state="$(curl -sS -H "X-API-Key: $RUNTIME_API_KEY" "$BASE_URL/tasks/$task_id")" || die "GET /tasks/$task_id request failed"
  log "final state: $state"
  grep -q '"status":"SUCCEEDED"' <<<"$state" || die "task status is not SUCCEEDED: $state"
  grep -q "\"topology\":\"$topology\"" <<<"$state" || die "task topology is not $topology: $state"
}

run_task "Demo 1/3 · SOLO — single ReAct agent (CLEAR_SIMPLE band, demo task #1)" \
  "SOLO" 1 \
  "Summarize the key architectural principles and core components of single autonomous agent runtimes."

run_task "Demo 2/3 · FAN_OUT — 3 parallel branches + GATHER (CLEAR_COMPLEX band, demo task #9)" \
  "FAN_OUT" 3 \
  "Conduct a comparative analysis of modern distributed consensus algorithms, specifically Raft, Multi-Paxos, and Byzantine Fault Tolerant protocols, and how each balances leader election latency, log replication overhead, and partition tolerance."

run_task "Demo 3/3 · SUPERVISOR — researcher / writer / critic loop (CLEAR_COMPLEX band, demo task #11)" \
  "SUPERVISOR" 4 \
  "Synthesize an autonomous systems governance framework addressing safety certification, continuous telemetry auditing, human override hierarchies, and legal liability distribution across automated critical infrastructure."

# --- 4. Kafka request/reply roundtrip ------------------------------------------
banner "Kafka request/reply — console producer + consumer"
KAFKA_PRODUCE="docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server 127.0.0.1:9092"
KAFKA_CONSUME="docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server 127.0.0.1:9092"
K_TASK_ID="$(new_task_id)"
K_ENVELOPE="$(printf '{"task_id":"%s","task_type":"research","prompt":"Trace one task end to end through the Kafka request-reply ingress","breadth_hint":2,"forced_topology":"CHAIN"}' "$K_TASK_ID")"

# `docker compose exec -T` attaches the caller's stdin; a pty caller (e.g.
# `asciinema rec` of this script) never EOFs, which hangs the docker CLI after
# the container command exits — hence an explicit EOF on stdin for the
# consumer (the producer already gets one from the printf pipe). `timeout -k`
# escalates to SIGKILL past any CLI that ignores SIGTERM.
log "task_id: $K_TASK_ID"
log "producing one envelope onto adaptive-tasks-request:"
printf '      %s\n' "$K_ENVELOPE"
printf '%s\n' "$K_ENVELOPE" | $KAFKA_PRODUCE --topic adaptive-tasks-request \
  || die "kafka-console-producer failed"
log "produced."

log "consuming adaptive-tasks-reply (from-beginning, filtered to task $K_TASK_ID, timeout ${KAFKA_TIMEOUT_SECONDS}s):"
REPLY_LOG="$TMP_DIR/kafka-reply.log"
timeout -k 5 "$KAFKA_TIMEOUT_SECONDS" $KAFKA_CONSUME --topic adaptive-tasks-reply --from-beginning --timeout-ms 30000 \
  < /dev/null > "$REPLY_LOG" 2>"$TMP_DIR/kafka-consumer.err" || true
ACK_LINE="$(grep -m1 "$K_TASK_ID" "$REPLY_LOG" || true)"
if [[ -z "$ACK_LINE" ]]; then
  die "no ACK for task $K_TASK_ID on adaptive-tasks-reply (consumer output: $(wc -l < "$REPLY_LOG") lines)"
fi
log "ACK received on adaptive-tasks-reply:"
printf '      %s\n' "$ACK_LINE"

# --- 5. MCP streamable-http with plain curl ------------------------------------
banner "MCP streamable-http — curl POST $BASE_URL/mcp (auth-exempt, same port)"
M_TASK_ID="$(new_task_id)"
MCP_URL="$BASE_URL/mcp"
MCP_HEADERS=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

log "task_id: $M_TASK_ID"
log "step 1/4 — JSON-RPC initialize (server answers with an Mcp-Session-Id header):"
curl -sS -D "$TMP_DIR/mcp-init-headers.txt" -o "$TMP_DIR/mcp-init-body.txt" --max-time 30 \
  -X POST "$MCP_URL" "${MCP_HEADERS[@]}" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-demo","version":"0.1.0"}}}' \
  || die "MCP initialize request failed"
SESSION_ID="$(sed -n 's/^[Mm]cp-[Ss]ession-[Ii]d:[[:space:]]*//p' "$TMP_DIR/mcp-init-headers.txt" | tr -d '\r' | head -1)"
if [[ -z "$SESSION_ID" ]]; then
  die "no Mcp-Session-Id header in the initialize response: $(cat "$TMP_DIR/mcp-init-headers.txt")"
fi
log "initialize ok — Mcp-Session-Id: $SESSION_ID"
log "initialize result: $(sed 's/\\"/"/g' "$TMP_DIR/mcp-init-body.txt")"

log "step 2/4 — notifications/initialized (handshake completion, same session):"
NOTIF_CODE="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 \
  -X POST "$MCP_URL" "${MCP_HEADERS[@]}" -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}')" \
  || die "MCP notifications/initialized request failed"
log "notifications/initialized accepted (HTTP $NOTIF_CODE)"

log "step 3/4 — tools/call submit_task (forced_topology=SOLO override through MCP):"
SUBMIT_CALL="$(printf '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"submit_task","arguments":{"task_id":"%s","prompt":"Summarize how the MCP ingress maps tool calls onto the same task pipeline","forced_topology":"SOLO"}}}' "$M_TASK_ID")"
curl -sS -o "$TMP_DIR/mcp-submit.txt" --max-time 60 \
  -X POST "$MCP_URL" "${MCP_HEADERS[@]}" -H "Mcp-Session-Id: $SESSION_ID" \
  -d "$SUBMIT_CALL" || die "MCP tools/call submit_task request failed"
log "submit_task result: $(sed 's/\\"/"/g' "$TMP_DIR/mcp-submit.txt")"

log "step 4/4 — tools/call get_task (poll until SUCCEEDED, then show the final state):"
GET_CALL="$(printf '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_task","arguments":{"task_id":"%s"}}}' "$M_TASK_ID")"
for attempt in $(seq 1 20); do
  curl -sS -o "$TMP_DIR/mcp-get.txt" --max-time 60 \
    -X POST "$MCP_URL" "${MCP_HEADERS[@]}" -H "Mcp-Session-Id: $SESSION_ID" \
    -d "$GET_CALL" || die "MCP tools/call get_task request failed"
  if grep -q 'SUCCEEDED' "$TMP_DIR/mcp-get.txt"; then
    log "get_task result: $(sed 's/\\"/"/g' "$TMP_DIR/mcp-get.txt")"
    log "MCP roundtrip complete (fixture model — task finished in $attempt poll(s))."
    break
  fi
  if (( attempt == 20 )); then
    die "task $M_TASK_ID did not reach SUCCEEDED within 20 MCP get_task polls: $(cat "$TMP_DIR/mcp-get.txt")"
  fi
  sleep 1
done

# --- 6. PASS --------------------------------------------------------------------
banner "DEMO PASS — SOLO · FAN_OUT · SUPERVISOR via SSE, Kafka roundtrip, MCP roundtrip"
log "all five protocol flows completed against the hermetic compose stack"
exit 0
