# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Primero solo el pom: la capa de dependencias se cachea mientras no cambie.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:17-jre-alpine

# Usuario sin privilegios
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app

WORKDIR /app
COPY --from=build /build/target/order-service.jar /app/order-service.jar

USER app
EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8083/actuator/health || exit 1

# Los secretos (JWT_SECRET, INTERNAL_API_KEY, SPRING_DATASOURCE_*) llegan solo por variables de entorno.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/order-service.jar"]
