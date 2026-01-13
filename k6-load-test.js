import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 커스텀 메트릭
const successCount = new Counter('participation_success');
const failCount = new Counter('participation_fail');

// 환경변수로부터 설정 읽기
const RATE = parseInt(__ENV.RATE) || 3000;           // 초당 요청 수
const DURATION = parseInt(__ENV.DURATION) || 10;     // 테스트 지속 시간 (초)
const MAX_VUS = parseInt(__ENV.MAX_VUS) || 5000;     // 최대 가상 사용자 수
const TOTAL_REQUESTS = parseInt(__ENV.TOTAL_REQUESTS) || 30000; // 총 요청 수
const PARTITIONS = parseInt(__ENV.PARTITIONS) || 3;  // Kafka 파티션 수

console.log(` Kafka 부하 테스트 설정: ${TOTAL_REQUESTS}개 요청, ${RATE}/s, ${DURATION}s, ${PARTITIONS} 파티션`);

// 테스트 설정
export const options = {
  scenarios: {
    spike_test: {
      executor: 'constant-arrival-rate',
      rate: RATE,              // 초당 요청 수 (환경변수)
      timeUnit: '1s',
      duration: `${DURATION}s`, // 지속 시간 (환경변수)
      preAllocatedVUs: Math.floor(MAX_VUS * 0.6), // maxVUs의 60%를 미리 할당
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
    timeout: '600s', // 타임아웃 10분 (duration과 동일, 확실한 처리)
  };

  // 선착순 참여 요청
  const response = http.post(
    `${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/participation`,
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
  });

  // 성공/실패 카운트 (참고: Kafka 비동기 처리라 즉시 결과는 모름)
  if (response.status === 200) {
    successCount.add(1);
  } else {
    failCount.add(1);
  }

  // 응답 로그 (샘플링)
  if (__VU % 10 === 0) {
    console.log(`[VU ${__VU}] Status: ${response.status}, Body: ${response.body}`);
  }
}

// K6 기본 summary 사용 (handleSummary 제거하여 백엔드 파서와 호환)
