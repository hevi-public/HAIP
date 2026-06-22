# Single Dockerized build entrypoint — the source of truth for the pipeline.
# `claude -p` is mocked under the `test` profile, so the build image needs no Claude auth.
# Base JDK is 21 to match the Gradle toolchain target; the daemon runs on it, so the
# toolchain is satisfied by the container's own JVM (no JDK download needed).
FROM eclipse-temurin:21-jdk AS build

# Node is needed for the frontend unit tier (the `jsTest` Gradle task → `npm test`, pure *-core.mjs under
# node:test). NodeSource gives a modern Node (the test runner's glob args need v21+), which Ubuntu's apt
# Node is too old for. node+npm only — the front-end modules have no runtime deps to install.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY . .

# The Gradle distribution + dependencies are cached in a named volume (see docker-compose.yml),
# which mounts over ~/.gradle at runtime — so the first `docker compose run` populates it and later
# runs are fast. Args (the pipeline stages) are supplied by docker-compose / CI.
ENTRYPOINT ["./gradlew", "--no-daemon"]
CMD ["verifyAll"]
