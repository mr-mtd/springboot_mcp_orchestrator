# syntax=docker/dockerfile:1

# ---- build stage: compile and package the jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
# Tests run in CI with `mvn verify`, not inside the image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && cp target/transport-mcp-orchestrator-*.jar /src/app.jar

# ---- runtime stage: JRE only ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /src/app.jar /app/app.jar
USER app

ENV PORT=8081 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
