# 1. Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# gradle wrapper 먼저
COPY gradlew .
COPY gradle gradle
RUN chmod +x gradlew

# 빌드 스크립트 (캐시 활용)
COPY build.gradle* settings.gradle* ./
RUN ./gradlew dependencies --no-daemon || true

# 나머지 소스
COPY . .
RUN ./gradlew clean bootJar --no-daemon

# 2. Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/batch-kafka-system-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
