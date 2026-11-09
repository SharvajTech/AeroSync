# aerosync-backend/Dockerfile
# Build context: aerosync-backend/ (this directory)

# ── Build stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src ./src
RUN ./mvnw -B clean package -DskipTests

# ── Runtime stage ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

# curl is used by the HEALTHCHECK below (and useful for manual debugging
# inside the container — see the main README's Docker troubleshooting section)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# /api/flights is a real endpoint backed by the DB — a healthy response
# means the app started AND the DB connection actually works, not just
# that the JVM is up.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=6 \
    CMD curl -f http://localhost:8080/api/flights || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
