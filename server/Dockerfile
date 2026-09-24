# Runtime image for the POS *store* server (Ktor fat jar).
# Used by the LOCAL demo stack (../docker-compose.local.yml) and is a faithful
# rehearsal for the eventual EC2 deploy. Build context is server/ (Gradle root).
#
# FRENCH RECEIPTS: the store draws French as a Java2D raster bitmap (no printer code
# page), so the runtime image MUST carry Unicode fonts + fontconfig or SansSerif
# falls back to boxes (□□□). Noto Core supplies Latin and French glyphs; fontconfig
# lets the JVM discover them; -Djava.awt.headless=true lets Java2D render with no
# X server. See docs/demo-runbook.md for the test-print gate.
# Base images are pinned by digest (tag kept for readability); Dependabot's
# docker ecosystem bumps the digest. The digest is the multi-arch manifest-list
# digest, so it still resolves the right arch on both x64 CI and the arm64 box.
FROM gradle:8.10-jdk17@sha256:c2900027f3f0681c2cbfb09d527813851ad67aeafbb409997297efa2df20e748 AS build
WORKDIR /home/gradle/project
# --chown so the build works whether the base image runs as root or gradle.
COPY --chown=gradle:gradle . .
# +x bit on the wrapper can be lost across checkouts.
RUN chmod +x gradlew && ./gradlew --no-daemon buildFatJar

FROM eclipse-temurin:17-jre@sha256:92999aea37688157a53a40bfcb187c30f317422e028045fd5fc5c548fde9e626
# fonts-noto-core: supplies "Noto Sans" — the renderer's first-choice family.
# fonts-noto-core: broad Unicode coverage, including French Latin characters.
# fontconfig: JVM font discovery. curl: compose healthcheck. tzdata: local time on receipts.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-core fontconfig curl tzdata \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home /app --shell /usr/sbin/nologin pos
WORKDIR /app
# buildFatJar emits <rootProject.name>-all.jar; this project's is pos-server-all.jar.
COPY --from=build /home/gradle/project/build/libs/pos-server-all.jar /app/pos-server-all.jar
# SQLite db + receipts/bills/photos live under /data (a named volume in compose)
# so they survive container restarts. Owned by the unprivileged runtime user.
RUN mkdir -p /data /data/receipts /data/bills /data/photos && chown -R pos:pos /data
USER pos
ENV TZ=America/New_York
# Heap cap, overridable per deployment: container-per-venue on a small instance
# runs several of these JVMs side by side (M8), so venues set e.g. POS_XMX=224m.
ENV POS_XMX=512m
EXPOSE 8080
# Headless AWT so the Java2D French raster renders without an X server.
# Shell form so POS_XMX expands; exec keeps java as PID 1 for clean signals.
CMD exec java -Djava.awt.headless=true -Xmx${POS_XMX} -jar /app/pos-server-all.jar
