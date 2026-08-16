# ---- build stage ------------------------------------------------------------
# Dependencies are resolved in their own layer so a code-only change does not
# re-download the whole Maven repository on every build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- runtime stage ----------------------------------------------------------
# JRE only, and a non-root user. The final image carries no Maven, no compiler
# and no source.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S slotsync && adduser -S slotsync -G slotsync
COPY --from=build /app/target/*.jar app.jar
USER slotsync

EXPOSE 8080

# Container-aware heap sizing, and a fixed UTC clock so every replica agrees on
# what "now" means regardless of where it runs.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
