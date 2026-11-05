# notification-service — imagen multi-etapa (Java 17 / Spring Boot 3.5)

# ---- Etapa 1: compilación
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Capa de dependencias: solo se reconstruye si cambia el pom.xml
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ---- Etapa 2: ejecución (solo JRE, usuario sin privilegios)
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/notification-service.jar /app/app.jar

USER app
EXPOSE 8084

# Estado del servicio y de su base de datos (público, sin detalles).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8084/actuator/health || exit 1

# JWT_SECRET, INTERNAL_API_KEY y SPRING_DATASOURCE_* se inyectan por entorno (sin valores por defecto).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
