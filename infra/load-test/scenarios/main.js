// 이슈 #70 — k6 부하 테스트 시나리오 (rankings → detail → market/status 상관 분석용)
//
// 실행 전제(설계 §0-0):
//   - 앱은 TOSS_ENABLED=true + 시드(#84) 웜업 DB 로 미리 기동되어 있어야 한다.
//   - cron 수집기(마스터·랭킹·prev-close·정기 일봉)와 겹치지 않는 시간대에 실행한다.
//   - 대상 심볼은 rankings 응답에서 파생(하드코딩 금지) → 랭킹 종목은 DB/캐시 응답.
//
// 4 시나리오는 startTime 으로 순차 배치(한 실행에서 겹치지 않음, 설계 §4-3).
//   RUN_SCENARIO=load 처럼 지정하면 그 시나리오 하나만 실행한다.
//
// remote-write 로 Prometheus 에 push (설계 §4-5):
//   k6 run \
//     -o experimental-prometheus-rw \
//     --tag testid=$(date +%s) \
//     scenarios/main.js
//   환경변수: K6_PROMETHEUS_RW_SERVER_URL, K6_PROMETHEUS_RW_TREND_STATS (README 참고)
import { check, sleep } from 'k6';
import {
  collectSymbols,
  pickMarket,
  pickRandom,
  getDetail,
  getMarketStatus,
} from './lib/helpers.js';

// VU 목표치는 env 로 스케일(부하 강도 조절). 기본값은 로컬/dev 에서 무난한 수준.
const LOAD_VUS = Number(__ENV.LOAD_VUS || 50);
const STRESS_VUS = Number(__ENV.STRESS_VUS || 300);
const SPIKE_VUS = Number(__ENV.SPIKE_VUS || 400);

// load 시나리오 구간 지속시간(env로 조절 — 짧은 런/CI용). 기본은 정식 부하.
const LOAD_RAMP = __ENV.LOAD_RAMP || '1m';
const LOAD_HOLD = __ENV.LOAD_HOLD || '3m';
const LOAD_RAMPDOWN = __ENV.LOAD_RAMPDOWN || '30s';

const ALL_SCENARIOS = {
  // 스모크: 경로·응답 sanity. 하드 gate.
  smoke: {
    executor: 'constant-vus',
    vus: 1,
    duration: '30s',
    startTime: '0s',
    gracefulStop: '5s',
    tags: { scenario: 'smoke' },
    exec: 'browse',
  },
  // 정상 부하: 기대 트래픽 SLA 검증. 하드 gate.
  load: {
    executor: 'ramping-vus',
    startVUs: 0,
    startTime: '40s',
    gracefulRampDown: '10s',
    stages: [
      { duration: LOAD_RAMP, target: LOAD_VUS },
      { duration: LOAD_HOLD, target: LOAD_VUS },
      { duration: LOAD_RAMPDOWN, target: 0 },
    ],
    tags: { scenario: 'load' },
    exec: 'browse',
  },
  // 스트레스: 한계까지 ramp. gate 아님(브레이킹 포인트 육안 판독, 설계 §4-4).
  stress: {
    executor: 'ramping-vus',
    startVUs: 0,
    startTime: '6m',
    gracefulRampDown: '10s',
    stages: [
      { duration: '2m', target: STRESS_VUS },
      { duration: '2m', target: STRESS_VUS },
      { duration: '1m', target: 0 },
    ],
    tags: { scenario: 'stress' },
    exec: 'browse',
  },
  // 스파이크: 급증→급감 회복력. gate 아님.
  spike: {
    executor: 'ramping-vus',
    startVUs: 0,
    startTime: '12m',
    gracefulRampDown: '10s',
    stages: [
      { duration: '20s', target: SPIKE_VUS },
      { duration: '1m', target: SPIKE_VUS },
      { duration: '20s', target: 0 },
    ],
    tags: { scenario: 'spike' },
    exec: 'browse',
  },
};

// RUN_SCENARIO 로 단일 시나리오만 실행(startTime 0 으로 앞당김).
function selectScenarios() {
  const only = __ENV.RUN_SCENARIO;
  if (!only) return ALL_SCENARIOS;
  const picked = ALL_SCENARIOS[only];
  if (!picked) throw new Error(`알 수 없는 RUN_SCENARIO: ${only}`);
  return { [only]: { ...picked, startTime: '0s' } };
}

export const options = {
  scenarios: selectScenarios(),
  // threshold 는 시나리오 태그로 스코프한다(설계 §4-4):
  //   smoke/load 만 하드 gate. stress/spike 는 gate 없이 관측 전용.
  //   abortOnFail 을 쓰지 않아 한계 구간에서 조기 종료되지 않는다.
  thresholds: {
    'checks{scenario:smoke}': ['rate>0.99'],
    'http_req_failed{scenario:smoke}': ['rate<0.01'],
    'http_req_duration{scenario:smoke}': ['p(95)<300'],
    'http_req_failed{scenario:load}': ['rate<0.01'],
    'http_req_duration{scenario:load}': ['p(95)<500'],
    // 엔드포인트별 세분화(load 구간).
    'http_req_duration{scenario:load,name:rankings_page}': ['p(95)<500'],
    'http_req_duration{scenario:load,name:stock_detail}': ['p(95)<600'],
    'http_req_duration{scenario:load,name:market_status}': ['p(95)<200'],
  },
};

// setup: 심볼 풀을 rankings 에서 워밍업(1회). VU 들이 detail 대상으로 재사용.
export function setup() {
  const kr = collectSymbols('KR', undefined, 'setup_rankings');
  const us = collectSymbols('US', undefined, 'setup_rankings');
  if (kr.length === 0 && us.length === 0) {
    throw new Error(
      'rankings 에서 심볼을 얻지 못했습니다. 앱 기동/시드 웜업(#84)/BASE_URL 을 확인하세요.',
    );
  }
  return { KR: kr, US: us };
}

// 사용자 흐름: 랭킹 브라우징 → 종목 상세 → 시장 상태 확인.
export function browse(data) {
  const market = pickMarket();
  const pool = (data && data[market]) || [];

  // 1) 랭킹 리스트 열람(첫 페이지) — 실제 부하 요청.
  const listMarket = pool.length > 0 ? market : (data.KR.length > 0 ? 'KR' : 'US');
  const listSymbols = collectSymbols(listMarket, 1, 'rankings_page');
  check(listSymbols, { 'rankings 심볼 존재': (s) => s.length > 0 });

  // 2) 풀에서 심볼을 골라 상세 조회(랭킹 종목 → DB/캐시 응답).
  const candidates = pool.length > 0 ? pool : data.KR.concat(data.US);
  if (candidates.length > 0) {
    const pick = pickRandom(candidates);
    const detail = getDetail(pick);
    check(detail, { 'detail 200': (r) => r.status === 200 });
  }

  // 3) 시장 상태(가볍고 캐시성).
  const status = getMarketStatus();
  check(status, { 'market/status 200': (r) => r.status === 200 });

  sleep(1); // think time
}

// 단일 exec 이름을 시나리오가 공유하므로 default 는 두지 않는다.
