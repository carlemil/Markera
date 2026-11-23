# Base images are pinned by digest (multi-arch index; Dependabot can bump them).
# Built with the project's own Gradle wrapper, so the image uses the same Gradle
# as a local ./gradlew (gradle/wrapper/gradle-wrapper.properties).
FROM eclipse-temurin:24-jdk@sha256:7493205ffe6caa8074fa8a06a276bb1c5ac41d3dd0fd43a0db66d7f776e80b3e AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
# gradlew is mode 644 in git.
RUN chmod +x gradlew && ./gradlew installDist --no-daemon

FROM eclipse-temurin:24-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2
# A fixed non-root user (1000 is taken by the image's "ubuntu" user). /data is
# created owned by it, so a fresh named volume mounted there inherits that owner.
# A volume first written by the old root container must be chowned once, see
# server/README.md.
RUN groupadd --system --gid 10001 markera \
 && useradd --system --uid 10001 --gid markera --no-create-home --shell /usr/sbin/nologin markera \
 && mkdir -p /data \
 && chown markera:markera /data
WORKDIR /app
COPY --from=build /src/build/install/server ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
