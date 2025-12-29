#!/usr/bin/env bash
set -euo pipefail

echo "[afterInstall] Start"

# 반드시 beforeInstall에서 .env.prod에 채워진 값을 사용
ENV_FILE=/opt/batch-kafka/.env.prod

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[afterInstall] ERROR: ${ENV_FILE} not found"
  exit 1
fi

# shellcheck disable=SC1090
set -a
source "${ENV_FILE}"
set +a

if [[ -z "${ECR_IMAGE:-}" ]]; then
  echo "[afterInstall] ERROR: ECR_IMAGE not set"
  exit 1
fi

echo "[afterInstall] Pulling image: ${ECR_IMAGE}"
docker pull "${ECR_IMAGE}"

echo "[afterInstall] Completed"
