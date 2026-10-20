# The research engine image: Stock Research Cockpit, monitor, MCP server and CLI (one image, four jars).
# Built by docker compose from the project folder (docker-compose.yml); no JDK or Maven needed on the PC.
# Tests are not run here: run `mvn verify` in java\ before changing the image.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
# The Maven cache survives between builds, so only the first build downloads the dependencies.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests \
 && mkdir /out \
 && cp app-web/target/research-web.jar app-worker/target/research-worker.jar \
       app-mcp/target/research-mcp.jar cli/target/research-cli.jar /out/

FROM eclipse-temurin:25-jre
# /data is the research database volume (alerts, watch list, ledger, price cache, SEC cache).
RUN mkdir -p /data /backups && chown ubuntu:ubuntu /data /backups
COPY --from=build /out/ /app/
USER ubuntu
WORKDIR /project
ENV RESEARCH_PROJECT_ROOT=/project \
    RESEARCH_ENV_FILE=/project/secrets.env \
    RESEARCH_RULES_FILE=/project/java/config/rules.properties \
    RESEARCH_PORTFOLIO=/project/portfolio.toml \
    RESEARCH_CACHE_DIR=/data \
    TZ=America/Toronto
CMD ["java", "-jar", "/app/research-web.jar", "--bind", "0.0.0.0"]
