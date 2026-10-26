FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon resolveDependencies
COPY src ./src
# Integration tests run via ./gradlew build on a Docker-capable host/CI.
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system devlink && useradd --system --gid devlink --no-create-home devlink
WORKDIR /app
COPY --from=build --chown=devlink:devlink /workspace/build/libs/app.jar app.jar
USER devlink
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
