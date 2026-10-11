# T9 — LLM classifier leg (ambiguity band only) + fallback + test stub (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commits 52d4bbf + follow-up fix 157ae1b); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T9: `LlmClassifier` runs ONLY when band=AMBIGUOUS; Spring AI structured output into `ComplexityVerdict`, `temperature=0.0`; parse/validation failure → fall back to RuleScorer verdict with rationale persisted; test-profile `StubClassifier` bean; WireMock fixture tests. Follow-up `157ae1b fix(core): conditional LlmClassifier activation` corrected bean activation (2 files, +6/−1).

Commits:

```
=== 52d4bbf feat(core): LLM classifier leg with rule fallback (2026-10-02) ===
 core/pom.xml                                       |  10 ++
 .../core/classify/ComplexityClassifier.java        |   7 +
 .../core/classify/LlmClassifier.java               |  51 ++++++
 .../adaptiveagents/core/classify/LlmVerdict.java   |   7 +
 .../core/classify/RuleScorer.java                  |   3 +
 .../core/classify/StubClassifier.java              |  28 +++
 .../core/classify/LlmClassifierWireMockTest.java   | 183 ++++++++++++++++++
 7 files changed, 289 insertions(+)
=== 157ae1b fix(core): conditional LlmClassifier activation (2026-10-02) ===
 .../core/classify/LlmClassifier.java               |   2 +-
 .../classify/LlmClassifierWireMockTest.java        |   5 +++++
 2 files changed, 6 insertions(+), 1 deletion(-)
```

## Acceptance re-executed today (post-hoc)

Full core suite at HEAD (transcript `/tmp/opencode/aar/03-core-test.log`) — includes the WireMock class:

```
$ mvn -pl core test
[INFO] Running io.adaptiveagents.core.classify.LlmClassifierWireMockTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.31 s -- in io.adaptiveagents.core.classify.LlmClassifierWireMockTest
...
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

LlmClassifierWireMockTest methods (surefire report): `ambiguousValidSchema`, `ambiguousMalformedOutput`, `clearBandsNeverCallLlm`, `endpoint500`.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| WireMock: valid schema → verdict | `ambiguousValidSchema` green |
| WireMock: malformed LLM output → rule fallback (asserted) | `ambiguousMalformedOutput` green |
| CLEAR bands never hit the LLM endpoint (WireMock verify 0 calls) | `clearBandsNeverCallLlm` green |
| Endpoint 500 → fallback verdict + rationale records fallback reason | `endpoint500` green |
| LLM called only in AMBIGUOUS band; temperature 0 | Enforced by `clearBandsNeverCallLlm` + structured-output config in `LlmClassifier` (commit 52d94bbf/157ae1b diff) |

## Not re-runnable post-hoc

Nothing — the acceptance is WireMock-based by design (no live LLM needed); re-run green today.
