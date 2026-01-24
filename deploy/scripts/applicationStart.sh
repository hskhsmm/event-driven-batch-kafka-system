#!/usr/bin/env bash
set -euo pipefail

echo "[applicationStart] Blue/Green 무중단 배포 시작"

APP_DIR=/opt/batch-kafka
ENV_FILE="${APP_DIR}/.env.prod"
STATE_FILE="${APP_DIR}/.deploy-state"

# Blue/Green 컨테이너 설정
BLUE_CONTAINER="batch-kafka-app-blue"
GREEN_CONTAINER="batch-kafka-app-green"
BLUE_PORT="8080"
GREEN_PORT="8081"

# ECR 이미지 확인
if [[ -z "${ECR_IMAGE:-}" ]]; then
  if [[ -f "${ENV_FILE}" ]]; then
    ECR_IMAGE=$(grep '^ECR_IMAGE=' "${ENV_FILE}" | cut -d= -f2 || true)
    export ECR_IMAGE
  fi
fi

if [[ -z "${ECR_IMAGE:-}" ]]; then
  echo "[applicationStart] ERROR: ECR_IMAGE not defined" >&2
  exit 1
fi

# 현재 활성 컨테이너 확인
get_active_container() {
  if docker ps --format '{{.Names}}' | grep -q "^${BLUE_CONTAINER}$"; then
    echo "blue"
  elif docker ps --format '{{.Names}}' | grep -q "^${GREEN_CONTAINER}$"; then
    echo "green"
  else
    echo "none"
  fi
}

# Health Check 함수
wait_for_health() {
  local port=$1
  local max_retry=30
  local retry_count=0

  echo "[applicationStart] Health check 대기 중 (port: ${port})..."

  until curl -fsS --max-time 3 "http://localhost:${port}/actuator/health/readiness" 2>/dev/null | grep -q '"status".*"UP"'; do
    retry_count=$((retry_count + 1))
    if [[ $retry_count -ge $max_retry ]]; then
      echo "[applicationStart] ERROR: Health check 실패 (${retry_count}/${max_retry})" >&2
      return 1
    fi
    echo "[applicationStart] Health check 재시도... (${retry_count}/${max_retry})"
    sleep 2
  done

  echo "[applicationStart] Health check 성공!"
  return 0
}

# Graceful Shutdown 함수
graceful_stop() {
  local container_name=$1

  if docker ps --format '{{.Names}}' | grep -q "^${container_name}$"; then
    echo "[applicationStart] ${container_name} Graceful Shutdown 시작 (최대 60초 대기)"
    docker stop --time 60 "${container_name}" || true
    docker rm "${container_name}" || true
    echo "[applicationStart] ${container_name} 종료 완료"
  fi
}

# 컨테이너 시작 함수
start_container() {
  local container_name=$1
  local port=$2

  echo "[applicationStart] ${container_name} 시작 (port: ${port})"

  # 기존 중지된 컨테이너 정리
  docker rm "${container_name}" 2>/dev/null || true

  docker run -d \
    --name "${container_name}" \
    --env-file "${ENV_FILE}" \
    -e SPRING_PROFILES_ACTIVE=prod,p5 \
    -e JAVA_TOOL_OPTIONS="-Xms2g -Xmx5g -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8" \
    -p "${port}:8080" \
    --memory=6g \
    --memory-swap=6g \
    --restart unless-stopped \
    "${ECR_IMAGE}"
}

# 기존 단일 컨테이너 정리 (Blue/Green 전환 시 최초 1회)
if docker ps -a --format '{{.Names}}' | grep -q "^batch-kafka-app$"; then
  echo "[applicationStart] 기존 단일 컨테이너(batch-kafka-app) 정리"
  docker stop --time 60 batch-kafka-app 2>/dev/null || true
  docker rm batch-kafka-app 2>/dev/null || true
fi

# 메인 로직
CURRENT_ACTIVE=$(get_active_container)
echo "[applicationStart] 현재 활성 컨테이너: ${CURRENT_ACTIVE}"

if [[ "${CURRENT_ACTIVE}" == "blue" ]]; then
  NEW_CONTAINER="${GREEN_CONTAINER}"
  NEW_PORT="${GREEN_PORT}"
  OLD_CONTAINER="${BLUE_CONTAINER}"
  OLD_PORT="${BLUE_PORT}"
elif [[ "${CURRENT_ACTIVE}" == "green" ]]; then
  NEW_CONTAINER="${BLUE_CONTAINER}"
  NEW_PORT="${BLUE_PORT}"
  OLD_CONTAINER="${GREEN_CONTAINER}"
  OLD_PORT="${GREEN_PORT}"
else
  # 최초 배포 또는 둘 다 없는 경우
  NEW_CONTAINER="${BLUE_CONTAINER}"
  NEW_PORT="${BLUE_PORT}"
  OLD_CONTAINER=""
  OLD_PORT=""
fi

echo "[applicationStart] 새 컨테이너: ${NEW_CONTAINER} (port: ${NEW_PORT})"

# 1. 새 컨테이너 시작
start_container "${NEW_CONTAINER}" "${NEW_PORT}"

# 2. 새 컨테이너 Health Check
if ! wait_for_health "${NEW_PORT}"; then
  echo "[applicationStart] ERROR: 새 컨테이너 시작 실패, 롤백 진행" >&2
  docker stop "${NEW_CONTAINER}" 2>/dev/null || true
  docker rm "${NEW_CONTAINER}" 2>/dev/null || true
  exit 1
fi

# 3. 기존 컨테이너 Graceful Shutdown
if [[ -n "${OLD_CONTAINER}" ]]; then
  graceful_stop "${OLD_CONTAINER}"
fi

# 4. 새 컨테이너를 메인 포트(8080)로 재시작
echo "[applicationStart] 새 컨테이너를 메인 포트(8080)로 전환"
docker stop "${NEW_CONTAINER}"
docker rm "${NEW_CONTAINER}"

start_container "${NEW_CONTAINER}" "8080"

# 5. 최종 Health Check
if ! wait_for_health "8080"; then
  echo "[applicationStart] ERROR: 최종 Health check 실패" >&2
  exit 1
fi

# 상태 저장
echo "${NEW_CONTAINER}" > "${STATE_FILE}"

echo "[applicationStart] Blue/Green 무중단 배포 완료!"
echo "[applicationStart] 활성 컨테이너: ${NEW_CONTAINER}"
