import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 커스텀 메트릭
const successCount = new Counter('participation_success');
const failCount = new Counter('participation_fail');

// 테스트 설정
export const options = {
  scenarios: {
    // 시나리오 1: 100명이 1초 동안 동시 요청 (동기 방식 성능 테스트)
    spike_test: {
      executor: 'constant-arrival-rate',
      rate: 100,           // 100개 요청
      timeUnit: '1s',      // 1초 동안
      duration: '5s',      // 5초간 지속
      preAllocatedVUs: 100, // 미리 할당할 VU
      maxVUs: 150,         // 최대 VU
    },
  },
  // Threshold 제거: 성능 측정이 목적이므로 pass/fail 기준 불필요
};

const BASE_URL = 'http://localhost:8080';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID || 1; // 환경변수로 캠페인 ID 전달 가능

export default function () {
  const userId = __VU; // Virtual User ID를 userId로 사용 (1~100)

  const payload = JSON.stringify({
    userId: userId,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  // 동기 방식 참여 요청 (Kafka 없이 바로 DB 처리)
  const response = http.post(
    `${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/participation-sync`,
    payload,
    params
  );

  // 응답 검증
  const isSuccess = check(response, {
    'status is 200': (r) => r.status === 200,
    'response has success field': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.hasOwnProperty('success');
      } catch (e) {
        return false;
      }
    },
    'response has method=SYNC': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data && body.data.method === 'SYNC';
      } catch (e) {
        return false;
      }
    },
  });

  // 성공/실패 카운트 (동기 방식이므로 즉시 결과 확인 가능)
  if (response.status === 200) {
    try {
      const body = JSON.parse(response.body);
      if (body.data && body.data.status === 'SUCCESS') {
        successCount.add(1);
      } else {
        failCount.add(1);
      }
    } catch (e) {
      failCount.add(1);
    }
  } else {
    failCount.add(1);
  }

  // 응답 로그 (샘플링)
  if (__VU % 10 === 0) {
    console.log(`[VU ${__VU}] Status: ${response.status}, Body: ${response.body}`);
  }
}

// K6 기본 summary 사용 (handleSummary 제거하여 백엔드 파서와 호환)
