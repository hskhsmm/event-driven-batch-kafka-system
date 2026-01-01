# 1. Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# gradle wrapper
COPY gradlew .
COPY gradle gradle
RUN chmod +x gradlew

# gradle 설정 (캐시)
COPY build.gradle* settings.gradle* ./
RUN ./gradlew dependencies --no-daemon || true

# 소스 코드만 복사
COPY src src

RUN ./gradlew clean bootJar --no-daemon

# 2. Run stage
FROM eclipse-temurin:25-jre
WORKDIR /app

# K6 설치 추가
RUN apt-get update && \
    apt-get install -y wget ca-certificates && \
    wget -q https://github.com/grafana/k6/releases/download/v0.48.0/k6-v0.48.0-linux-amd64.tar.gz && \
    tar -xzf k6-v0.48.0-linux-amd64.tar.gz && \
    mv k6-v0.48.0-linux-amd64/k6 /usr/local/bin/ && \
    rm -rf k6-v0.48.0-linux-amd64* && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# K6 스크립트 복사
COPY k6-load-test.js /app/k6-load-test.js
COPY k6-sync-test.js /app/k6-sync-test.js

# JAR 파일 복사
COPY --from=build /app/build/libs/batch-kafka-system-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
