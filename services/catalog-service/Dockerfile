FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8085
WORKDIR /app
COPY build/libs/catalog-service-*.jar /app/catalog-service.jar

RUN useradd -r spring
USER spring

ENTRYPOINT ["java", "-jar", "/app/catalog-service.jar"]
