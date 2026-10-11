# F2 — Code quality audit — Evidence

Repo: `/home/kirill/IdeaProjects/adaptive-agent-runtime` (branch `main`, HEAD `725c9fa` at audit start; unchanged at audit end — read-only audit, no code changes, no commits).
Task: F2 checks (a) `mvn verify` green, (b) exhaustive catch-block audit, (c) module-boundary matrix, (d) conventional-commit subjects.
Concurrent work noted: F3 agent ran compose/docker work during the build (Testcontainers containers observed starting 09:26–09:31) — no container interference occurred; nothing was killed by this audit.

Environment: OpenJDK 25.0.4.1 (Ubuntu), Maven 3.9.12, runtime `/usr/lib/jvm/java-25-openjdk-amd64` (JDK 25 toolchain as required).

---

## Check (a) — `mvn verify` from repo root

Command (backgrounded with nohup, polled to completion):

```
mvn verify > /tmp/opencode/f2-verify.log 2>&1; echo $? > /tmp/opencode/f2-verify.exit
```

**Exit code: 0 — BUILD SUCCESS.** No failing module, no failing test.

Reactor summary (verbatim, ANSI stripped):

```
[INFO] Reactor Summary for adaptive-agent-runtime 0.1.0-SNAPSHOT:
[INFO]
[INFO] adaptive-agent-runtime ............................. SUCCESS [  0.003 s]
[INFO] spike .............................................. SUCCESS [ 11.139 s]
[INFO] core ............................................... SUCCESS [ 21.656 s]
[INFO] workload ........................................... SUCCESS [  1.964 s]
[INFO] topologies ......................................... SUCCESS [01:24 min]
[INFO] ingress ............................................ SUCCESS [01:34 min]
[INFO] bench .............................................. SUCCESS [ 18.266 s]
[INFO] app ................................................ SUCCESS [01:16 min]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

Aggregate test totals (summed over all surefire reports in the log): **272 tests, 0 failures, 0 errors, 2 skipped.**

The 2 skips are deliberate environment guards, not defects:

```
[WARNING] Tests run: 1, Failures: 0, Errors: 0, Skipped: 1 -- in io.adaptiveagents.spike.OllamaIntegrationTest
[WARNING] Tests run: 1, Failures: 0, Errors: 0, Skipped: 1 -- in io.adaptiveagents.bench.RealBenchmarkTest
```

(`RealBenchmarkTest` skip is consistent with BENCHMARK.md's honest NOT RUN status for the real-LLM profile; `OllamaIntegrationTest` requires a local Ollama.)

Wall time ≈ 5 min (09:26:34 start → 09:31:35 "Finished at") — well inside the 15–30 min estimate; no per-module split was needed. Log retained at `/tmp/opencode/f2-verify.log`.

**Verdict (a): PASS — 0 findings.**

---

## Check (b) — exhaustive `catch` audit (SLF4J-only rule, empty catches, swallowed InterruptedException)

Command:

```
grep -rn "catch" --include=*.java . | grep -v "/target/"
```

**103 hits total** (raw list mirrored in `/tmp/opencode/f2-catch-context.txt` with 4 lines of context per hit). 3 hits are prose (javadoc/comment mentions of the word "catch"), 100 are real catch clauses. Every one reviewed; verdict per site below.

Supporting scans:

```
grep -rn "System\.out\|System\.err" --include=*.java . | grep -v "/target/"
→ 5 hits (1 main source, 4 spike test) — all flagged F4/F5
```

### Full verdict table (file:line → verdict)

Rules applied: empty body → finding; `InterruptedException` caught without `Thread.currentThread().interrupt()` and without propagation → finding; `System.out`/`System.err` → finding; WARN/ERROR (or equivalent handling: rethrow, span.error+rethrow, documented fallback-return) → ok. `ok/n` = ok with observation noted after the table.

| file:line | verdict |
|---|---|
| app/.../app/fixture/FixedTextChatModel.java:30 | ok — IE, `Thread.currentThread().interrupt()` restored (l.31) |
| app/.../app/resume/StartupRescan.java:78 | ok — log.warn, documented retry-on-restart |
| app/.../app/resume/StartupRescan.java:83 | ok — log.error + must-not-abort-startup comment |
| app/src/test/.../ResumeAfterKillE2eTest.java:215 | ok — rethrow wrapped w/ SQL context |
| app/src/test/.../ResumeAfterKillE2eTest.java:226 | ok — rethrow wrapped |
| app/src/test/.../ResumeAfterKillE2eTest.java:255 | ok/n — returns 0; test poll helper |
| bench/.../bench/config/BenchProfileConfig.java:59 | ok — rethrow wrapped |
| bench/.../bench/judge/LlmJudge.java:87 | ok — log.warn + deterministic fallback |
| bench/.../bench/runner/BenchmarkRunner.java:181 | ok — IE: interrupt() + rethrow (l.182–183) |
| core/.../core/classify/LlmClassifier.java:46 | ok — degradation fallback; exception message recorded in rationale |
| core/.../core/progress/ProgressService.java:55 | ok — rethrow wrapped |
| core/.../core/store/TaskStore.java:39 | (javadoc prose, not code) |
| core/.../core/store/TaskStore.java:62 | ok — documented defensive DuplicateKeyException fallback → 200-with-existing |
| core/.../core/store/TaskStore.java:98 | ok — rethrow wrapped |
| core/.../core/store/TaskStore.java:135 | ok — rethrow wrapped |
| core/src/test/.../TaskStoreTest.java:182 | **FINDING F1** — `catch (InterruptedException e) {}` empty, no interrupt restore |
| ingress/.../kafka/KafkaTaskListener.java:75 | ok — poison (DLT path) |
| ingress/.../kafka/KafkaTaskListener.java:81 | ok — log.error + error reply |
| ingress/.../kafka/KafkaTaskListener.java:97 | ok — log.error + minimal-ack fallback |
| ingress/.../kafka/KafkaTaskListener.java:132 | ok/n — literal INVALID-JSON fallback (bug guard; sibling at :97 logs) |
| ingress/.../kafka/ProgressTopicPublisher.java:69 | ok — log.warn, next-tick retry |
| ingress/.../rest/TaskController.java:109 | ok — rethrown as TaskValidationException |
| ingress/.../rest/TaskEventStreamer.java:102 | ok — log.warn + completeWithError, guarded by CAS |
| ingress/.../rest/TaskIngressService.java:73 | ok — log.error, row-stays-ROUTED documented |
| ingress/src/test/.../CrossIngressContractE2eTest.java:392 | ok — rethrow wrapped |
| ingress/src/test/.../CrossIngressContractE2eTest.java:456 | ok/n — returns null; test poll helper |
| ingress/src/test/.../kafka/KafkaIngressIntegrationTest.java:138 | ok — IE: interrupt() restored (l.139) |
| ingress/src/test/.../kafka/KafkaIngressIntegrationTest.java:383 | ok/n — returns null; test poll helper |
| ingress/src/test/.../rest/RestIngressIntegrationTest.java:332 | ok/n — returns null; test poll helper |
| spike/src/test/.../PostgresSaverIntegrationTest.java:62 | **FINDING F1** — `catch (InterruptedException e) {}` empty, no interrupt restore |
| spike/src/test/.../PostgresSaverIntegrationTest.java:66 | **FINDING F1** — `catch (InterruptedException e) {}` empty, no interrupt restore |
| spike/src/test/.../PostgresSaverIntegrationTest.java:136 | **FINDING F2** — `catch (Exception e) { }` empty body |
| topologies/.../budget/PriceTable.java:43 | ok — rethrow wrapped |
| topologies/.../budget/PriceTable.java:69 | ok — rethrow wrapped |
| topologies/.../guard/GuardException.java:7 | (javadoc prose, not code) |
| topologies/.../registry/ChainGraphFactory.java:100 | ok — rethrow wrapped |
| topologies/.../registry/ChainGraphFactory.java:110 | ok — foldGuard (guard → terminal mapping) |
| topologies/.../registry/ChainGraphFactory.java:119 | ok — log.warn + fold degradation |
| topologies/.../registry/ChainGraphFactory.java:134 | ok — foldGuard |
| topologies/.../registry/ChainGraphFactory.java:143 | ok — log.warn + fold |
| topologies/.../registry/ChainGraphFactory.java:158 | ok — foldGuard |
| topologies/.../registry/ChainGraphFactory.java:171 | ok — log.warn + fold |
| topologies/.../registry/ChainGraphFactory.java:186 | ok — foldGuard |
| topologies/.../registry/ChainGraphFactory.java:193 | ok — log.warn + fold |
| topologies/.../registry/ChainGraphFactory.java:213 | ok — span.error + rethrow |
| topologies/.../registry/FanOutGraphFactory.java:47 | (javadoc prose, not code) |
| topologies/.../registry/FanOutGraphFactory.java:119 | ok — rethrow wrapped |
| topologies/.../registry/FanOutGraphFactory.java:148 | ok — failureGuard |
| topologies/.../registry/FanOutGraphFactory.java:161 | ok — log.warn + failure fold |
| topologies/.../registry/FanOutGraphFactory.java:180 | ok — span.error + rethrow |
| topologies/.../registry/FanOutGraphFactory.java:197 | ok — GUARD_REJECTED fold (terminal mapping) |
| topologies/.../registry/SoloGraphFactory.java:73 | ok — rethrow wrapped |
| topologies/.../registry/SoloGraphFactory.java:87 | ok — log.warn + DEGRADED fold |
| topologies/.../registry/SoloGraphFactory.java:110 | ok — error captured, transcript entry, log.warn |
| topologies/.../registry/SupervisorGraphFactory.java:124 | ok — rethrow wrapped |
| topologies/.../registry/SupervisorGraphFactory.java:169 | ok — foldGuard |
| topologies/.../registry/SupervisorGraphFactory.java:179 | ok — log.warn + fold |
| topologies/.../registry/SupervisorGraphFactory.java:199 | ok — span.error + rethrow |
| topologies/.../registry/SupervisorGraphFactory.java:216 | ok — foldGuard |
| topologies/.../registry/SupervisorGraphFactory.java:225 | ok — log.warn + fold |
| topologies/.../registry/SupervisorGraphFactory.java:237 | ok — foldGuard |
| topologies/.../registry/SupervisorGraphFactory.java:252 | ok — log.warn + fold |
| topologies/.../registry/SupervisorGraphFactory.java:264 | ok — foldGuard |
| topologies/.../routing/RoutingService.java:96 | ok/n — rethrow, but original cause `e` dropped (no `, e`) |
| topologies/.../runner/ExecutionRunner.java:169 | ok — span.error + rethrow |
| topologies/.../runner/ExecutionRunner.java:209 | ok — span.error + rethrow |
| topologies/.../runner/ExecutionRunner.java:247 | ok — span.error + rethrow |
| topologies/.../runner/ExecutionRunner.java:286 | ok — span.error + rethrow |
| topologies/.../runner/ExecutionRunner.java:324 | ok — span.error + rethrow |
| topologies/.../runner/ExecutionRunner.java:365 | ok — span.error + rethrow |
| topologies/.../runner/ExecutionRunner.java:435 | ok/n — SQLException → false: documented "missing saver table = restart path"; swallows other SQL errors by design |
| topologies/.../runner/ExecutionRunner.java:471 | ok — CancellationException → cancelledOutcome |
| topologies/.../runner/ExecutionRunner.java:473 | ok — IE: interrupt() + rethrow (l.474–475) |
| topologies/.../runner/ExecutionRunner.java:476 | ok — log.warn + cause unwrap + FAILED terminal |
| topologies/.../runner/ExecutionRunner.java:507 | ok — log.info, benign finalized-elsewhere race |
| topologies/.../runner/ExecutionRunner.java:566 | ok — nodeSpan.error + end + rethrow |
| topologies/.../runner/ExecutionRunner.java:589 | ok — rethrow wrapped |
| topologies/.../runner/ExecutionRunner.java:652 | ok/n — best-effort JSON normalize, nested fallback return (fallback IS the handling) |
| topologies/.../runner/ExecutionRunner.java:655 | ok/n — inner fallback of the above |
| topologies/.../runner/ExecutionRunner.java:678 | ok/n — partial embedded as plain string, nothing dropped |
| topologies/.../sweep/RunningTaskSweep.java:74 | ok — DEBUG for expected already-finalized race (not empty; deliberate) |
| topologies/.../sweep/RunningTaskSweep.java:79 | ok — log.warn + never-propagate comment (@Scheduled fixed-delay protection) |
| topologies/src/test/.../chain/ChainTopologyIntegrationTest.java:153 | ok — rethrow wrapped |
| topologies/src/test/.../fanout/FanOutTopologyIntegrationTest.java:164 | ok — rethrow wrapped |
| topologies/src/test/.../runner/ExecutionRunnerIntegrationTest.java:396 | **FINDING F3** — `catch (Exception ignored) {}` comment-only body ("saver tables not created yet") |
| topologies/src/test/.../runner/ExecutionRunnerIntegrationTest.java:408 | ok — returns "unreadable: msg" (info preserved) |
| topologies/src/test/.../runner/ExecutionRunnerIntegrationTest.java:418 | ok — returns "unreadable: msg" |
| topologies/src/test/.../solo/SoloTopologyIntegrationTest.java:168 | ok — rethrow wrapped |
| topologies/src/test/.../solo/SoloTopologyIntegrationTest.java:178 | ok — rethrow wrapped |
| topologies/src/test/.../supervisor/SupervisorTopologyIntegrationTest.java:197 | ok — rethrow wrapped |
| topologies/src/test/.../testutil/ControlledChatModel.java:64 | ok — IE: interrupt() restored (l.65) |
| topologies/src/test/.../testutil/ControlledChatModel.java:72 | ok — IE: interrupt() restored (l.73) |
| workload/.../tools/CiteTool.java:52 | ok — span.error + rethrow |
| workload/.../tools/FixtureFetchAdapter.java:73 | ok — span.error + rethrow |
| workload/.../tools/FixtureFetchAdapter.java:103 | ok — log.warn + empty-page fallback |
| workload/.../tools/FixtureFetchAdapter.java:125 | ok — log.warn |
| workload/.../tools/FixtureFetchAdapter.java:129 | ok — log.debug, construction-time enumeration fallback |
| workload/.../tools/FixtureSearchAdapter.java:80 | ok — span.error + rethrow |
| workload/.../tools/FixtureSearchAdapter.java:111 | ok — log.warn + empty-list fallback |
| workload/.../tools/FixtureSearchAdapter.java:133 | ok — log.warn |
| workload/.../tools/FixtureSearchAdapter.java:137 | ok — log.debug, construction-time enumeration fallback |
| workload/.../tools/ResearchTools.java:74 | ok — span.error + rethrow |
| workload/.../tools/TavilyAdapter.java:67 | ok — span.error + rethrow |

### System.out / System.err scan (SLF4J-only rule) — all 5 hits:

| file:line | verdict |
|---|---|
| app/src/main/java/io/adaptiveagents/app/HarnessProbeRunner.java:22 | **FINDING F4** — `System.out.println("HARNESS_PROBE_READY")` in main sources; appears to be a deliberate stdout readiness protocol under `@Profile("harness")` (followed by `Thread.currentThread().join()`), but it is still a System.out in main code |
| spike/src/test/.../TestLG4jParallel.java:17,21,25,38 | **FINDING F5** — 4 × System.out.println in spike-module scratch test |

### Observations (ok/n above — style notes, not findings under the stated rules)

- RoutingService.java:96 — rethrown `IllegalTaskStateException` drops the causal exception (no `, e`); cause only implied by message.
- ExecutionRunner.java:435 — `SQLException → return false` is documented for the missing-saver-table restart path but also masks unrelated SQL failures.
- ExecutionRunner.java:652/655/678 — silent best-effort serialization fallbacks (fallback return is the handling; nothing logged).
- KafkaTaskListener.java:132 — literal JSON fallback for a failed error-reply serialization (sibling path at :97 does log).
- Test poll helpers returning `null`/`0` (ResumeAfterKillE2eTest:255, CrossIngressContractE2eTest:456, KafkaIngressIntegrationTest:383, RestIngressIntegrationTest:332) — acceptable test-scope pattern.

**Summary (b): 100 real catch clauses reviewed; 94 ok, 6 sites are findings (F1 ×3, F2, F3) — ALL in test/spike code, ZERO in main production sources. Every InterruptedException in main sources correctly restores the interrupt flag (FixedTextChatModel:31, ExecutionRunner:474, BenchmarkRunner:182). No empty catch and no System.out exists in any main-source path except the harness probe marker (F4).**

---

## Check (c) — module boundaries

Command (per module, per source set):

```
grep -rhoE "^import (static )?io\.adaptiveagents\.[a-z]+" <module>/src/{main,test}/java --include=*.java | sort | uniq -c
```

### Import-based dependency matrix (cross-module `io.adaptiveagents.X` imports found in module Y)

| module ↓ imports → | core | topologies | ingress | workload | bench | app | spike |
|---|---|---|---|---|---|---|---|
| core      | —    | ·  | · | ·  | · | · | · |
| topologies| 26m/32t | — | · | 16m/10t | · | · | · |
| ingress   | 21m/2t | 2m | — | 8t | · | · | · |
| workload  | · | · | · | — | · | · | · |
| bench     | 4m/3t | 2m | · | 4t | — | · | · |
| app       | 6m/19t | 1m/9t | · | 6t | · | — | · |
| spike     | · | · | · | · | · | · | — |

(mN/tN = N imports in main/test; · = none. Nothing imports bench, app, or spike.)

### Maven-declared cross-module dependencies (module poms) — consistent with the matrix

```
topologies → core, workload
ingress    → core, topologies
bench      → topologies, workload, core
app        → core, topologies, ingress, workload
core, workload, spike → (no internal deps)
```

Note: `app` declares `ingress` + `workload` deps but its main sources import neither (runtime component-scan wiring / test-only usage) — declared-but-unused-in-main, harmless aggregator pattern, not a cycle, not flagged as a finding.

### Cycle analysis

Edges (main scope): topologies→{core,workload}, ingress→{core,topologies}, bench→{core,topologies}, app→{core,topologies}. **No module imports app/ingress/bench/spike, so no A→B & B→A pair exists. Graph is a DAG: acyclic — 0 cycles.**

### Duplicated class basenames across modules

```
find . -name "*.java" -not -path "*/target/*" | basename | sort | uniq -d
→ Marker, MarkerTest
```

`Marker.java` (scaffold placeholder: `public static final String NAME = "<module>";`) and `MarkerTest.java` exist in **5 modules each**:

```
core/src/main/java/io/adaptiveagents/core/Marker.java
topologies/src/main/java/io/adaptiveagents/topologies/Marker.java
workload/src/main/java/io/adaptiveagents/workload/Marker.java
bench/src/main/java/io/adaptiveagents/bench/Marker.java
ingress/src/main/java/io/adaptiveagents/ingress/Marker.java
(+ MarkerTest.java in each module's src/test)
```

**FINDING F6** — duplicated `Marker`/`MarkerTest` basenames across 5 modules: multi-module-skeleton scaffold residue (commit e107dbd); not imported cross-module, so harmless, but per the duplicate-basename check it is flagged.

**Summary (c): 0 cyclic dependencies; dependency direction strictly downward (app/bench/ingress → topologies → core/workload); 1 duplicate-basename finding (F6).**

---

## Check (d) — conventional commits

Command + result:

```
git log --oneline | wc -l                                        → 32
git log --oneline | sed -E 's/^[a-f0-9]+ //' \
  | grep -Ec '^(feat|fix|docs|test|chore|refactor|ops|obs)\([a-z]+\): '   → 32
git log --oneline | sed -E 's/^[a-f0-9]+ //' \
  | grep -Ev '^(feat|fix|docs|test|chore|refactor|ops|obs)\([a-z]+\): '   → (no output, exit 1)
```

**32/32 subjects match the required pattern; 0 violators.** Even the repo's first commit (`9fa4450 chore(spike): wave-0 integration spike ...`) is conventional, so no "legit initial commit" exception was needed. Types used: feat(21), docs(3), test(2), chore(2), fix(1), ops(1), obs(1), bench→feat(bench)(1). Scope tokens all lowercase ASCII as required.

**Verdict (d): PASS — 0 findings.**

---

## Commands + exit codes

| Command | Result |
|---|---|
| `mvn verify` (repo root, JDK 25) | **exit 0** — BUILD SUCCESS, 8/8 modules, 272 tests / 0 failures / 0 errors / 2 deliberate skips |
| `grep -rn "catch" --include=*.java` (excl. target) | 103 hits (100 code + 3 prose) — all reviewed in table above |
| `grep -rn "System\.out\|System\.err"` (excl. target) | 5 hits — F4 (main, harness probe marker), F5 (spike test ×4) |
| cross-module import grep per module | matrix above; 0 cycles |
| `find ... uniq -d` basenames | Marker + MarkerTest duplicated ×5 modules (F6) |
| `git log --oneline` regex filter | 32/32 conformant, 0 violators |
| `git status --porcelain` after audit | no tracked modifications (read-only held; only this untracked, gitignored evidence file added) |
| `git log --oneline -1` after audit | still `725c9fa` — no commits made |

MUST-NOT compliance: no code changes; no commits / no `git add` (evidence dir is gitignored per `.omo/evidence/` in `.gitignore`); no containers killed (F3's compose/Testcontainers traffic left untouched); t28 worktree path not touched.

---

## VERDICT

# F2: APPROVE

### Findings (numbered)

1. **F1 — MEDIUM (b)** — Swallowed `InterruptedException`, empty body, interrupt flag not restored, ×3 sites, all test code: `core/src/test/java/io/adaptiveagents/core/store/TaskStoreTest.java:182`, `spike/src/test/java/io/adaptiveagents/spike/PostgresSaverIntegrationTest.java:62` and `:66`. (All *main*-source IE catches correctly restore the flag — the defect class is absent from production code.)
2. **F2 — LOW (b)** — Empty catch block: `spike/src/test/java/io/adaptiveagents/spike/PostgresSaverIntegrationTest.java:136` — `catch (Exception e) { }` with no comment, no log, no rethrow.
3. **F3 — LOW (b)** — Comment-only empty catch: `topologies/src/test/java/io/adaptiveagents/topologies/runner/ExecutionRunnerIntegrationTest.java:396` — `catch (Exception ignored) {} // saver tables not created yet`; deliberate poll guard, still an empty catch per the rule.
4. **F4 — LOW (b)** — `System.out.println("HARNESS_PROBE_READY")` in main sources: `app/src/main/java/io/adaptiveagents/app/HarnessProbeRunner.java:22` — SLF4J-only rule violation; mitigating context: stdout is plausibly the readiness *protocol* for the `harness` profile probe (followed by `join()`), not diagnostic logging.
5. **F5 — LOW (b)** — 4 × `System.out.println` in spike-module scratch test `spike/src/test/java/io/adaptiveagents/spike/TestLG4jParallel.java:17,21,25,38` (spike is an explicit scratchpad module; rule still technically violated).
6. **F6 — LOW (c)** — Duplicated class basenames `Marker` / `MarkerTest` across 5 modules (core, topologies, workload, bench, ingress) — skeleton scaffold residue from `chore(repo)` e107dbd; not cross-imported, harmless.

### Counts by category

| Category | Findings |
|---|---|
| (a) build | 0 |
| (b) catch/System.out audit | 5 (F1–F5) |
| (c) module boundaries | 1 duplicate-basename (F6); 0 cycles |
| (d) conventional commits | 0 |
| **Total** | **6** (1 MEDIUM, 5 LOW; 0 HIGH, 0 CRITICAL) |

Approve rationale: build fully green on JDK 25 (272/272 effective tests), production catch discipline is clean (all findings in test/spike code except one deliberate-looking harness stdout marker), module graph is an acyclic downward DAG, and 100% of commit subjects are conventional. All findings are low-severity hygiene items suitable for a follow-up cleanup task; none blocks.
