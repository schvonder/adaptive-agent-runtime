# T2 — Maven multi-module skeleton + version pins (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit e107dbd); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T2: parent POM (groupId `io.adaptiveagents`, artifact `adaptive-agent-runtime`) + modules `spike, core, topologies, ingress, workload, bench, app`; pins JDK 25, Boot 4.1.1 parent, `spring-ai-bom:2.0.1`, `org.bsc.langgraph4j:langgraph4j-bom:1.9.1`, Testcontainers 1.21.4, Flyway (Boot-managed), WireMock test-scope; `git init` + `.gitignore`.

Commit `e107dbd chore(repo): multi-module skeleton, JDK 25, pinned BOMs` (2026-10-01) — creates all 7 module poms, `Application.java` + `ApplicationTest.java` in app, `Marker`/`MarkerTest` pairs in core/ingress/topologies/bench, root pom module list, `.gitignore` (per `git show --stat`, 30 files, e.g. `app/pom.xml 49 +`, `core/…/MarkerTest.java 9 +`).

## Acceptance re-executed today (post-hoc)

Full build at HEAD 725c9fa (transcript `/tmp/opencode/aar/00-install.log`):

```
$ mvn clean install -DskipTests
[INFO] BUILD SUCCESS
[INFO] Total time:  11.526 s
$ grep -A11 'Reactor Summary' /tmp/opencode/aar/00-install.log
[INFO] Reactor Summary for adaptive-agent-runtime 0.1.0-SNAPSHOT:
[INFO]
[INFO] adaptive-agent-runtime ............................. SUCCESS [  0.206 s]
[INFO] spike .............................................. SUCCESS [  2.840 s]
[INFO] core ............................................... SUCCESS [  1.600 s]
[INFO] workload ........................................... SUCCESS [  0.619 s]
[INFO] topologies ......................................... SUCCESS [  1.671 s]
[INFO] ingress ............................................ SUCCESS [  1.145 s]
[INFO] bench .............................................. SUCCESS [  0.719 s]
[INFO] app ................................................ SUCCESS [  2.295 s]
[INFO] BUILD SUCCESS
exit=0
```

Per-module test phases re-run today (see sibling evidence files for full transcripts): spike 5/0/1 skip, core 41/0, workload 16/0, topologies 46/0, ingress 18/0, app `clean verify -DskipTests=false` surefire 5/0 + failsafe 3/0 — i.e. the modern equivalent of the skeleton-time `mvn -q verify` is green across the whole reactor.

Pinned versions from root `pom.xml` today: `<java.version>25</java.version>`, `<maven.compiler.release>25</maven.compiler.release>`, parent `org.springframework.boot:spring-boot-starter-parent:4.1.1`, `spring-ai-bom:2.0.1`, `langgraph4j-bom:1.9.1`, `testcontainers-bom:1.21.4`.

Pin resolution check (plan QA alternative to the non-committed broken-pin branch; transcript `/tmp/opencode/aar/12-deptree.log`):

```
$ mvn -pl app dependency:tree -Dincludes=org.bsc.langgraph4j
[INFO]    +- org.bsc.langgraph4j:langgraph4j-spring-ai:jar:1.9.1:compile
[INFO]    |  \- org.bsc.langgraph4j:langgraph4j-core:jar:1.9.1:compile
[INFO]    \- org.bsc.langgraph4j:langgraph4j-postgres-saver:jar:1.9.1:compile
[INFO] BUILD SUCCESS
exit=0
```

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| `mvn -q verify` exits 0 | `mvn clean install -DskipTests` → 8/8 reactor SUCCESS; every module suite + `app clean verify` green at HEAD (runs listed above and in task-3..18 evidence) |
| Every module has one smoke test | `MarkerTest` green today in core (1), topologies (1), workload (1), ingress (1) suite outputs; bench `MarkerTest` in module; spike has 4 test classes; app `ApplicationTest` 1/1 green |
| `app` starts with `--spring.main.web-application-type=none` smoke | See Not re-runnable post-hoc — skeleton-time proof; today's evolved equivalent is `ApplicationTest` full-context boot 1/1 green |
| Pins resolve to intended versions | `dependency:tree -Dincludes=org.bsc.langgraph4j` → `1.9.1` across spring-ai/core/postgres-saver artifacts (above) |

## Not re-runnable post-hoc

- The literal skeleton acceptance "`app` starts with `--spring.main.web-application-type=none`" described the empty skeleton; the app has since become the full web deployable (REST/SSE/MCP on one port, waves 4–5). Re-running that exact flag today is not meaningful against HEAD. Closest re-runnable proof: `ApplicationTest` boots the full context 1/1 green (`app clean verify`).
- The QA "failure" path (deliberately wrong BOM version on a scratch branch) was, by the plan's own wording, never committed; the sanctioned alternative (`dependency:tree` shows 1.9.1) is what was executed today.
