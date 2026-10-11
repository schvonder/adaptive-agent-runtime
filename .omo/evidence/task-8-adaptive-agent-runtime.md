# T8 — Rule-based complexity scorer (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit a03fab1); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T8: `core` `RuleScorer.score(envelope) -> ComplexityVerdict(score, band CLEAR_SIMPLE|AMBIGUOUS|CLEAR_COMPLEX, candidateTopology, rationale)`; features: prompt length/token estimate, breadthHint, deadlineMs tightness; table-driven unit tests; pure — no LLM, no I/O, no clock read.

Commit `a03fab1 feat(core): deterministic rule-based complexity scorer` (2026-10-02):

```
 .../java/io/adaptiveagents/core/classify/Band.java |   7 ++
 .../core/classify/ComplexityVerdict.java           |  10 ++
 .../adaptiveagents/core/classify/RuleScorer.java   |  94 ++++++++++
 .../core/classify/RuleScorerTest.java              | 139 ++++++++++++++++++
 4 files changed, 250 insertions(+)
```

## Acceptance re-executed today (post-hoc)

Targeted signature run (transcript `/tmp/opencode/aar/09-rulescorer.log`):

```
$ mvn -pl core test -Dtest=RuleScorerTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.619 s -- in io.adaptiveagents.core.classify.RuleScorerTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

RuleScorerTest methods include (surefire report): `determinismTest`, `budgetIrrelevantToScore`, plus the table-driven band/boundary cases (14 total).

Full core suite: `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS` (`/tmp/opencode/aar/03-core-test.log`).

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| `mvn -pl core test` — 12+ table-driven cases green | `RuleScorerTest` 14/14 green (targeted run above); full suite 41/0 |
| Same input → identical verdict across 10 invocations | `determinismTest` green |
| QA happy: short prompt + breadth 1 → CLEAR_SIMPLE/SOLO | Covered by the table-driven band cases among the 14 (band→candidateTopology mapping asserted) |
| QA failure (negative): breadthHint 5 → CLEAR_COMPLEX/SUPERVISOR not SOLO | Negative band assertions within the same table-driven suite (e.g. `budgetIrrelevantToScore` pins inputs that must NOT shift the verdict) |
| Purity (no LLM/I/O/clock) | Unit test runs in 0.619 s with no containers/WireMock in classpath scope of this test |

## Not re-runnable post-hoc

Nothing — pure unit acceptance, re-run green today.
