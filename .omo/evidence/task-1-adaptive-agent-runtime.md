# T1 — Wave-0 integration spike (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit 9fa4450); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T1: verify the four risky joints on JDK 25 in module `spike/`: (a) `langgraph4j-spring-ai:1.9.1` against `spring-ai-starter-model-openai:2.0.1` ChatClient; (b) `langgraph4j-postgres-saver:1.9.1` checkpoint roundtrip on Postgres 17 with two nodes writing in parallel branches + its DDL mechanism; (c) Ollama OpenAI-compatible tool-call roundtrip with a pinned model; (d) WireMock-stubbed OpenAI endpoint serving deterministic tool-calls. Answers recorded in `SPIKE.md` before Wave 1.

Commit `9fa4450 chore(spike): wave-0 integration spike (LG4j 1.9.1 x Spring AI 2.0.1 x Boot 4.1.1)` (2026-10-01):

```
 .gitignore                                         |   7 +
 SPIKE.md                                           |  78 +++++++++++
 pom.xml                                            |  60 ++++++++
 spike/pom.xml                                      |  72 ++++++++++
 .../spike/OllamaIntegrationTest.java               |  34 +++++
 .../spike/PostgresSaverIntegrationTest.java        | 149 ++++++++++++++++++
 .../io/adaptiveagents/spike/TestLG4jParallel.java  |  40 +++++
 .../adaptiveagents/spike/WireMockSpringAiTest.java | 154 ++++++++++++++++++
 .../test/resources/mappings/chat-completion.json   |  43 ++++++
 9 files changed, 637 insertions(+)
```

## Acceptance re-executed today (post-hoc)

`mvn -pl spike test` at HEAD 725c9fa (full transcript `/tmp/opencode/aar/01-spike-test.log`):

```
$ mvn -pl spike test
[INFO] Running io.adaptiveagents.spike.OllamaIntegrationTest
[WARNING] Tests run: 1, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.027 s -- in io.adaptiveagents.spike.OllamaIntegrationTest
[INFO] Running io.adaptiveagents.spike.TestLG4jParallel
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.322 s -- in io.adaptiveagents.spike.TestLG4jParallel
[INFO] Running io.adaptiveagents.spike.WireMockSpringAiTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.523 s -- in io.adaptiveagents.spike.WireMockSpringAiTest
[INFO] Running io.adaptiveagents.spike.PostgresSaverIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.803 s -- in io.adaptiveagents.spike.PostgresSaverIntegrationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
exit=0
```

Per-method (surefire report `spike/target/surefire-reports/`): `WireMockSpringAiTest.testChatClientWithWireMockAndLangGraph`, `PostgresSaverIntegrationTest.parallelBranchCheckpointsAreConsistent` + `resumeFromCheckpointSkipsCompletedNodes`, `TestLG4jParallel.testParallel`, `OllamaIntegrationTest.testLocalOllamaEndpoint` (skipped).

`SPIKE.md` answers present today (verbatim headings): `## Verdicts` — "(a) **PASS** … (b) **PASS** … (c) **SKIPPED** (Ollama was DOWN during probe) … (d) **PASS**"; `## Postgres Saver DDL Mechanism` — "Runtime Creation: `PostgresSaver.builder().createTables(true)` … CREATE TABLE IF NOT EXISTS … for LG4JThread, LG4JThreadTag and LG4JCheckpoint" (→ saver tables kept saver-managed, per the plan's fallback decision table); `## Checkpoint Resume API Recipe`; `## Parallel State Branching Strategy`; `## Fallbacks Applied`; `## Exact Working Version Combo` (Boot 4.1.1 / Spring AI 2.0.1 / LG4j 1.9.1 / Testcontainers 1.21.4 / WireMock 3.12.0).

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| `mvn -pl spike test` exits 0 with WireMock profile | `mvn -pl spike test` → `Tests run: 5, Failures: 0, Errors: 0, Skipped: 1`, `BUILD SUCCESS`, exit=0 (WireMockSpringAiTest green) |
| Ollama test runs when env `OLLAMA_BASE_URL` set, skipped otherwise (`@EnabledIfEnvironmentVariable`) | `OllamaIntegrationTest` → `Tests run: 1, … Skipped: 1` with env unset today — exactly the designed skip path |
| SPIKE.md contains explicit answers (a)(b)(c)(d) + fallback decisions | `SPIKE.md` `## Verdicts` (a/b/c/d), `## Fallbacks Applied`, `## Exact Working Version Combo` quoted above |
| QA happy: WireMock tool-call fixture → parsed tool args | `WireMockSpringAiTest.testChatClientWithWireMockAndLangGraph` 1/1 green |
| QA failure: parallel branch checkpoint writes + restart-from-checkpoint, thread state consistent | `PostgresSaverIntegrationTest.parallelBranchCheckpointsAreConsistent` + `resumeFromCheckpointSkipsCompletedNodes` 2/2 green |

## Not re-runnable post-hoc

- Spike verdict (c) as a *live* roundtrip: `OllamaIntegrationTest` needs `OLLAMA_BASE_URL` pointing at a running Ollama server with the pinned model; no Ollama is available in today's environment, so only the env-gated skip is demonstrable (the skip itself is the acceptance-mandated behavior). Original wave-0 verdict for (c) was also SKIPPED (Ollama down) and was compensated by (d) WireMock tool-call determinism, per SPIKE.md.
