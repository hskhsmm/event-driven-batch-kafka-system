import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 커스텀 메트릭
const successCount = new Counter('participation_success');
const failCount = new Counter('participation_fail');

// 테스트 설정
export const options = {
  scenarios: {
    // 시나리오 1: 100명이 1초 동안 동시 요청
    spike_test: {
      executor: 'constant-arrival-rate',
      rate: 100,           // 100개 요청
      timeUnit: '1s',      // 1초 동안
      duration: '5s',      // 5초간 지속 (여유있게)
      preAllocatedVUs: 100, // 미리 할당할 VU
      maxVUs: 150,         // 최대 VU
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'], // 95%의 요청이 500ms 이내
    http_req_failed: ['rate<0.5'],    // 실패율 50% 이하 (재고 소진 정상)
  },
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

// 테스트 종료 후 요약
export function handleSummary(data) {
  return {
    'stdout': JSON.stringify({
      metrics: {
        http_req_duration_p95: data.metrics.http_req_duration.values['p(95)'],
        http_req_duration_avg: data.metrics.http_req_duration.values.avg,
        http_reqs_total: data.metrics.http_reqs.values.count,
        http_req_failed_rate: data.metrics.http_req_failed.values.rate,
        participation_success: data.metrics.participation_success ? data.metrics.participation_success.values.count : 0,
        participation_fail: data.metrics.participation_fail ? data.metrics.participation_fail.values.count : 0,
      },
      summary: `
========================================
📊 부하 테스트 결과
========================================
총 요청 수: ${data.metrics.http_reqs.values.count}
평균 응답 시간: ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms
95% 응답 시간: ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)}ms
실패율: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%

⚠️  주의: Kafka 비동기 처리로 실제 성공/실패는
   DB 또는 배치 집계 결과를 확인해야 합니다.
========================================
      `,
    }, null, 2),
  };
}
