FROM eclipse-temurin:25.0.2_10-jdk AS builder

WORKDIR /app

COPY gradle /app/gradle
COPY build.gradle.kts gradle.properties gradlew settings.gradle.kts /app/
COPY core /app/core
COPY domain /app/domain
COPY src /app/src

RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:25.0.2_10-jre

WORKDIR /app

RUN useradd -m portfolio

USER portfolio

COPY --from=builder /app/build/libs/portfolio-all.jar portfolio.jar

EXPOSE 7777

CMD ["java", "-jar", "portfolio.jar"]
