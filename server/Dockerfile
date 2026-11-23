# Base images are pinned by digest (multi-arch index; Dependabot can bump them).
# Built with the project's own Gradle wrapper, so the image uses the same Gradle
# as a local ./gradlew (gradle/wrapper/gradle-wrapper.properties).
FROM eclipse-temurin:17-jdk@sha256:7a0b2bc7e1372bb05ee361fb79e8a9e822df47343b8716253b3bbaa75060653e AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
# gradlew is mode 644 in git.
RUN chmod +x gradlew && ./gradlew installDist --no-daemon

FROM eclipse-temurin:17-jre@sha256:541b08fc7f494edf14e436ff6bfb55767e4a115c87bfc547b8c5de957b58737f
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
