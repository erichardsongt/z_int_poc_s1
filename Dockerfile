# syntax=docker/dockerfile:1
#
# Meridian dispute-intake prototype: hardened container image.
#
#   Build stage:   full JDK, compiles the sources (no third-party dependencies, no network needed)
#   Runtime stage: JRE only, no compiler or build tools, runs as an unprivileged user
#
# Runtime hardening (read-only root FS, all Linux capabilities dropped, no-new-privileges,
# resource limits, no network for demo/test) is applied by docker-run.sh / docker-compose.yml.
# Base images are pinned by tag; for a production pipeline, pin them by digest (image@sha256:…).

ARG JDK_IMAGE=eclipse-temurin:17-jdk-jammy
ARG JRE_IMAGE=eclipse-temurin:17-jre-jammy

# ---------------------------------------------------------------------------- build
FROM ${JDK_IMAGE} AS build
WORKDIR /workspace
COPY src ./src
RUN set -eux; \
    mkdir -p build/classes; \
    find src/main/java -name '*.java' > build/sources.txt; \
    javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt; \
    cp -R src/main/resources/. build/classes/; \
    jar --create --file /workspace/meridian-poc.jar --main-class com.meridian.poc.App -C build/classes .

# ---------------------------------------------------------------------------- runtime
FROM ${JRE_IMAGE} AS runtime

LABEL org.opencontainers.image.title="meridian-dispute-poc" \
      org.opencontainers.image.description="Meridian Bank digital assistant: dispute-intake prototype (all external systems simulated)" \
      org.opencontainers.image.licenses="Proprietary - interview exercise"

# Unprivileged, shell-less system user with a fixed UID/GID (no home directory, no login).
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin app

WORKDIR /app
# Root-owned and read-only for the app user: the process cannot modify its own code.
COPY --from=build --chown=root:root --chmod=0444 /workspace/meridian-poc.jar /app/meridian-poc.jar

ENV MERIDIAN_BIND_ADDRESS=0.0.0.0 \
    MERIDIAN_BASE_PORT=8080

USER 10001:10001

# Only the assistant data plane (demo console) is published. The IdP, façade, core-banking and
# Salesforce mocks listen on the container's loopback and are unreachable from outside it.
EXPOSE 8080

# Real application-level check (not just "port open"), using bash's /dev/tcp so no curl is needed.
HEALTHCHECK --interval=15s --timeout=3s --start-period=15s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /healthz HTTP/1.0\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200 "' || exit 1

# -XX:-UsePerfData: the JVM writes nothing to disk, so the root filesystem can be read-only.
# MaxRAMPercentage: the heap respects the container memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:-UsePerfData", "-Djava.io.tmpdir=/tmp", "-Dfile.encoding=UTF-8", \
            "-jar", "/app/meridian-poc.jar"]
CMD ["serve"]
