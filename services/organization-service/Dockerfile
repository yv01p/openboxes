FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8084
WORKDIR /app
COPY build/libs/organization-service-*.jar /app/organization-service.jar

RUN useradd -r spring
USER spring

ENTRYPOINT ["java", "-jar", "/app/organization-service.jar"]
