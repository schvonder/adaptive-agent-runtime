# Wave-0 Integration Spike

This document summarizes the findings from the Wave-0 Integration Spike.

## Verdicts
- (a) **PASS**: `langgraph4j-spring-ai` compiles+runs against `spring-ai-starter-model-openai` via `ChatClient` with a minimal WireMock stubbed node. (Tool execution disabled for the first request; intercept and mapping correctly tested).
- (b) **PASS**: `langgraph4j-postgres-saver` verified for both parallel fan-out state updates (which are implicitly merged safely into the central Map state) and checkpoint resume (resumes exactly from the point of failure using the same threadId).
- (c) **SKIPPED**: Ollama test `OllamaIntegrationTest` was skipped because `OLLAMA_BASE_URL` was not matched (Ollama was DOWN during probe).
- (d) **PASS**: WireMock-stubbed OpenAI endpoints correctly served JSON and returned parsed tool definitions successfully.

## Dependency Verification
* Spring Boot `4.1.1` - Found
* Spring AI `2.0.1` - Found
* LangGraph4j BOM `1.9.1` - Found (`org.bsc.langgraph4j:langgraph4j-bom`)
* Testcontainers `1.21.4` - Found

## Postgres Saver DDL Mechanism
**Runtime Creation**: `PostgresSaver.builder().createTables(true)` automatically sends `CREATE TABLE IF NOT EXISTS` via JDBC for `LG4JThread`, `LG4JThreadTag` and `LG4JCheckpoint` when the saver initializes. 

## Checkpoint Resume API Recipe
In LangGraph4j 1.9.1, resuming from a saved checkpoint happens naturally by re-invoking the compiled graph using the exact same `threadId` in the `RunnableConfig`, and passing an empty map (or `null` casted to Map) as the initial graph input:

```java
RunnableConfig config = RunnableConfig.builder().threadId("thread-resume").build();
// First run
try {
    compiled.invoke(Map.of(), config);
} catch(Exception e) {}

// Resume run (automatically picks up from failed node and skips completed nodes)
Optional<AgentState> resultOpt = compiled.invoke((Map<String, Object>) null, config);
```

## Parallel State Branching Strategy
LangGraph4j allows multiple incoming and outgoing edges for nodes. The state payload structure relies heavily on a `Map<String, Object>`. No custom reducers or Channels are strictly necessary for basic merge behavior; `AgentState` automatically appends and updates the central Map under the hood, making fan-out and multi-writes across parallel branches natively consistent.

## Fallbacks Applied
* No module version fallback needed since all BOMs and components matched Central correctly.
* Replaced `java.util.function.Function` with `@Tool` annotated method enclosed in a Spring Bean class (`WeatherService`), as required by Spring AI >= 2.x when interacting with `ChatClient`.

## Exact Working Version Combo
```xml
<spring-boot.version>4.1.1</spring-boot.version>
<spring-ai.version>2.0.1</spring-ai.version>
<langgraph4j.version>1.9.1</langgraph4j.version>
<testcontainers.version>1.21.4</testcontainers.version>
<wiremock.version>3.12.0</wiremock.version>
```

## Dependency List for Later Modules (`core`/`topologies`)
```xml
<!-- Spring Boot -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
<dependency>
    <groupId>org.bsc.langgraph4j</groupId>
    <artifactId>langgraph4j-spring-ai</artifactId>
</dependency>
<dependency>
    <groupId>org.bsc.langgraph4j</groupId>
    <artifactId>langgraph4j-postgres-saver</artifactId>
</dependency>
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```