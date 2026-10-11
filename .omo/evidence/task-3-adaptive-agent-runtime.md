# T3 — Test harness: singleton Postgres + spawned-app runner (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit d1bef12); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T3: in `app/src/test` — Testcontainers singleton `postgres:17-alpine` (one container per JVM via `SingletonPostgres`), `SpawnedApp` helper launching the packaged fat-jar from `${project.build.directory}` as a child `java -jar` process with stop(pid, SIGKILL) + relaunch against the SAME container. Real process restart, no `@SpringBootTest` context-recreation substitute.

Commit `d1bef12 test(harness): singleton PG + spawned-app runner` (2026-10-01):

```
 app/pom.xml                                        |  52 ++++++++++
 .../io/adaptiveagents/app/HarnessProbeRunner.java  |  26 +++++++
 .../io/adaptiveagents/app/ApplicationTest.java     |   8 +-
 .../io/adaptiveagents/app/SingletonPostgres.java   |  24 +++++
 .../java/io/adaptiveagents/app/SpawnedApp.java     |  72 ++++++++++++++++++
 .../app/SpawnedAppKillRestartTest.java             |  88 ++++++++++++++++++
 pom.xml                                            |   1 -
 7 files changed, 269 insertions(+), 2 deletions(-)
```

Today the harness lives on as `app/src/test/java/io/adaptiveagents/app/{SingletonPostgres,SpawnedApp}.java` and underpins `SpawnedAppKillRestartTest` (failsafe IT) and `ResumeAfterKillE2eTest` (T17).

## Acceptance re-executed today (post-hoc)

`mvn -pl app clean verify -DskipTests=false` at HEAD (transcript `/tmp/opencode/aar/06-app-verify.log`) — failsafe IT built on this harness:

```
[INFO] Running io.adaptiveagents.app.SpawnedAppKillRestartTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 13.60 s -- in io.adaptiveagents.app.SpawnedAppKillRestartTest
...
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

Test method (failsafe report / source): `SpawnedAppKillRestartTest.testKillAndRestartPreservesData` — start spawned app → POST row via API → SIGKILL → relaunch against same container → row still readable via API. The same verify run also executes `ResumeAfterKillE2eTest` 2/2 (33.04 s) and surefire `ApplicationTest` + 3 tracing tests (5/5), all against the singleton PG17 container, and the spawned-app log shows `Successfully applied 2 migrations to schema "public", now at version v2` — the relaunched process reconnects to the SAME Postgres.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Harness test: start app → POST row via API → SIGKILL → relaunch → row still readable via API. Exits 0 | `SpawnedAppKillRestartTest` 1/1 green inside `mvn -pl app clean verify -DskipTests=false` → `BUILD SUCCESS`, exit=0 |
| QA happy: restart cycle preserves data | `testKillAndRestartPreservesData` (method name = the assertion) |
| QA failure: SIGKILL during in-flight write leaves DB usable (relaunch succeeds) | kill/relaunch cycle exercised by the same IT and by `ResumeAfterKillE2eTest.killMidRunThenRelaunchCompletesFromCheckpoints` 1/1 (T17 evidence, same verify transcript) |
| Harness lives in `app` (needs packaged fat-jar) | ITs run under maven-failsafe in `app` module; jar prerequisite enforced (`checkJarExists` in SpawnedAppKillRestartTest) |

## Not re-runnable post-hoc

Nothing — the harness is fully re-runnable and was re-run today (outputs above).
