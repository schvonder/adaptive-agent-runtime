# T31 — Asciinema demo recording — Evidence

Worktree: `/home/kirill/IdeaProjects/adaptive-agent-runtime` (branch `main`, HEAD `477a1c2` at task start).
Task: T31 of approved plan (`Asciinema demo recording`) — final implementation todo (31/31).

## Method — asciinema CLI 2.4.0 (real recording, no fallback needed)

The asciinema CLI was not installed. System `pip install --user` is blocked (PEP 668 externally-managed
environment, no sudo), so it was installed into a dedicated venv:

```bash
python3 -m venv /tmp/opencode/asciinema-venv
/tmp/opencode/asciinema-venv/bin/pip install asciinema   # asciinema 2.4.0
```

Recording command (run from the repo root, plain `bash scripts/demo.sh` under the asciinema pty —
every event in the cast is real terminal output of a real run; no synthetic events, no
idle-time-limit post-processing):

```bash
TERM=xterm-256color /tmp/opencode/asciinema-venv/bin/asciinema rec --overwrite \
  --command 'bash scripts/demo.sh' docs/demo.cast
```

Honest failure ledger (both fixed before the committed cast):

1. **First recording attempt hung >9 min** in the Kafka section: `docker compose exec -T` attaches the
   caller's stdin; under the asciinema pty stdin never EOFs, so the docker CLI never terminated after
   the container-side consumer exited (plain runs were immune — the tool harness gives null stdin).
   Reproduced in isolation with `script -qec`. Fix: `< /dev/null` on the consumer exec (stdin EOF) +
   `timeout -k 5` SIGKILL escalation. Verified under `script -qec`: clean exit in 14.8 s.
2. **Second recording attempt failed fast**: the `< /dev/null` was initially also added to the
   producer line, where it overrode the `printf '%s\n' envelope |` pipe — the console-producer got
   null stdin, produced zero messages, and no ACK could arrive ("no ACK … consumer output: 0 lines").
   Fix: redirect only on the consumer; the producer's stdin-EOF comes from the pipe.
   (asciinema rec does not propagate the child's non-zero exit — the failure surfaced through the
   post-recording content check below, which is why that check exists.)

## scripts/demo.sh (what it runs, in order)

1. Fails fast on missing `.env` / empty `RUNTIME_API_KEY` / `POSTGRES_PASSWORD`; sources `.env`
   **silently** and echoes only masked placeholders (`RUNTIME_API_KEY=******** (value hidden)`).
2. `docker compose up -d --wait`; EXIT trap tears the stack down.
3. Three REST+SSE demos with **fresh task_ids** and `forced_topology` matching the docs/TASKS.md
   bands — SOLO (demo task #1 band, breadth 1), FAN_OUT (demo task #9 band, breadth 3),
   SUPERVISOR (demo task #11 band, breadth 4): each `POST /tasks` (HTTP 202, masked key display)
   → `curl -N` SSE stream (all `NODE_START` frames + terminal `TASK_COMPLETED` shown live, both
   asserted in the stream log) → final `GET` asserting `"status":"SUCCEEDED"` + matching topology.
4. Kafka roundtrip: one envelope piped to `kafka-console-producer.sh --topic adaptive-tasks-request`
   (fresh task_id, `forced_topology=CHAIN`), then `kafka-console-consumer.sh --topic
   adaptive-tasks-reply --from-beginning --timeout-ms 30000` (bounded by `timeout -k 5 45`),
   filtered to the fresh task_id; the ACK line is printed and asserted.
5. MCP streamable-http via plain curl: JSON-RPC `initialize` (session id captured from the
   `Mcp-Session-Id` response header), `notifications/initialized` (HTTP 202), `tools/call
   submit_task` (`forced_topology=SOLO`), `tools/call get_task` polled to `SUCCEEDED`.
6. `DEMO PASS` banner; trap → `docker compose down`.

## Cast stats — docs/demo.cast

| Metric | Value |
|---|---|
| Format | asciinema v2, JSON-lines (`docs/demo.cast`, 285 lines incl. header) |
| Size | 159,126 bytes |
| Events | 284 (all `"o"` output events — no input events recorded) |
| Duration | 70.4 s of recorded stream time |
| Header | `version=2 width=80 height=24 timestamp=1791681986 env={SHELL:/bin/bash, TERM:xterm-256color}` |

## Verification 1 — asciinema v2 parse check (script + output)

Script `/tmp/opencode/t31/parse-check.py` (asserts: line 1 is a JSON object with `version == 2` +
int width/height/timestamp; every subsequent non-empty line parses as a JSON `[time, code, data]`
tuple; timestamps ≥ 0 and monotonic; codes ∈ {o,i,m,r}; o/i payloads are strings; prints stats):

```python
import json, sys
lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
assert lines, "cast is empty"
header = json.loads(lines[0])
assert header.get("version") == 2
count, last_t, types, output_bytes = 0, 0.0, {}, 0
for n, line in enumerate(lines[1:], start=2):
    if not line.strip():
        continue
    ev = json.loads(line)
    assert isinstance(ev, list) and len(ev) in (2, 3)
    t, code = ev[0], ev[1]
    assert isinstance(t, (int, float)) and t >= 0 and t >= last_t - 1e-9
    last_t = t
    assert code in ("o", "i", "m", "r"), f"line {n}: {code!r}"
    if code in ("o", "i"):
        assert isinstance(ev[2], str)
        if code == "o":
            output_bytes += len(ev[2])
    types[code] = types.get(code, 0) + 1
    count += 1
print(f"header: version=2 width={header['width']} height={header['height']}")
print(f"events: {count} types={types} duration={last_t:.1f}s output_bytes={output_bytes}")
print("asciinema v2 cast: VALID")
```

Output (exit 0):

```
header: version=2 width=80 height=24 timestamp=1791681986
events: 284 types={'o': 284} duration=70.4s output_bytes=117611
asciinema v2 cast: VALID
```

## Verification 2 — cast records all three topology outputs (QA happy path)

Concatenated cast output (CR stripped — the pty applies ONLCR), assertion matrix — all present:

```
OK   SOLO section + solo-agent NODE_START          ("node": "solo-agent")
OK   FAN_OUT branch-1/2/3 + gather                (3 parallel branch NODE_STARTs interleave, then gather)
OK   SUPERVISOR researcher/writer/critic           (loop x2 rounds + finalize; criticRounds:2 in result)
OK   TASK_COMPLETED x3 / SUCCEEDED final states x3
OK   Kafka produced + ACK(OVERRIDE)               ("source":"OVERRIDE" decision in the reply ACK)
OK   MCP initialize + session id / initialized 202 / submit_task / get_task SUCCEEDED
OK   DEMO PASS banner + masked key display + stack teardown at end
```

## Verification 3 — no secrets in the cast (QA failure path)

Secret values were passed via pattern files (`grep -F -f <file>`), never on a command line:

| Check | Result |
|---|---|
| `grep -cF -f <RUNTIME_API_KEY value> docs/demo.cast` | **0** |
| `grep -cF -f <POSTGRES_PASSWORD value> docs/demo.cast` | **0** |
| `grep -cE 'sk-[A-Za-z0-9_-]{8,}' docs/demo.cast` | **0** |
| `grep -cE '(api[_-]?key\|password\|secret)["=: ]+[A-Za-z0-9+/]{12,}' docs/demo.cast` | **0** |

The only credential-related strings in the cast are the literal masked placeholders
(`X-API-Key: ******** (value hidden)`, `RUNTIME_API_KEY=******** (value hidden)`).

## Verification 4 — demo.sh runs green OUTSIDE the recording (plain run)

`time ./scripts/demo.sh` (no pty, no recorder), after the Kafka stdin fixes — exit 0 in 1m0.8s:

```
[demo] ACK received on adaptive-tasks-reply:
      {"task_id":"8ee0d317-7522-4532-a1ce-40a4db2819d4","status":"ROUTED","decision":{"source":"OVERRIDE","verdict":null,"rationale":"forced_topology override from ingress"}}
...
[1;36m================ DEMO PASS — SOLO · FAN_OUT · SUPERVISOR via SSE, Kafka roundtrip, MCP roundtrip ================[0m
[demo] all five protocol flows completed against the hermetic compose stack
DEMO_EXIT=0   (real 1m0.801s)
```

(A first plain run before the Kafka hardening had already passed in 1m10.0s — the fixes target
pty-specific behavior, not the plain path.)

## Verification 5 — README links the cast

README gained a `## Demo` section (all prior content untouched) linking
[`scripts/demo.sh`](scripts/demo.sh) and [`docs/demo.cast`](docs/demo.cast) with the exact playback
command `asciinema play docs/demo.cast`, plus an HTML-comment placeholder for a future
asciinema.org embed. Relative-link checker (same method as T30) — exit 0:

```
OK: BENCHMARK.md
OK: bench/README.md
OK: compose.yaml
OK: docs/DEGRADATION.md
OK: docs/demo.cast
OK: docs/ENVELOPE.md
OK: docs/TASKS.md
OK: .env.example
OK: scripts/demo.sh
OK: scripts/smoke.sh
OK: SPIKE.md
---
checked 11 relative link targets, fail=0
```

## Commands + exit codes

| Command | Result |
| --- | --- |
| `./scripts/demo.sh` (plain, post-fix) | exit 0 — 1m0.8s, all five flows PASS |
| `asciinema rec --overwrite -c 'bash scripts/demo.sh' docs/demo.cast` | recording finished; cast validated below |
| `python3 /tmp/opencode/t31/parse-check.py docs/demo.cast` | exit 0 — VALID, 284 events, 70.4s |
| cast content assertion matrix (17 checks) | ALL OK |
| secret greps (4 patterns) | 0 / 0 / 0 / 0 matches |
| `bash /tmp/opencode/t31/link-check.sh` | exit 0 — 11/11 relative links resolve |
| `bash -n scripts/demo.sh` | OK |

## Commit contents

`scripts/demo.sh`, `docs/demo.cast`, `README.md`, and force-added
`.omo/evidence/task-31-adaptive-agent-runtime.md` + `.omo/evidence/task-30-adaptive-agent-runtime.md`
(the T30 agent left the task-30 evidence file uncommitted on disk; folded into this commit and
disclosed in the commit body — `.omo/` is gitignored, evidence files are committed by repo precedent
via `git add -f`).
