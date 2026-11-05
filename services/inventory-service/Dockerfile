# syntax=docker/dockerfile:1

# ---- Etapa de compilacion ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Primero solo el pom, para aprovechar la cache de capas con las dependencias.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ---- Etapa de ejecucion ----
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/inventory-service.jar /app/app.jar

USER app
EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
