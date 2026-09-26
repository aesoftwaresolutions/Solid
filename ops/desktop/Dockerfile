# Serverless-style desktop build for Docker: bundles the web UI into the jar (spec 060/066) but skips the
# platform-specific PostgreSQL binaries -- this image is meant to be pointed at a remote database via
# remote-db.properties, never the embedded one, so those binaries would just be dead weight.
FROM node:20-alpine AS webui
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /repo/backend
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=webui /web/dist /repo/frontend/dist
# -Ddesktop.os is only what selects the bundled-Postgres profile; an empty/unknown value activates just the
# "serve the web UI from the jar" half without pulling in binaries for a platform nothing here will use.
RUN mvn -B -q package -DskipTests -Ddesktop.os=none

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 solid
WORKDIR /app
COPY --from=build /repo/backend/target/solid-*.jar app.jar
USER solid
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
