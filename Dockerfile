FROM eclipse-temurin:25.0.3_9-jdk@sha256:68868d04fa9cfd5f5c6abec0b5cef86d8de2bf9c62c37c7d3e4f0f80f5cfd7ff AS builder

WORKDIR /app

COPY gradle /app/gradle
COPY build.gradle.kts gradle.lockfile gradle.properties gradlew settings.gradle.kts settings-gradle.lockfile /app/
COPY core /app/core
COPY domain /app/domain
COPY src /app/src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:25.0.3_9-jre@sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m stock-analyst

USER stock-analyst

COPY --from=builder /app/build/libs/stock-analyst-all.jar stock-analyst.jar

EXPOSE 8080

CMD ["java", "-jar", "stock-analyst.jar"]
