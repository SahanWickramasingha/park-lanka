# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
# Run the repository tests separately before deployment; some inspect desktop sources.
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --system --gid 10001 parking \
    && useradd --system --uid 10001 --gid parking --home-dir /app --shell /usr/sbin/nologin parking \
    && mkdir -p /app/backups \
    && chown 10001:10001 /app /app/backups
# This is the executable artifact from backend/pom.xml; never select *.jar.
COPY --from=build /build/target/parking-backend-0.2.0.jar /app/app.jar
ENV SPRING_PROFILES_ACTIVE=neon \
    PARKING_ENV_ENABLED=false \
    SERVER_ADDRESS=0.0.0.0 \
    PORT=10000 \
    PARKING_BACKUP_DIRECTORY=/app/backups \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -Djava.awt.headless=true"
USER 10001:10001
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
