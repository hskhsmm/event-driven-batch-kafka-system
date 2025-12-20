# 1. Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew clean bootJar

# 2. Run stage
FROM eclipse-temurin:25-jre
WORKDIR /app
# 프로젝트 이름으로 시작하는 JAR 파일만 명확하게 복사
COPY --from=build /app/build/libs/batch-kafka-system-*.jar app.jar

# 가상 스레드 활용을 위한 JVM 옵션 추가 가능
ENTRYPOINT ["java", "-jar", "app.jar"]