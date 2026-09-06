FROM gradle:8.14-jdk17 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/install/server ./
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
