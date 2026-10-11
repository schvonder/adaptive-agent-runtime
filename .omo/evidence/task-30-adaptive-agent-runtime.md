# T30 — README + architecture docs — Evidence

Worktree: `/home/kirill/IdeaProjects/adaptive-agent-runtime` (branch `main`, HEAD `dbd480d` at task start).
Task: T30 of approved plan (`README + architecture docs`).

## Structure summary (what README.md contains, in order)

1. **H1 + positioning** — "adaptive durable multi-agent runtime for the JVM"; explicit "a runtime, not
   an agent platform" paragraph enumerating what does NOT exist (web UI, designer, dynamic graph
   assembly, MCP client/tool federation, auth beyond single `X-API-Key`).
2. **Why** — the empty-niche paragraph: frameworks ship primitives, platforms take over the runtime;
   this project = envelope in → complexity-classified routing → durable execution with degradation.
3. **Architecture** — mermaid `flowchart LR`, 15 nodes (14 + counted without the `flowchart LR`
   header line; ≤ 20 per spec): 3 ingresses → `TaskEnvelope v1` → classifier → registry → 4 topologies
   → `ExecutionRunner` → Postgres / SSE out / Kafka progress topic. Stack line cites Java 25,
   Boot 4.1.1, Spring AI 2.0.1, LangGraph4j 1.9.1 (links SPIKE.md), PG 17, Kafka 3.9 KRaft,
   micrometer-tracing OTLP → Jaeger.
4. **Quickstart** — `cp .env.example .env` / `docker compose up -d --wait` / `./scripts/smoke.sh`
   (exact strings, see match proof below); what smoke proves (202 submit → SSE `TASK_COMPLETED` →
   `SUCCEEDED` + non-empty result); hermetic `fixture-model` default; Jaeger UI
   http://localhost:16686, service `adaptive-agent-runtime`.
5. **Protocols** — 3 bullets (REST+SSE w/ `X-API-Key` + RFC 7807; MCP auth-exempt streamable-http,
   tool-error-not-500 semantics; Kafka immediate-ACK request/reply + live-tail progress + DLT) +
   links docs/ENVELOPE.md, bench/README.md.
6. **Adaptive routing** — band table (≤0 SOLO / 1–19 AMBIGUOUS→LLM leg→CHAIN / ≥20 breadth<4
   FAN_OUT / ≥20 breadth≥4 SUPERVISOR) + `forced_topology` override note + link docs/TASKS.md.
7. **Benchmark** — honest paragraph: harness shape (4 × 12 × N≥3, medians, stub CI profile) +
   **BENCHMARK.md carries a NOT RUN status** with the pinned profile and reproduction command
   (`mvn test -pl bench -Pbenchmark-real`) — linked, not papered over.
8. **Durability and degradation** — checkpoint saver (`threadId = task_id`), `StartupRescan`
   resume-after-kill (+ deadline-expired-while-down → `DEADLINE_EXCEEDED`), `task_id` idempotency
   (no 409 path), node-boundary guards + `RunningTaskSweep`, per-topology degradation one-liners +
   link docs/DEGRADATION.md.
9. **Project layout** — module table: core / topologies / ingress / workload / bench / app / spike,
   each a one-liner grounded in the actual package layout (`core/{classify,envelope,model,progress,store,trace}`,
   `topologies/{budget,chain,fanout,guard,registry,routing,runner,solo,supervisor,sweep}`,
   `ingress/{common,kafka,mcp,rest}`, `workload/tools`).
10. **Documentation index** — SPIKE.md, docs/ENVELOPE.md, docs/DEGRADATION.md, docs/TASKS.md,
    BENCHMARK.md, bench/README.md.

### Deferral note (deliberate)

`docs/demo.cast` is **NOT linked** from README: that file is created by todo 31, which also adds its
README link. The link checker therefore reports 9/9 clean today; todo 31 will extend both the README
and this check.

## Verification 1 — relative-link check (script + output)

Script (`/tmp/opencode/t30/link-check.sh`, run from repo root; extracts `](target)` links from
README.md, skips `http(s):`/`mailto:`, strips `#anchors`, asserts the file part exists and any
cross-file anchor matches a heading):

```bash
#!/usr/bin/env bash
set -uo pipefail
cd /home/kirill/IdeaProjects/adaptive-agent-runtime
fail=0; count=0
mapfile -t rel < <(grep -oE '\]\([^)]+\)' README.md | sed -E 's/^\]\(//; s/\)$//' | grep -vE '^(https?://|mailto:)' | sort -u)
for link in "${rel[@]}"; do
  count=$((count+1))
  file="${link%%#*}"
  if [[ -z "$file" ]]; then           # pure in-page anchor
    frag="${link#\#}"; frag="${frag// /-}"; frag="${frag,,}"
    grep -qiE "^#{1,6} .*$frag" README.md && echo "OK: $link" || { echo "DEAD ANCHOR: $link"; fail=1; }
  elif [[ ! -e "$file" ]]; then
    echo "DEAD: $link"; fail=1
  else
    if [[ "$link" == *"#"* && "$file" == *.md ]]; then
      frag="${link#*#}"; frag="${frag// /-}"; frag="${frag,,}"
      grep -qiE "^#{1,6} .*$frag" "$file" && echo "OK: $link" || { echo "DEAD ANCHOR: $link"; fail=1; }
    else echo "OK: $link"; fi
  fi
done
echo "---"
echo "checked $count relative link targets, fail=$fail"
exit $fail
```

Output (exit 0):

```
OK: BENCHMARK.md
OK: bench/README.md
OK: compose.yaml
OK: docs/DEGRADATION.md
OK: docs/ENVELOPE.md
OK: docs/TASKS.md
OK: .env.example
OK: scripts/smoke.sh
OK: SPIKE.md
---
checked 9 relative link targets, fail=0
```

**9 links OK, 0 dead.** (No QA dead-link fix was needed — the only candidate, `docs/demo.cast`,
was never linked; see deferral note.)

## Verification 2 — quickstart commands match smoke.sh / .env.example exactly

Script asserts each README quickstart command string appears verbatim in the defining artifact:

```bash
check 'cp .env.example .env'        scripts/smoke.sh   # smoke fail-fast message (line 28)
check 'cp .env.example .env'        .env.example       # env template header
check 'docker compose up -d --wait' scripts/smoke.sh   # stack startup command (line 55)
check './scripts/smoke.sh'          .env.example       # documented invocation (header)
# plus: each of the three command strings present in README.md quickstart block
```

Output (exit 0):

```
MATCH: 'cp .env.example .env' found in scripts/smoke.sh (smoke fail-fast message)
MATCH: 'cp .env.example .env' found in .env.example (env template header)
MATCH: 'docker compose up -d --wait' found in scripts/smoke.sh (stack startup command)
MATCH: './scripts/smoke.sh' found in .env.example (documented invocation)
MATCH: 'cp .env.example .env' present in README quickstart
MATCH: 'docker compose up -d --wait' present in README quickstart
MATCH: './scripts/smoke.sh' present in README quickstart
quickstart-match fail=0
```

Additional accuracy notes folded into the README text: smoke.sh runs `docker compose up -d --wait`
itself and tears the stack down via EXIT trap (so the standalone `up` line is marked as optional
keep-alive); smoke submits a SOLO-band envelope with `X-API-Key`; the stack defaults to the hermetic
`fixture-model` profile (no LLM, no network) — all verbatim behaviors of the committed script.

## Verification 3 — mermaid block syntactic sanity (method + output)

Method (documented in `/tmp/opencode/t30/mermaid-check.sh`): (1) exactly one ```mermaid fence and a
clean >5-line extraction; (2) per-line double-quote parity (labels fully quoted); (3) per-line
bracket balance after stripping quoted spans (`[`==`]`, `(`==`)`); (4) ≥ 10 edges and every edge
line strips down to the shape `ID --> ID` with uppercase-identifier endpoints; (5) node census over
edge endpoints (excluding the `flowchart LR` header): every ID wired into ≥ 1 edge, ≤ 20 nodes.

Output (exit 0):

```
fences=1 diagram_lines=18
check 2 quote parity: OK
check 3 shape balance: OK
check 4 edge lines reduce to 'ID --> ID': OK (16 edges)
check 5 node census: OK (14 distinct nodes, all wired)
node budget <= 20: OK
mermaid-check fail=0
```

## Verification 4 — banned-tone greps

Greps run against README.md (all returned no matches; exit code 1 = clean):

| Pattern (grep -niE) | Result |
|---|---|
| `coming soon\|roadmap\|will soon\|stay tuned\|in the future\|planned` | no matches |
| `blazing\|seamless\|effortless\|powerful\|lightning\|cutting-edge\|state-of-the-art\|revolutionar\|game-chang\|elegant\|beautiful\|amazing\|awesome\|stunning\|delight` | no matches |
| `best-in-class\|world-class\|next-gen\|unleash\|supercharge\|robust and scalable` | no matches |
| `grep -c '!'` (exclamation marks, entire file) | **0** |

No roadmap promises, no "coming soon" sections; every capability claim in the README maps to a read
artifact: version combo → SPIKE.md; auth matrix/tool semantics/topics/DLT → docs/ENVELOPE.md;
bands/distribution → docs/TASKS.md; degradation behaviors → docs/DEGRADATION.md; NOT RUN status and
repro command → BENCHMARK.md; quickstart behaviors → scripts/smoke.sh + compose.yaml + .env.example;
resume/sweep/idempotency → `StartupRescan`, `ExecutionRunner`, `RunningTaskSweep`, TaskStore
(no-409) source; tracing spans/traceId → T28 tests cited in compose.yaml comments and T29 evidence.

## Commands + exit codes

| Command | Result |
| --- | --- |
| `/tmp/opencode/t30/link-check.sh` | exit 0 — 9/9 relative links resolve |
| `/tmp/opencode/t30/quickstart-match.sh` | exit 0 — 7/7 exact-string matches |
| `/tmp/opencode/t30/mermaid-check.sh` | exit 0 — all 5 sanity checks pass |
| banned-tone greps (3 × grep -niE + grep -c '!') | clean / 0 |
| `git status` before commit | only `README.md` + this evidence file (evidence is gitignored per plan — only README.md committed) |

Note: evidence files are excluded from commits by the plan's commit strategy ("never commit
secrets/.env/.omo/evidence"); the T30 commit therefore contains README.md only. (`git check-ignore`
confirms `.omo/evidence/` is ignored via the `.omo/evidence/` pattern in `.gitignore`.)
