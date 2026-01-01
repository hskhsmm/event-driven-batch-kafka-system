import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 커스텀 메트릭
const successCount = new Counter('participation_success');
const failCount = new Counter('participation_fail');

// 환경변수로부터 설정 읽기
const RATE = parseInt(__ENV.RATE) || 1000;           // 초당 요청 수
const DURATION = parseInt(__ENV.DURATION) || 30;     // 테스트 지속 시간 (초)
const MAX_VUS = parseInt(__ENV.MAX_VUS) || 10000;    // 최대 가상 사용자 수
const TOTAL_REQUESTS = parseInt(__ENV.TOTAL_REQUESTS) || 30000; // 총 요청 수
const PARTITIONS = parseInt(__ENV.PARTITIONS) || 3;  // Kafka 파티션 수 (참고용)

console.log(`⏱️ 동기 부하 테스트 설정: ${TOTAL_REQUESTS}개 요청, ${RATE}/s, ${DURATION}s`);

// 테스트 설정
export const options = {
  scenarios: {
    spike_test: {
      executor: 'constant-arrival-rate',
      rate: RATE,              // 초당 요청 수 (환경변수)
      timeUnit: '1s',
      duration: `${DURATION}s`, // 지속 시간 (환경변수)
      preAllocatedVUs: Math.floor(MAX_VUS * 0.5), // maxVUs의 50%를 미리 할당
      maxVUs: MAX_VUS,         // 최대 VU (환경변수)
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
