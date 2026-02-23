import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

/**
 * k6 선착순 정합성 검증 테스트
 *
 * 흐름:
 * 1. POST /participation       → 200 (선착순 통과) or 400 (재고 소진)
 * 2. 200이면 sleep(2초)        → Consumer가 PENDING → SUCCESS 확정할 시간
 * 3. GET /result 폴링 (최대 3회) → PENDING / SUCCESS 확인
 *
 * 프론트 화면 시뮬레이션:
 * 200 → "처리 중입니다" 스피너
 * 400 → "마감됐습니다" 팝업
 * SUCCESS → "쿠폰이 발급됐습니다!" 팝업
 */

const BASE_URL = 'http://localhost:8080';

// 커스텀 메트릭
const acceptedCount = new Counter('participation_accepted');   // 선착순 통과 (200)
const rejectedCount = new Counter('participation_rejected');   // 재고 소진 (400)
const confirmedCount = new Counter('participation_confirmed'); // 최종 SUCCESS 확정
const pendingCount = new Counter('participation_still_pending'); // 폴링 후에도 PENDING

export const options = {
  stages: [
    { duration: '1s', target: 100 },
    { duration: '2s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'],
  },
};

export function setup() {
  console.log('========================================');
  console.log('🚀 선착순 정합성 검증 테스트 시작');
  console.log('========================================');
  console.log('테스트 시나리오:');
  console.log('  - 동시 요청: 100명');
  console.log('  - 예상 재고: 50개');
  console.log('  - 흐름: 참여 요청 → 결과 폴링 (프론트 시뮬레이션)');
  console.log('========================================\n');

  const campaignsRes = http.get(`${BASE_URL}/api/admin/campaigns`);

  if (campaignsRes.status === 200) {
    try {
      const body = JSON.parse(campaignsRes.body);
      const campaigns = body.data || [];

      if (campaigns.length > 0) {
        const campaign = campaigns[0];
        console.log(`📋 테스트 대상 캠페인:`);
        console.log(`   ID: ${campaign.id}`);
        console.log(`   이름: ${campaign.name}`);
        console.log(`   현재 재고: ${campaign.currentStock}/${campaign.totalStock}`);
        console.log('========================================\n');
        return { campaignId: campaign.id };
      }
    } catch (err) {
      console.error('캠페인 정보 파싱 실패:', err);
    }
  }

  console.warn('⚠️  캠페인을 찾을 수 없습니다. 기본 ID=1 사용');
  return { campaignId: 1 };
}

export default function (data) {
  const campaignId = data.campaignId;
  const userId = 10000 + __VU; // 10001~10100 (고유한 사용자 ID)

  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  // ── STEP 1. 선착순 참여 요청 ──────────────────────────────
  const participateRes = http.post(
    `${BASE_URL}/api/campaigns/${campaignId}/participation`,
    JSON.stringify({ userId }),
    params
  );

  const participateBody = JSON.parse(participateRes.body);

  check(participateRes, {
    '참여 요청: 200 or 400': (r) => r.status === 200 || r.status === 400,
    '참여 요청: ApiResponse 형식': (r) => participateBody.success !== undefined,
  });

  // ── STEP 2. 재고 소진이면 즉시 종료 ──────────────────────
  if (participateRes.status === 400) {
    rejectedCount.add(1);
    console.log(`[User ${userId}] 🚫 재고 소진 → "${participateBody.message}"`);
    return;
  }

  // ── STEP 3. 선착순 통과 → 결과 폴링 (프론트 스피너 시뮬레이션) ──
  acceptedCount.add(1);
  console.log(`[User ${userId}] ✅ 선착순 통과 → "${participateBody.message}"`);

  // Consumer가 PENDING → SUCCESS 확정할 시간 대기 (프론트 스피너)
  sleep(2);

  // 최대 3회 폴링 (2초 간격)
  let finalStatus = 'PENDING';
  for (let i = 1; i <= 3; i++) {
    const resultRes = http.get(
      `${BASE_URL}/api/campaigns/${campaignId}/participation/${userId}/result`,
      params
    );

    check(resultRes, {
      '결과 조회: 200': (r) => r.status === 200,
    });

    if (resultRes.status === 200) {
      const resultBody = JSON.parse(resultRes.body);
      finalStatus = resultBody.data.status;

      console.log(`[User ${userId}] 📊 결과 조회 ${i}회차 → ${finalStatus}: "${resultBody.data.message}"`);

      if (finalStatus === 'SUCCESS') {
        confirmedCount.add(1);
        console.log(`[User ${userId}] 🎉 최종 확정: 쿠폰 발급 완료!`);
        break;
      }
    }

    // 아직 PENDING이면 2초 후 재시도
    if (i < 3) sleep(2);
  }

  // 3회 폴링 후에도 PENDING이면 카운트
  if (finalStatus === 'PENDING') {
    pendingCount.add(1);
    console.log(`[User ${userId}] ⏳ 3회 폴링 후에도 PENDING (Consumer 처리 지연)`);
  }
}

export function teardown(data) {
  console.log('\n========================================');
  console.log('✅ 정합성 검증 테스트 완료');
  console.log('========================================');
  console.log('커스텀 메트릭 확인:');
  console.log('  - participation_accepted:      선착순 통과 수 (재고 수와 일치해야 함)');
  console.log('  - participation_rejected:      재고 소진 수');
  console.log('  - participation_confirmed:     최종 SUCCESS 확정 수');
  console.log('  - participation_still_pending: 폴링 후에도 PENDING (Consumer 지연)');
  console.log('========================================');
  console.log('DB 직접 확인:');
  console.log('  SELECT status, COUNT(*) FROM participation_history GROUP BY status;');
  console.log('========================================');
  console.log('예상 결과:');
  console.log('  - accepted = 재고 수 (예: 50)');
  console.log('  - rejected = 나머지 (예: 50)');
  console.log('  - confirmed = accepted와 동일 (모두 SUCCESS 확정)');
  console.log('  - still_pending = 0 (정상 처리 시)');
  console.log('========================================\n');
}

export function handleSummary(data) {
  return {
    'stdout': textSummary(data, { indent: '  ', enableColors: true }),
  };
}
