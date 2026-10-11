# T11 — Research workload tools, fixture-first (adaptive-agent-runtime)

Date: 2026-10-11 · BACKFILLED POST-HOC: work originally implemented and accepted in its wave session (commit cf86ab9); acceptance re-executed today at HEAD 725c9fa

## What was implemented

Plan spec T11: `workload` module — `SearchTool` (interface + `TavilyAdapter` reading `TAVILY_API_KEY` env + `FixtureSearchAdapter` reading recorded JSON fixtures), `FetchTool` (fixture-backed page fetch), `CiteTool` (citations from tool outputs); Spring AI `@Tool` beans; fixture set `workload/src/test/resources/fixtures/search/*.json`. No live network in tests/bench.

Commit `cf86ab9 feat(workload): search/fetch/cite tools with fixture adapters` (2026-10-03):

```
 workload/pom.xml                                   |  15 +
 .../workload/tools/{Citation,CiteTool,FetchTool,FixtureFetchAdapter,FixtureSearchAdapter,ResearchTools,SearchResult,SearchTool,Slug,TavilyAdapter,WorkloadToolsConfig}.java
 .../workload/tools/{CiteToolTest,FixtureFetchAdapterTest,FixtureSearchAdapterTest,ResearchToolsTest,WorkloadToolsContextTest}.java
 .../fixtures/pages/*.json (2)  .../fixtures/search/*.json (3)
 22 files changed, 714 insertions(+)
```

## Acceptance re-executed today (post-hoc)

Full workload suite at HEAD (transcript `/tmp/opencode/aar/02-workload-test.log`):

```
$ mvn -pl workload test
[INFO] Running io.adaptiveagents.workload.MarkerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.074 s -- in io.adaptiveagents.workload.MarkerTest
[INFO] Running io.adaptiveagents.workload.tools.FixtureSearchAdapterTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.528 s -- in io.adaptiveagents.workload.tools.FixtureSearchAdapterTest
[INFO] Running io.adaptiveagents.workload.tools.ResearchToolsTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.068 s -- in io.adaptiveagents.workload.tools.ResearchToolsTest
[INFO] Running io.adaptiveagents.workload.tools.CiteToolTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.014 s -- in io.adaptiveagents.workload.tools.CiteToolTest
[INFO] Running io.adaptiveagents.workload.tools.WorkloadToolsContextTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.673 s -- in io.adaptiveagents.workload.tools.WorkloadToolsContextTest
[INFO] Running io.adaptiveagents.workload.tools.FixtureFetchAdapterTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.028 s -- in io.adaptiveagents.workload.tools.FixtureFetchAdapterTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit=0
```

Methods (surefire reports): FixtureSearchAdapterTest — `fixtureQueryReturnsParsedResults`, `missingFixtureReturnsEmptyList`, `slugNormalizationIsCaseInsensitiveAndPunctuationAware`, `blankOrNullQueryReturnsEmptyList`; FixtureFetchAdapterTest — `fixtureUrlReturnsContent`, `secondFixtureUrlReturnsContent`, `missingPageReturnsEmptyString`, `blankOrNullUrlReturnsEmptyString`; CiteToolTest — `citesAllResultsInStableOrderWhenNoFetchedUrls`, `filtersToFetchedUrlsAndKeepsStableNumbering`, `emptySearchResultsReturnsEmpty`; ResearchToolsTest — `webSearchDelegatesToFixtureSearch`, `fetchPageDelegatesToFixtureFetch`, `citeSourcesDelegatesToCiteTool`; WorkloadToolsContextTest — `fixtureSearchIsActiveAndTavilyAbsentWhenLiveDisabled`.

## Acceptance-criteria coverage

| Plan criterion | Command / output covering it |
|---|---|
| Fixture-driven unit tests for each tool | 16/16 green across all five tool test classes (search, fetch, cite, @Tool facade, context wiring) |
| No test makes outbound HTTP (WireMock/fixtures only) | Suite runs in seconds with no network; `fixtureSearchIsActiveAndTavilyAbsentWhenLiveDisabled` asserts the live adapter is absent unless `WORKLOAD_LIVE` set |
| QA happy: fixture query returns parsed results | `fixtureQueryReturnsParsedResults` |
| QA failure: empty fixture → tool returns empty list (not exception) | `missingFixtureReturnsEmptyList`, `missingPageReturnsEmptyString`, `emptySearchResultsReturnsEmpty` |

## Not re-runnable post-hoc

Nothing — fixture-first by design; re-run green today.
