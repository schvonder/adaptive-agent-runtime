# F1-rerun — Plan compliance audit (re-audit after remediation) — Evidence

Repo: `/home/kirill/IdeaProjects/adaptive-agent-runtime` (branch `main`, HEAD `988d528`
(988d528479ae53fa6c7acf4850c8b838863ff472), 33 commits, working tree clean at audit start).
Plan file audited: `/home/kirill/.omo/plans/adaptive-agent-runtime.md` (351 lines, OUTSIDE the
repo — read there, NOT modified).
Task: re-run of the F1 plan-compliance audit (original: `task-F1-adaptive-agent-runtime.md`,
verdict REJECT with 3 findings) after remediation of findings #1 (MAJOR: evidence 1–18 missing)
and #2 (LOW: todos 17–31 unchecked). Finding #3 (LOW: extra commit 157ae1b) was disclosed as
not remediated by design. This re-audit re-executes the original four checks independently at
current HEAD and does NOT trust remediation claims. READ-ONLY: no code, plan, or other-task
evidence modified; nothing committed; no docker compose; no full reactor build (targeted tests
+ single `core` module suite only).

Audit method: raw commands executed 2026-10-11 against the repo + plan file; every check below
reproduces its command and verbatim output (ANSI trimmed). Remediation claim under test:
18 backfilled evidence files committed in `988d528` with post-hoc headers, real re-executed
outputs at (then-)HEAD 725c9fa, coverage tables, and explicit not-re-runnable declarations.

---

## Check (a) — Todo checkbox states + field completeness

Commands:

```
$ grep -cE '^- \[ \] [0-9]+\. ' /home/kirill/.omo/plans/adaptive-agent-runtime.md
0
$ grep -cE '^- \[x\] [0-9]+\. ' /home/kirill/.omo/plans/adaptive-agent-runtime.md
31
$ grep -nE '^- \[ \]' /home/kirill/.omo/plans/adaptive-agent-runtime.md
332:- [ ] F1. Plan compliance audit
334:- [ ] F2. Code quality review
336:- [ ] F3. Real manual QA
338:- [ ] F4. Scope fidelity
```

Checkbox states (from `grep -nE '^- \[[ x]\] [0-9]+\. '` — 31 header lines listed):

| Todos | State |
|---|---|
| 1 … 31 (all numbered) | `[x]` checked (31) |
| 17–31 (previously unchecked 15) | now all `[x]` — original finding #2 remediated |
| F1–F4 (final verification wave, not numbered todos) | `[ ]` unchecked (4) — expected: F-wave lines are not numbered todos and F1–F4 approval bookkeeping is outside this audit's scope |

All 31 todo numbers 1..31 exist exactly once, no gaps (31 numbered header lines, sequential).

Field completeness — every todo chunk checked for the five required fields:

```
$ for f in 'What to do' 'References' 'Acceptance criteria' 'QA scenarios' 'Commit: Y |'; do grep -c "$f" <plan>; done
31
32   (References: 31 todo fields + 1 header mention "Direct dependencies only")
31
31
31
```

todos with missing fields: NONE — all 31 have all 5 fields.

**Result (a): PASS — 31/31 checked, 0 unchecked numbered todos, fields complete.
Original finding #2 RESOLVED.** (The plan's literal F1(a) text "`grep -cE '^- \[ \] [0-9]+\. '` = 31"
cannot coexist with completed work; the evident intent — all 31 resolved/checked — now holds,
matching the original audit's interpretation.)

## Check (b) — Evidence files N=1..31 on disk

Command per N: `[ -f .omo/evidence/task-N-adaptive-agent-runtime.md ] && stat -c%s <file>`:

| N | Exists | Bytes | | N | Exists | Bytes | | N | Exists | Bytes |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | yes | 5,044 | | 12 | yes | 2,635 | | 23 | yes | 7,541 |
| 2 | yes | 4,542 | | 13 | yes | 2,525 | | 24 | yes | 5,574 |
| 3 | yes | 3,498 | | 14 | yes | 2,769 | | 25 | yes | 7,536 |
| 4 | yes | 3,221 | | 15 | yes | 2,364 | | 26 | yes | 7,287 |
| 5 | yes | 3,371 | | 16 | yes | 3,289 | | 27 | yes | 2,381 |
| 6 | yes | 2,930 | | 17 | yes | 3,046 | | 28 | yes | 5,707 |
| 7 | yes | 2,457 | | 18 | yes | 3,784 | | 29 | yes | 10,209 |
| 8 | yes | 2,499 | | 19 | yes | 8,099 | | 30 | yes | 9,305 |
| 9 | yes | 2,930 | | 20 | yes | 9,516 | | 31 | yes | 9,325 |
| 10 | yes | 3,188 | | 21 | yes | 10,275 | | | | |
| 11 | yes | 4,213 | | 22 | yes | 8,648 | | | | |

Summary: **31/31 exist on disk, all non-empty (2,364–10,275 bytes)**. Files 1–18 are the
backfilled set (created by commit `988d528`); 19–22 force-added in the same commit (previously
on disk but untracked — `.gitignore` carries `.omo/`); 23–31 tracked since their waves
(T31 force-add precedent). `git ls-files .omo/evidence/` confirms all 31 task files tracked.
`.omo/evidence/` additionally contains F1–F4 audit files (F2/F3/F4 ran after the original F1;
untracked, consistent with `.gitignore`) — outside checks (a)–(d) scope.

**Result (b): PASS for N=1..31. Original finding #1 (missing 18 files) RESOLVED on disk.**

## Check (c) — Commit count + per-todo subject match

Command:

```
$ git log --oneline | wc -l
33
```

All 33 subjects matched against the plan's 31 `Commit: Y | <message>` lines
(exact-subject comparison, programmatic `grep -F` per plan line): **31/31 exact matches**
(same commits as the original audit — 9fa4450, e107dbd, d1bef12, 74b94e5, 6019b1f, cc410bc,
db41076, a03fab1, 52d4bbf, e3df8c8, cf86ab9, c9078d8, 12cd267, 283952f, 18949de, 4e059d3,
7a840d9, c7d1f1d, 0eb7179, 0b09120, b3b635f, 42cc8cb, 23a98b5, 48f66ac, 905b877, 32d4a06,
f7450b2, ce50031, dbd480d, 477a1c2, 725c9fa — in dependency-consistent order).

Non-todo commits (exhaustive list, `git log --format='%h %s' | grep -vFf plan-commits`):

| Commit | Subject | Status |
|---|---|---|
| `157ae1b` | `fix(core): conditional LlmClassifier activation` | original finding #3 — NOT remediated by design (disclosed deviation) |
| `988d528` | `docs(evidence): backfill acceptance evidence for todos 1-18 (post-hoc)` | new since original audit — the remediation commit itself (evidence-only, 22 files, +1,458 lines, no code) |

Merge commits: 0 (`git log --merges | wc -l` = 0).

`git show --stat 988d528`: touched exactly `.omo/evidence/task-{1..22}-*.md` — the 18 new
backfilled files plus force-add of previously untracked 19–22. Commit message body discloses
the post-hoc nature, the re-execution at HEAD 725c9fa, the force-add of 19–22, and the two
not-re-runnable declarations (T1 live Ollama, T2 skeleton-only boot).

**Result (c): PASS — 33 = 31 todo commits (31/31 exact subjects) + 2 disclosed non-todo
commits (157ae1b, 988d528); no merges; no undisclosed extras.**

## Check (d) — Acceptance re-runnability from evidence (all 18 backfilled files read IN FULL)

Method: each of task-1 … task-18 read in full; verify (i) post-hoc header naming the original
commit + re-execution date 2026-10-11 at HEAD 725c9fa, (ii) real command+output blocks with
exit codes / test counts, (iii) per-criterion coverage table mapping every plan acceptance
criterion to recorded evidence, (iv) explicit `Not re-runnable post-hoc` section where
applicable.

| N | Header names original commit | Command+output w/ exit code | Coverage table | Not-re-runnable declared | Notes |
|---|---|---|---|---|---|
| 1 | 9fa4450 ✓ | `mvn -pl spike test` → 5 run/1 skip, exit=0 | 5 criteria → outputs | YES (live Ollama) | skip itself is the acceptance-mandated env-gated behavior; SPIKE.md verdicts quoted |
| 2 | e107dbd ✓ | `mvn clean install -DskipTests` reactor 8/8 SUCCESS + `dependency:tree` LG4j 1.9.1, exit=0 | 4 criteria | YES (skeleton-only boot flag) | reasonable evolved-equivalent argument (ApplicationTest) |
| 3 | d1bef12 ✓ | app `clean verify` failsafe 1/1 + totals 3/0, exit=0 | 4 criteria | nothing (fully re-run) | real SIGKILL/relaunch IT |
| 4 | 74b94e5 ✓ | core 41/0/0 exit=0 + Flyway migrate log lines | 5 criteria | nothing | 41 matches independent re-run (below) |
| 5 | 6019b1f ✓ | core 41/0/0, TaskEnvelopeTest 9/9, `ls` doc check | 5 criteria | nothing | 9 negative+positive schema cases named |
| 6 | cc460bc ✓ | `-Dtest=TaskStoreTest` 6/6 exit=0 + core 41 | 6 criteria | nothing | reproduced by this audit (below) |
| 7 | db41076 ✓ | `-Dtest=ProgressServiceTest` 4/4 exit=0 + core 41 | 5 criteria | nothing | reproduced by this audit (below) |
| 8 | a03fab1 ✓ | `-Dtest=RuleScorerTest` 14/14 exit=0 | 5 criteria | nothing | ≥12 required by plan; 14 delivered |
| 9 | 52d4bbf + 157ae1b ✓ | core suite w/ LlmClassifierWireMockTest 4/4, 41/0/0 exit=0 | 5 criteria | nothing | discloses the fix commit inline; minor hash typo (nit, below) |
| 10 | e3df8c8 ✓ | topologies 46/0/0 w/ per-class 4+3, exit=0 | 5 criteria | nothing | TopologyRegistryTest reproduced (below) |
| 11 | cf86ab9 ✓ | workload 16/0/0 all 6 classes listed, exit=0 | 4 criteria | nothing | 16 = exact per-class sum |
| 12 | c9078d8 ✓ | topologies 46/0/0 w/ Solo 3/3, exit=0 | 5 criteria | nothing | bounded-loop test extra |
| 13 | 12cd267 ✓ | topologies 46/0/0 w/ Chain 3/3, exit=0 | 4 criteria | nothing | |
| 14 | 283952f ✓ | topologies 46/0/0 w/ FanOut 3/3, exit=0 | 5 criteria | nothing | virtual-thread claim structural (impl-level), tests cover behavior |
| 15 | 18949de ✓ | topologies 46/0/0 w/ Supervisor 4/4, exit=0 | 5 criteria | nothing | |
| 16 | 4e059d3 ✓ | `-Dtest=ExecutionRunnerIntegrationTest` 8/8 exit=0 | 5 criteria | nothing | checkpoint poll-assert test named |
| 17 | 7a840d9 ✓ | targeted `it.test=ResumeAfterKillE2eTest` 2/2 exit=0 (real spawn/SIGKILL/relaunch) | 4 criteria | nothing | full kill/relaunch re-executed |
| 18 | c7d1f1d ✓ | TaskStoreTest 6/6 + runner 8/8 + ingress 18/0 w/ Kafka 4/4, exit=0 ×3 | 5 criteria | nothing | redelivery test named |

Every acceptance criterion in todos 1–18 maps to a recorded command + output (test method
names, counts, exit codes); no criterion is covered by vague prose alone. The two cases that
CANNOT be literally re-run post-hoc (T1 live Ollama roundtrip — env-gated and env absent;
T2 skeleton-era `--spring.main.web-application-type=none` boot — app has since become the full
web deployable) are explicitly declared in dedicated `Not re-runnable post-hoc` sections with
the closest re-runnable equivalent recorded. This is honest post-hoc labeling, not fabrication.

Cross-consistency of recorded counts vs module totals (core=41, topologies=46, workload=16,
ingress=18, spike 5 run/1 skip, app surefire 5 + failsafe 3):
- workload 16 = 1+4+3+3+1+4 exact per-class sum ✓
- core 41 = 3+9+6+4+14+4 (wave-0–4 classes) + 1 MarkerTest ✓
- topologies 46 = wave-0–4 classes (7+3+3+3+4+8+1 = 29) + later-wave classes in the same
  module (BudgetDegradation 4, DeadlineSweep 5, DemoTasksRouting 3, PriceTable — T19/T20/T25
  evidence) = 46 ✓ — files correctly report the module TOTAL while citing their own classes
- ingress 18 = REST 5 + Kafka 4 + MCP 5 + contract 3 + Marker 1 ✓ (matches original F1 audit)
- spike 5 run/1 skip ✓; app surefire 5 (Application 1 + 4 tracing) + failsafe 3 (KillRestart 1
  + ResumeAfterKill 2) ✓
All 13 referenced transcript logs (`/tmp/opencode/aar/00…12*.log`) exist on disk, timestamped
Oct 11 10:06–10:13 (before commit 988d528 at 10:19:37), and their `Tests run:` totals match the
numbers quoted in the evidence files exactly (spike 5/1, workload 16, core 41, ingress 18,
topologies 46, app-verify failsafe 3, taskstore 6, progress 4, rulescorer 14, runner 8,
resume-e2e 2; install + deptree BUILD SUCCESS). No inconsistent test counts found.

**Result (d): PASS — 18/18 backfilled files carry post-hoc headers, real recorded
command+output blocks, and complete criterion→evidence mappings; the 2 legitimately
non-re-runnable items are explicitly declared. No fabricated-output indicators found.**

## Spot-verification — commands re-run BY THIS AUDIT (independent reproduction)

| Command (from repo root) | Recorded claim | This audit's result | Match |
|---|---|---|---|
| `mvn -pl topologies test -Dtest=TopologyRegistryTest` | 3/3, exit=0 (T10) | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS` · EXIT=0 | ✓ |
| `mvn -pl core test -Dtest=TaskStoreTest` | 6/6, exit=0 (T6, T18) | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS` · EXIT=0 | ✓ |
| `mvn -pl core test -Dtest=ProgressServiceTest` | 4/4, exit=0 (T7) | `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS` · EXIT=0 | ✓ |
| `mvn -pl core test` (full module suite) | 41/0/0/0, exit=0 (T2/T4–T9) | surefire XML aggregate: tests=41 failures=0 errors=0 skipped=0 · `BUILD SUCCESS` · EXIT=0 | ✓ |

Timings differ run-to-run (e.g. TaskStoreTest 6.714 s recorded vs 4.938 s now) — normal
variance; counts, outcomes, and exit codes identical. Environment used: JDK 25.0.4.1,
Maven 3.9.12, Docker available for Testcontainers (PG17 singleton) — same class of environment
as the backfill run.

## Commands + exit codes (audit's own)

| Command | Result |
| --- | --- |
| `grep -cE '^- \[ \] [0-9]+\. ' <plan>` | 0 |
| `grep -cE '^- \[x\] [0-9]+\. ' <plan>` | 31 |
| per-todo field scan (What to do/References/Acceptance/QA/Commit) | 31/31 complete, 0 missing |
| evidence existence probe ×31 | 31 exist, 0 missing |
| `git log --oneline \| wc -l` | 33 |
| plan-subject ↔ git-subject exact match | 31/31 |
| non-todo commit listing | 2 (157ae1b, 988d528), 0 merges |
| `git show --stat 988d528` | 22 evidence files, +1,458 lines, no code |
| evidence reads T1–T18 (18 files, full) | all have headers + commands + outputs + coverage tables |
| `/tmp/opencode/aar/*.log` existence + totals | 13/13 present, totals match quotes |
| `mvn -pl topologies test -Dtest=TopologyRegistryTest` | 3/3, exit 0 |
| `mvn -pl core test -Dtest=TaskStoreTest` | 6/6, exit 0 |
| `mvn -pl core test -Dtest=ProgressServiceTest` | 4/4, exit 0 |
| `mvn -pl core test` | 41/0/0/0, exit 0 |
| `git rev-parse HEAD` | 988d528479ae53fa6c7acf4850c8b838863ff472 |
| `git status --short` | empty (clean; only this new evidence file written by the audit) |

## Findings

1. **[RESOLVED — was MAJOR] Evidence files for todos 1–18 missing.** All 18 now exist on disk
   (2,364–3,784 bytes… plus force-added 19–22), committed in `988d528`, labeled
   `BACKFILLED POST-HOC` in headers naming the original commit and re-execution at HEAD 725c9fa
   on 2026-10-11. Every acceptance criterion maps to recorded command output; the two
   legitimately non-re-runnable items (T1 live Ollama, T2 skeleton-only boot) are explicitly
   declared. Counts cross-check against module totals; 13 backing transcripts exist with
   pre-commit timestamps; 4 commands independently re-run by this audit reproduce recorded
   results exactly. No fabrication indicators.
2. **[RESOLVED — was LOW] 15 todo checkboxes unchecked.** Plan now shows 31/31 `[x]`, 0 `[ ]`
   for numbered todos (F-wave lines remain `[ ]` as expected — they are not numbered todos).
   All five required fields still present on all 31 todos.
3. **[OPEN by design — LOW, disclosed deviation] Extra commits beyond one-per-todo.**
   `157ae1b fix(core): conditional LlmClassifier activation` (original finding #3, disclosed in
   T9's backfilled evidence) plus the new `988d528 docs(evidence): backfill …` (the remediation
   commit itself; evidence-only, no code). Both conventional-format, both disclosed in their
   messages/evidence, no merge commits. Acceptable as a documented deviation; carried forward
   as informational.
4. **[NIT — no severity] Commit-hash typo in task-9 coverage table:** `52d94bbf` in one table
   cell (line 51); the correct hash `52d4bbf` appears in the same file's header and stat block.
   Cosmetic; does not affect verifiability.

## Verdict

F1-rerun: APPROVE
1. Original finding #1 (MAJOR): RESOLVED — 18 backfilled evidence files exist, are post-hoc labeled, carry real re-executed outputs with consistent test counts, and independently reproduce under this audit's own re-runs (4/4 commands match exactly).
2. Original finding #2 (LOW): RESOLVED — plan shows 31/31 checked, 0 unchecked numbered todos; field completeness intact.
3. Original finding #3 (LOW): remains as disclosed deviation (+ the remediation commit 988d528); both non-todo commits listed explicitly, conventional format, no merges — informational, not blocking.
