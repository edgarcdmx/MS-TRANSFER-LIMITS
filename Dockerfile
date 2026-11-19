# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1: build the layered Spring Boot jar and extract its layers.
# Quality gates (tests, Spotless, Checkstyle, JaCoCo) run in `./mvnw verify`
# before the image is built, so they are skipped here.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/main/ src/main/

RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw \
    && ./mvnw -B -Dmaven.test.skip=true -Dspotless.check.skip=true \
       -Dcheckstyle.skip=true -Djacoco.skip=true package \
    && cp target/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
# Stage 2: minimal runtime (JRE only), non-root user, layered copy.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:27-jre

LABEL org.opencontainers.image.title="ms-transfer-limits" \
      org.opencontainers.image.description="Validates transfers against per-channel daily and per-operation limits" \
      org.opencontainers.image.version="0.1.0-SNAPSHOT" \
      org.opencontainers.image.vendor="Banamex" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:27-jre"

RUN groupadd --system --gid 10001 spring \
    && useradd --system --uid 10001 --gid spring --no-create-home --shell /usr/sbin/nologin spring

WORKDIR /app

# Least to most frequently changing, to maximise layer cache reuse.
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring:spring

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# The temurin JRE image ships neither curl nor wget, so the probe uses bash's /dev/tcp
# (no extra packages, no second JVM). It passes only on an HTTP 200 from the health endpoint.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 200 '"]

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
