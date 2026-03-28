# Stage 1: Build the Scala app + Scala.js frontend
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY . .
RUN ./sbt server/assembly web/fullLinkJS

# Stage 2: Runtime with Idris 2 + JDK + Node
FROM ghcr.io/joshuanianji/idris-2-docker/devcontainer:latest AS runtime

# Add JDK 21 (headless) + Node.js
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jre-headless \
    nodejs npm \
    && rm -rf /var/lib/apt/lists/*

# Pre-install elab-util (needed by most specs)
RUN pack install elab-util

WORKDIR /app

# Copy server JAR
COPY --from=builder /build/modules/server/target/scala-*/arkhitekton-server-assembly-*.jar app.jar

# Copy Scala.js SPA output + static assets
COPY --from=builder /build/modules/web/target/scala-*/arkhitekton-web-opt/ /app/static/
COPY --from=builder /build/modules/web/src/main/static/ /app/static/

ENV PORT=8080
ENV JAVA_OPTS="-Xmx512m -XX:MaxRAMPercentage=75"
ENV STATIC_DIR="/app/static"
EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
