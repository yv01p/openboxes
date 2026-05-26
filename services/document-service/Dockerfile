FROM eclipse-temurin:21-jre-jammy

# Install curl for the compose healthcheck (base image does not include it).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8081
WORKDIR /app
COPY build/libs/document-service-*.jar /app/document-service.jar
ENTRYPOINT ["java", "-jar", "/app/document-service.jar"]
