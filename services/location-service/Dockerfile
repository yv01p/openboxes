FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8083
WORKDIR /app
COPY build/libs/location-service-*.jar /app/location-service.jar

RUN useradd -r spring
USER spring

ENTRYPOINT ["java", "-jar", "/app/location-service.jar"]
