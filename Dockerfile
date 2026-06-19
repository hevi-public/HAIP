# Single Dockerized build entrypoint — the source of truth for the pipeline.
# `claude -p` is mocked under the `test` profile, so the build image needs no Claude auth.
# Base JDK is 21 to match the Gradle toolchain target; the daemon runs on it, so the
# toolchain is satisfied by the container's own JVM (no JDK download needed).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .

# The Gradle distribution + dependencies are cached in a named volume (see docker-compose.yml),
# which mounts over ~/.gradle at runtime — so the first `docker compose run` populates it and later
# runs are fast. Args (the pipeline stages) are supplied by docker-compose / CI.
ENTRYPOINT ["./gradlew", "--no-daemon"]
CMD ["verifyAll"]
