# Multi-stage image for the single deployable app (T29 demo stack).
# Build stage: full JDK 25 + Maven building the multi-module reactor limited to
# `app` and its dependencies (core, topologies, ingress, workload). The Maven
# local repository rides a BuildKit cache mount so dependency downloads survive
# across rebuilds. Runtime stage: JRE 25 only, non-root user, boot jar.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Module POMs first (all of them — Maven validates every <module> of the parent
# even when the reactor is narrowed with -pl), then a cacheable dependency
# pre-fetch, then sources.
COPY pom.xml ./
COPY spike/pom.xml spike/pom.xml
COPY core/pom.xml core/pom.xml
COPY topologies/pom.xml topologies/pom.xml
COPY ingress/pom.xml ingress/pom.xml
COPY workload/pom.xml workload/pom.xml
COPY bench/pom.xml bench/pom.xml
COPY app/pom.xml app/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl app -am dependency:go-offline -DskipTests || true

COPY core core
COPY topologies topologies
COPY ingress ingress
COPY workload workload
COPY app app
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl app -am package -DskipTests

FROM eclipse-temurin:25-jre AS runtime
RUN useradd --system --uid 1001 --home /app appuser
WORKDIR /app
COPY --from=build /build/app/target/app-*.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
