# F1 — Plan compliance audit — Evidence

Repo: `/home/kirill/IdeaProjects/adaptive-agent-runtime` (branch `main`, HEAD `725c9fa`,
32 commits, working tree clean at audit start).
Plan file audited: `/home/kirill/.omo/plans/adaptive-agent-runtime.md` (351 lines, OUTSIDE the repo — read there, NOT modified).
Task: F1 of the plan's Final verification wave. READ-ONLY audit: no code, plan, or other-task
evidence modified; nothing committed; `-t28` worktree untouched.

Audit method: raw commands executed 2026-10-11 against the repo + plan file; every check below
reproduces its command and verbatim output (trimmed only where marked).

---

## Check (a) — Todo checkbox states + field completeness

Commands:

```
$ grep -cE '^- \[ \] [0-9]+\. ' /home/kirill/.omo/plans/adaptive-agent-runtime.md
15
$ grep -cE '^- \[x\] [0-9]+\. ' /home/kirill/.omo/plans/adaptive-agent-runtime.md
16
```

Checkbox states (from `grep -nE '^- \[[ x]\] [0-9]+\. ' <plan>`):

| Todos | State |
|---|---|
| 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 | `[x]` checked (16) |
| 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31 | `[ ]` **unchecked (15)** |
| F1–F4 (final verification wave, not numbered todos) | `[ ]` unchecked (expected — F1 is this audit) |

All 31 todo numbers 1..31 exist exactly once (verified programmatically: 31 headers found, no gaps).
F-wave lines use `F1.`–`F4.` prefixes and are not matched by the `[0-9]+` regex.

Field completeness — every todo chunk was checked for the five required fields
(`What to do`, `References`, `Acceptance criteria`, `QA scenarios`, `Commit: Y |`):

```
todos with missing fields: NONE — all 31 have all 5 fields
plan "Commit: Y |" lines found: 31
```

**Result (a):** all 31 todos present, all fields complete — BUT 15 boxes unchecked (17–31),
including 13 todos (19–31) whose commits AND evidence files exist, and 2 (17–18) whose commits
exist. The plan's own F1 formula expects the todo set fully resolved (its literal text
"`grep -cE '^- \[ \] [0-9]+\. '` = 31" cannot hold simultaneously with completed work; the
evident intent — all 31 boxes `[x]` — is NOT met: 16/31 checked). Plan state lags behind the
repo state. LOW severity (bookkeeping, not missing work). FINDING #2.

## Check (b) — Evidence files N=1..31 on disk

Command per N: `[ -f .omo/evidence/task-N-adaptive-agent-runtime.md ] && stat -c%s <file>`:

| N | Exists | Bytes | | N | Exists | Bytes | | N | Exists | Bytes |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **NO** | — | | 12 | **NO** | — | | 23 | yes | 7,541 |
| 2 | **NO** | — | | 13 | **NO** | — | | 24 | yes | 5,574 |
| 3 | **NO** | — | | 14 | **NO** | — | | 25 | yes | 7,536 |
| 4 | **NO** | — | | 15 | **NO** | — | | 26 | yes | 7,287 |
| 5 | **NO** | — | | 16 | **NO** | — | | 27 | yes | 2,381 |
| 6 | **NO** | — | | 17 | **NO** | — | | 28 | yes | 5,707 |
| 7 | **NO** | — | | 18 | **NO** | — | | 29 | yes | 10,209 |
| 8 | **NO** | — | | 19 | yes | 8,099 | | 30 | yes | 9,305 |
| 9 | **NO** | — | | 20 | yes | 9,516 | | 31 | yes | 9,325 |
| 10 | **NO** | — | | 21 | yes | 10,275 | | | | |
| 11 | **NO** | — | | 22 | yes | 8,648 | | | | |

Summary: 13/31 exist (19–31, all non-empty, 2,381–10,275 bytes). **MISSING: N = 1, 2, 3, 4, 5,
6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18 (18 files)** — waves 0–4 complete plus Wave-5
precursors. Not fabricated by this audit; reported as found. `.omo/evidence/` contains only the
13 files above plus this F1 file (no F2–F4 yet — expected, they follow this wave).
Note: evidence 19–22 are on disk but gitignored (untracked); 23–31 are force-added per repo
precedent set in the T31 commit (disclosed there). F1 checks disk existence — passes for 19–31.
**Result (b): FAIL for todos 1–18. HEADLINE FINDING #1.**

## Check (c) — Commit count + per-todo subject match

Command:

```
$ git log --oneline | wc -l
32
```

All 32 subjects were matched against the plan's 31 `Commit: Y | <message>` lines
(exact-subject comparison, programmatic):

| N | Plan Commit subject | Found |
|---|---|---|
| 1 | chore(spike): wave-0 integration spike (LG4j 1.9.1 x Spring AI 2.0.1 x Boot 4.1.1) | YES (9fa4450) |
| 2 | chore(repo): multi-module skeleton, JDK 25, pinned BOMs | YES (e107dbd) |
| 3 | test(harness): singleton PG + spawned-app runner | YES (d1bef12) |
| 4 | feat(core): Flyway V1 schema (tasks, progress_events) | YES (74b94e5) |
| 5 | feat(core): TaskEnvelope v1 contract + protocol mapping doc | YES (6019b1f) |
| 6 | feat(core): TaskStore state machine + repository | YES (cc460bc) |
| 7 | feat(core): progress event log with sinceSeq replay | YES (db41076) |
| 8 | feat(core): deterministic rule-based complexity scorer | YES (a03fab1) |
| 9 | feat(core): LLM classifier leg with rule fallback | YES (52d4bbf) |
| 10 | feat(topologies): topology registry + routing decision persistence | YES (e3df8c8) |
| 11 | feat(workload): search/fetch/cite tools with fixture adapters | YES (cf86ab9) |
| 12 | feat(topologies): SOLO ReAct topology | YES (c9078d8) |
| 13 | feat(topologies): CHAIN sequential topology | YES (12cd267) |
| 14 | feat(topologies): FAN_OUT/GATHER parallel topology | YES (283952f) |
| 15 | feat(topologies): SUPERVISOR_TEAM topology | YES (18949de) |
| 16 | feat(core): checkpointed execution runner with budget/deadline guards | YES (4e059d3) |
| 17 | feat(core): startup resume of interrupted tasks | YES (7a840d9) |
| 18 | feat(core): taskId idempotency with advisory lock | YES (c7d1f1d) |
| 19 | feat(core): budget guard + degradation matrix | YES (0eb7179) |
| 20 | feat(core): deadline guard + zombie-free sweep | YES (0b09120) |
| 21 | feat(ingress): REST+SSE ingress with API key auth | YES (b3b635f) |
| 22 | feat(ingress): Kafka request/reply + progress topic | YES (42cc8cb) |
| 23 | feat(ingress): MCP streamable-http server tools | YES (23a98b5) |
| 24 | test(ingress): cross-protocol contract parity e2e | YES (48f66ac) |
| 25 | feat(workload): 12-task demo set with fixtures + expected routing | YES (905b877) |
| 26 | feat(bench): cross-topology benchmark harness | YES (32d4a06) |
| 27 | docs(bench): BENCHMARK.md results | YES (f7450b2) |
| 28 | feat(obs): micrometer-tracing OTLP + jaeger compose | YES (ce50031) |
| 29 | feat(ops): compose stack + smoke script | YES (dbd480d) |
| 30 | docs(readme): positioning, architecture, quickstart | YES (477a1c2) |
| 31 | docs(demo): asciinema cast + demo script | YES (725c9fa = HEAD) |

**Match: 31/31, exact subjects, in dependency-consistent order.**
Extra commit not matching any plan `Commit:` line: `157ae1b fix(core): conditional LlmClassifier
activation` (between todos 9 and 10). Conventional format, but deviates from the plan's
"one commit per todo exactly" strategy. LOW severity note. FINDING #3.
**Result (c): PASS (31/31 matched; 32 = 31 todo commits + 1 disclosed extra fix).**

## Check (d) — Acceptance re-runnability from evidence

Method: each evidence file for 19–31 read in full; criterion = the todo's stated
"Acceptance criteria (agent-executable)" is covered by a recorded command + recorded output
(test names, counts, exit codes, transcripts) inside the evidence file.

| N | Acceptance (abbreviated) | Recorded command + output in evidence? |
|---|---|---|
| 19 | per-topology budget-cut tests → BUDGET_EXCEEDED + partial + matrix behavior | YES — `BudgetDegradationIntegrationTest` (4 tests, per-topology list) + `mvn -pl core test` 41/0/0, `mvn -pl topologies test` 38/0/0, app `clean verify` transcript |
| 20 | 200ms deadline → DEADLINE_EXCEEDED + executor baseline within 5s | YES — `DeadlineSweepIntegrationTest` 5 scenarios (incl. `planAcceptanceDeadlineWithin5s`) + full-root `mvn -B clean verify` EXIT_CODE=0 reactor transcript |
| 21 | no key → 401; submit→poll→SSE ordered incl. Last-Event-ID replay | YES — `RestIngressIntegrationTest` 5 tests (auth/401, dedup 202/200, `sseOrderedWithReplay`, 400s, 404) + per-module test counts + reactor BUILD SUCCESS |
| 22 | Kafka e2e: request→reply ACK, ordered progress topic, redelivery → single execution | YES — `KafkaIngressIntegrationTest` 4 tests incl. `redeliveredDuplicateSingleExecution` (event COUNT=1 proof) + `mvn clean verify` exit 0 transcript |
| 23 | MCP submit→get_task→SUCCEEDED; /mcp exempt vs /tasks 401 | YES — `McpIngressIntegrationTest` 5 tests (roundtrip, idempotent dup, `coexistenceAndAuthExemption`, unknown-id tool error, invalid args) + exit codes 0 |
| 24 | contract test green; decision + result-hash equality across 3 transports | YES — `CrossIngressContractE2eTest` 3 tests (byte-identical decision JSON, SHA-256 equality, auth matrix) + ingress 18/0/0 |
| 25 | 12/12 demo tasks route as documented; fixtures load | YES — `DemoTasksRoutingTest` 3 tests + 13-row band/score matrix + workload/topologies/app test counts |
| 26 | `-Pbenchmark` produces BENCHMARK.md 4×12×3 + echoed config | YES — commands with exit 0 + extracted BENCHMARK.md config block + 48-row matrix sample + ERROR-row proof |
| 27 | BENCHMARK.md real table OR explicit NOT RUN + command | YES — NOT RUN fallback (plan-sanctioned): diagnosis timeline, model repin disclosure, repro command; on-disk `BENCHMARK.md` confirmed to carry the `NOT RUN` marker (1 match) |
| 28 | capturing-exporter spans test + jaeger query API returns trace | YES — `CapturingSpanExporterTest` (2), `JaegerOtlpIntegrationTest` (trace ID + 15 spans listed), `ExporterDownNonFatalTest`; per-module totals 128 tests/0 fail |
| 29 | `./scripts/smoke.sh` on clean docker env exits 0 | YES — full smoke transcript (HTTP 202 → SSE TASK_COMPLETED → SUCCEEDED, `SMOKE_EXIT=0`), both QA fail-fast transcripts, failed-iteration ledger |
| 30 | all relative links resolve; quickstart matches smoke.sh | YES — link-check script + output (9/9 OK exit 0), quickstart-match 7/7, mermaid 5 checks, banned-tone greps clean |
| 31 | demo.cast valid JSON-lines; README links it; no secrets | YES — parse-check script + output (`VALID`, 284 events, 70.4s), 17-point content matrix, 4 secret greps all 0, link-check 11/11, plain-run DEMO_EXIT=0 |

Artifact spot-checks re-run by this audit (commands + results):
`BENCHMARK.md` exists (1,122 B, `NOT RUN` ×1) · `README.md` exists (10,387 B) · `docs/demo.cast`
exists (159,126 B, valid asciinema v2 header) · `git status --short` → 0 lines (clean tree).

Todos 1–18: evidence files absent from disk (Check b) → acceptance **not verifiable from repo**,
even though commits for all of 1–18 exist (Check c). Included in FINDING #1.
**Result (d): PASS for 19–31 (13/13 record command + output); NOT VERIFIABLE for 1–18.**

## Commands + exit codes (audit's own)

| Command | Result |
| --- | --- |
| `grep -cE '^- \[ \] [0-9]+\. ' <plan>` | 15 |
| `grep -cE '^- \[x\] [0-9]+\. ' <plan>` | 16 |
| per-todo field scan (What to do/References/Acceptance/QA/Commit) | 31/31 complete, 0 missing |
| evidence existence probe ×31 | 13 exist, 18 missing |
| `git log --oneline \| wc -l` | 32 |
| plan-subject ↔ git-subject exact match | 31/31 |
| evidence reads T19–T31 (13 files, full) | all have commands + outputs |
| `git rev-parse HEAD` | 725c9fa3b750f4da06a3f09bc88a83d554938228 |
| `git status --short` | empty (clean) |

## Findings

1. **[MAJOR — headline] Evidence files for todos 1–18 missing on disk** (18 of 31:
   task-1 … task-18). Waves 0–4 (spike, skeleton/harness/schema/contract/store/events,
   classifier/registry, four topologies, durable-execution core) have commits but no recorded
   evidence; their acceptance criteria are not re-runnable or verifiable from the repo.
   Violates the plan's Verification strategy ("Evidence: .omo/evidence/task-N-… for every todo")
   and Success criteria ("Evidence files exist for every todo").
2. **[LOW] 15 todo checkboxes unchecked (todos 17–31)** although commits exist for all of them
   and evidence exists for 19–31 — plan bookkeeping lags the repo; the plan's own F1(a)
   expectation (all 31 resolved/checked) is not met.
3. **[LOW] One commit beyond the one-commit-per-todo strategy**: `157ae1b fix(core): conditional
   LlmClassifier activation` (conventional format; disclosed here, not in the plan).

## Verdict

F1: REJECT
1. MAJOR: evidence files task-1 … task-18 (18/31) missing on disk — acceptance for todos 1–18 not verifiable from repo.
2. LOW: plan todos 17–31 left unchecked despite commits (+ evidence for 19–31); plan F1(a) "all resolved" expectation unmet (16/31 checked, grep '[ ]' = 15).
3. LOW: 1 extra commit (157ae1b fix(core)) beyond the plan's one-commit-per-todo strategy (commit-subject match otherwise 31/31 exact).
