FROM eclipse-temurin:21-jre-jammy

# Install curl for the compose healthcheck (base image does not include it).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8082
WORKDIR /app
COPY build/libs/identity-service-*.jar /app/identity-service.jar

# Run as an unprivileged user (T2-M3). The COPY above ran as root and writes
# /app/identity-service.jar world-readable by default, so the spring user can
# read the jar without an explicit chown.
RUN useradd -r spring
USER spring

ENTRYPOINT ["java", "-jar", "/app/identity-service.jar"]
