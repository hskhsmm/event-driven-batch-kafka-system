# 1. Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# gradle wrapper만 먼저 복사
COPY gradlew .
COPY gradle gradle
RUN chmod +x gradlew

# gradle 설정 파일 (캐시용)
COPY build.gradle* settings.gradle* ./
RUN ./gradlew dependencies --no-daemon || true

# ⚠️ 나머지 소스 복사 (gradlew 제외됨)
COPY src src
COPY docker docker
# 필요하면 다른 디렉터리도 명시적으로 추가

RUN ./gradlew clean bootJar --no-daemon

# 2. Run stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/libs/batch-kafka-system-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
