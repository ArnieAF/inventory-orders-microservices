# ---- Etapa de compilacion ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Primero solo el pom: la capa de dependencias se cachea mientras el pom no cambie.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

# ---- Imagen final ----
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build /build/target/app.jar /app/app.jar
USER app
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
