# F3 — Real manual QA (smoke · demo · Kafka · MCP · cast) — Evidence

Worktree: `/home/kirill/IdeaProjects/adaptive-agent-runtime` (branch `main`, HEAD `725c9fa3b750f4da06a3f09bc88a83d554938228`, tree clean at start).
Task: F3 independent manual QA — REAL EXECUTION only (no log-reading substitutes), no fixes, no commits.
Context: `.env` present with real `RUNTIME_API_KEY` + `POSTGRES_PASSWORD` (never printed; all captured transcripts scanned post-run — 0 hits for either value, table at bottom). Concurrent `mvn verify` (F2) was running; no maven/docker contention failure observed (the app image `adaptive-agent-runtime:0.1.0` already existed, so no docker build was triggered by either check).

All five protocol flows were exercised against the live compose stack (hermetic `fixture-model` profile), each check re-executed by hand below — not inferred from prior evidence files.

---

## Check (a) — `./scripts/smoke.sh` → exit 0

Command: `./scripts/smoke.sh` (from repo root; stack up → REST submit → SSE → SUCCEEDED → teardown).

Exit code: **0**

Transcript tail (full log: `/tmp/opencode/f3/a-smoke.log`):

```
[smoke] task_id: 9d4f4808-aa59-428a-8c05-b38bd56b6d0f
[smoke] submitting task via POST http://localhost:8080/tasks
[smoke] submit accepted (HTTP 202): {"task_id":"9d4f4808-aa59-428a-8c05-b38bd56b6d0f","status":"ROUTED"}
[smoke] following SSE http://localhost:8080/tasks/9d4f4808-aa59-428a-8c05-b38bd56b6d0f/events (timeout 120s)
id:105
event:NODE_START
data:{"node": "solo-agent", "traceId": "0280fa53ae64f48aab11829f65733e68"}

id:106
event:TASK_COMPLETED
data:{"reason": "\"adaptive agents\"", "traceId": "0280fa53ae64f48aab11829f65733e68"}

[smoke] terminal SSE event: TASK_COMPLETED
[smoke] fetching final task state
[smoke] state: {"task_id":"9d4f4808-aa59-428a-8c05-b38bd56b6d0f","status":"SUCCEEDED","topology":"SOLO","result":"\"adaptive agents\"","error":null}
[smoke] PASS — compose stack smoke succeeded (task 9d4f4808-aa59-428a-8c05-b38bd56b6d0f)
[smoke] tearing the stack down (docker compose down)
 Container adaptive-agent-runtime-app-1 Stopped
 ... (all 4 containers + network removed)
 Network adaptive-agent-runtime_default Removed
EXIT_CODE=0
```

Verified chain: `docker compose up -d --wait` all-Healthy → POST /tasks HTTP 202 (X-API-Key: <redacted>) → SSE emitted `NODE_START` then terminal `TASK_COMPLETED` → GET shows `"status":"SUCCEEDED"` + non-empty `"result"` → EXIT-trap teardown ran.

**PASS**

---

## Check (b) — `bash scripts/demo.sh` → exit 0, three topologies via SSE

Command: `bash scripts/demo.sh` (full five-flow demo incl. its own Kafka + MCP sections).

Exit code: **0**

Per-topology outcomes (full log: `/tmp/opencode/f3/b-demo.log`):

| Demo | task_id | SSE NODE_START frames | Terminal SSE | Final state |
|---|---|---|---|---|
| 1/3 SOLO (breadth 1) | `d660379a-71b7-428d-abc6-ea6826ccd037` | 1 (`solo-agent`) | `TASK_COMPLETED` | `"status":"SUCCEEDED","topology":"SOLO"` + result |
| 2/3 FAN_OUT (breadth 3) | `9b8521b0-322d-4d26-8365-bb523c2465de` | 4 (`branch-3`,`branch-2`,`branch-1`,`gather`) | `TASK_COMPLETED` | `"status":"SUCCEEDED","topology":"FAN_OUT"` + result |
| 3/3 SUPERVISOR (breadth 4) | `1ea37d42-b2b5-44da-9d19-891c7a493fbc` | 6 (`researcher`,`writer`,`critic`,`writer`,`critic`,`finalize`) | `TASK_COMPLETED` | `"status":"SUCCEEDED","topology":"SUPERVISOR"` + result |

Transcript tails (abridged, one per demo):

```
[demo] SSE complete: 1 NODE_START frame(s), terminal TASK_COMPLETED
[demo] final state: {"task_id":"d660379a-…","status":"SUCCEEDED","topology":"SOLO","result":"\"adaptive agents\"","error":null}

[demo] SSE complete: 4 NODE_START frame(s), terminal TASK_COMPLETED
[demo] final state: {"task_id":"9b8521b0-…","status":"SUCCEEDED","topology":"FAN_OUT","result":"{\"degraded\": false, \"sections\": […3…], \"branchStatus\": {\"branch-1\": \"ok\", …}}","error":null}

[demo] SSE complete: 6 NODE_START frame(s), terminal TASK_COMPLETED
[demo] final state: {"task_id":"1ea37d42-…","status":"SUCCEEDED","topology":"SUPERVISOR","result":"{\"draft\": …, \"criticRounds\": 2, …}","error":null}

================ DEMO PASS — SOLO · FAN_OUT · SUPERVISOR via SSE, Kafka roundtrip, MCP roundtrip ================
[demo] all five protocol flows completed against the hermetic compose stack
EXIT_CODE=0
```

All three topologies reached `TASK_COMPLETED` on SSE **and** `SUCCEEDED` + matching `topology` on GET. API key appears only as `******** (value hidden)` in the demo's own masked output.

### demo.cast byte-diff behavior (noted per task instructions)

- `git status --short` after the rerun: **empty (clean)** — no `git checkout -- docs/demo.cast` was needed.
- Proof of byte-identity: `md5sum docs/demo.cast` = `99a4a42e7525198693a7fda493cf7d79` == `git show HEAD:docs/demo.cast | md5sum` = `99a4a42e7525198693a7fda493cf7d79`.
- Explanation: the cast is only rewritten when the demo is re-**recorded** under
  `asciinema rec --overwrite -c 'bash scripts/demo.sh' docs/demo.cast` (the T31 recording command). A plain
  `bash scripts/demo.sh` rerun never opens the file, so no timestamp/byte drift occurred in F3. Had it been
  re-recorded, timestamp/event-timing drift on every line would be expected (per T31 evidence: 284 events, 70.4s).

**PASS**

---

## Check (c) — One Kafka roundtrip via console producer + reply topic

Stack brought up manually first: `docker compose up -d --wait` → exit 0, all four services Healthy (log: `/tmp/opencode/f3/cd-stackup.log`). Kafka ingress needs no API key (envelope carries no secrets).

Commands executed (mirroring `scripts/demo.sh` §4 exactly):

```bash
K_TASK_ID="$(uuidgen)"   # 10d7ed92-b284-4b57-9fb3-cac4b86c6fd7
printf '%s\n' '{"task_id":"10d7ed92-b284-4b57-9fb3-cac4b86c6fd7","task_type":"research","prompt":"F3 manual Kafka ingress roundtrip check","breadth_hint":1,"forced_topology":"SOLO"}' \
  | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server 127.0.0.1:9092 --topic adaptive-tasks-request
# PRODUCE_EXIT=0
timeout -k 5 45 docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server 127.0.0.1:9092 \
  --topic adaptive-tasks-reply --from-beginning --timeout-ms 30000 < /dev/null   # </dev/null EOF per demo.sh pattern
# CONSUME_WRAPPER_EXIT=0
grep -m1 "$K_TASK_ID" /tmp/opencode/f3/c-reply.log   # ACK_FOUND_EXIT=0
```

Exit codes: producer pipe **0** · consumer wrapper **0** · ACK grep **0**.

Transcript (complete reply log — one line, the ACK):

```
{"task_id":"10d7ed92-b284-4b57-9fb3-cac4b86c6fd7","status":"ROUTED","decision":{"source":"OVERRIDE","verdict":null,"rationale":"forced_topology override from ingress"}}
```

Fresh task_id, `task_type=research`, `forced_topology=SOLO` envelope produced onto `adaptive-tasks-request`; immediate ACK for that exact task_id consumed from `adaptive-tasks-reply` (timeout-bounded, `</dev/null` stdin). No secrets present.

**PASS**

---

## Check (d) — One MCP streamable-http call via curl (demo.sh sequence mirrored exactly)

Commands (mirroring `scripts/demo.sh` §5 verbatim: same headers `Content-Type: application/json` + `Accept: application/json, text/event-stream`, same 4 steps, same 20×1s poll loop; MCP endpoint is auth-exempt):

| Step | Command (abridged) | Result |
|---|---|---|
| 1/4 | `curl -D headers -o body -X POST :8080/mcp -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",…}}'` | HTTP **200**; `Mcp-Session-Id: dfb2e384-33be-4dce-add7-0e56a35fe83c` captured |
| 2/4 | `curl -X POST :8080/mcp -H "Mcp-Session-Id: …" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'` | HTTP **202** |
| 3/4 | `tools/call submit_task` (fresh task_id `2e108234-5bda-4d31-b172-be1615575372`, `forced_topology=SOLO`) | SSE frame: `"text":"{\"task_id\":\"2e108234-…\",\"status\":\"ROUTED\"}"`, `isError:false` |
| 4/4 | `tools/call get_task` poll (≤20 × 1s) | **SUCCEEDED after 2 poll(s)** |

Transcript tails:

```
step 1: HTTP/1.1 200
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{…},"serverInfo":{"name":"adaptive-agent-runtime","version":"0.1.0"}}}
step 2: NOTIF_HTTP=202
step 3: data:{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\"task_id\":\"2e108234-5bda-4d31-b172-be1615575372\",\"status\":\"ROUTED\"}"}],"isError":false}}
step 4: data:{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"task_id\":\"2e108234-5bda-4d31-b172-be1615575372\",\"status\":\"SUCCEEDED\",\"topology\":\"SOLO\",\"result\":\"\\\"adaptive agents\\\"\",\"error\":null}"}],"isError":false}}
```

Session id is echoed by the server in the SSE `id:` field (not a secret; MCP path is auth-exempt by design). No API key involved.

**PASS**

---

## Check (e) — Committed `docs/demo.cast` parses as valid asciinema v2 JSON-lines

Checker: `/tmp/opencode/f3/parse-check.py` (strict: line 1 = JSON object with `version == 2`; every subsequent line = JSON array `[ts, "o", string-payload]` with numeric `ts`; any deviation exits 1).

Command + output (run against the **COMMITTED** blob, not the worktree file):

```bash
$ git show HEAD:docs/demo.cast | python3 /tmp/opencode/f3/parse-check.py
VALID asciinema v2: header version=2, 284 events, all [ts,"o",...], duration 70.4s
$ echo $?
0
```

Header (line 1 of the committed cast):

```
{"version": 2, "width": 80, "height": 24, "timestamp": 1791681986, "env": {"SHELL": "/bin/bash", "TERM": "xterm-256color"}}
```

284 events, every one `[ts,"o",…]`, 0 parse failures. Matches the T31-recorded stats (284 events / 70.4s) — the committed canonical recording is intact.

**PASS**

---

## Commands + exit codes

| Command | Result |
| --- | --- |
| `git rev-parse HEAD` | `725c9fa3b750f4da06a3f09bc88a83d554938228` (= expected 725c9fa); `git status` clean at start |
| (a) `./scripts/smoke.sh` | **exit 0** — 202 → SSE TASK_COMPLETED → SUCCEEDED + non-empty result → teardown |
| (b) `bash scripts/demo.sh` | **exit 0** — SOLO/FAN_OUT/SUPERVISOR each TASK_COMPLETED via SSE + SUCCEEDED on GET |
| (b) `git status --short` (post-rerun) | empty; `md5sum docs/demo.cast` == `git show HEAD:docs/demo.cast \| md5sum` → **no restore needed** (`git checkout --` not invoked) |
| (c) `docker compose up -d --wait` | **exit 0** (all Healthy) |
| (c) console-producer → `adaptive-tasks-request` | **exit 0** (1 envelope, fresh task_id) |
| (c) timeout-bounded console-consumer ← `adaptive-tasks-reply` (`</dev/null`) | **exit 0**; ACK grep **exit 0** |
| (d) MCP initialize / notifications/initialized / submit_task / get_task×2 | HTTP 200 / 202 / SSE `ROUTED` / SSE `SUCCEEDED` — all curl **exit 0** |
| (e) `git show HEAD:docs/demo.cast \| python3 /tmp/opencode/f3/parse-check.py` | **exit 0** — VALID, 284 events, 70.4s |
| Cleanup `docker compose down` | **exit 0** (network + all containers removed) |
| Final `git status --short` | empty (clean) |

## Secret scan of captured transcripts (method + result)

Method: `source .env` then `grep -cF -- "$RUNTIME_API_KEY" <file>` / `grep -cF -- "$POSTGRES_PASSWORD" <file>` over every captured log (`a-smoke.log`, `b-demo.log`, `c-produce.log`, `c-reply.log`, `d-init-headers.txt`, `d-init-body.txt`, `d-submit.txt`, `d-get.txt`). Secret values never echoed; hits counted only.

| File | RUNTIME_API_KEY hits | POSTGRES_PASSWORD hits |
|---|---|---|
| all 8 captured files | **0** | **0** |

demo.sh's own output masks the key as `******** (value hidden)` (rendered here as <redacted>).

## Constraints honored

- No code changes, no fixes, no commits (`git status` clean at end; evidence file is on disk only, never `git add`ed — `.omo/evidence/` is gitignored).
- `git checkout --` of demo.cast was **not** needed (file never dirtied — see check (b) note).
- The t28 worktree was not touched; only the main worktree at HEAD `725c9fa` was exercised.
- Every check was a real execution against the live compose stack; no log-reading substitutes.

---

## VERDICT

**F3: APPROVE**

(a) smoke exit 0 · (b) demo exit 0 with SOLO/FAN_OUT/SUPERVISOR all TASK_COMPLETED + SUCCEEDED, demo.cast untouched · (c) Kafka ACK roundtrip exit 0 · (d) MCP init→submit→SUCCEEDED exit 0 · (e) committed cast valid asciinema v2 (284 events) — all five checks green, no findings.
