# syntax=docker/dockerfile:1

# ---- build ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
# Wrapper and build scripts first, so the dependency layer is cached between source changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ---- run ----
FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app --uid 1001 app
WORKDIR /app
COPY --from=build /src/build/libs/app.jar app.jar
USER app
EXPOSE 8080
# Size the heap from the container's memory limit instead of the host's.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
