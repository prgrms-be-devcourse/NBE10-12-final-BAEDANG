// 이슈 #70 후속 — 다수 유저 × N종목 동시 매수/매도 레이턴시.
//
// 목적: 유저마다 자기 계좌 행만 잠그는 현실적 쓰기 부하. 단일계좌(orders.js)의 직렬화 상한과 대비 —
//       교차 경합이 없으면 읽기처럼 확장하는지 실측한다.
//
// 전제: disposable 테스트 유저/계좌를 미리 시드하고(user_id==account_id, [ACCOUNT_MIN..ACCOUNT_MAX]),
//       각 계좌가 STOCKS 전 종목을 대량 보유(매도 성공 보장). 끝나면 전부 삭제.
//       AUTH_ENABLED=false → X-User-Id 헤더로 유저 지정, accountId 는 그 유저 소유여야 함.
//
// 실행은 PowerShell(Git Bash는 /scripts 경로 MSYS 변환됨). env: ACCOUNT_MIN/MAX·STOCKS·ORDER_VUS·SELL_RATE.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080/api';
const A_MIN = Number(__ENV.ACCOUNT_MIN || 2);
const A_MAX = Number(__ENV.ACCOUNT_MAX || 51);
const STOCKS = (__ENV.STOCKS || '').split(',').filter(Boolean);
const MARKET = __ENV.ORDER_MARKET || 'KR';
const QTY = __ENV.ORDER_QTY || '1';
const VUS = Number(__ENV.ORDER_VUS || 200);
const THINK = Number(__ENV.ORDER_THINK || 0.5); // 유저별 think-time(초). 0이면 최대 부하.
const SELL_RATE = Number(__ENV.SELL_RATE || 0.5);

function uuidv4() {
  const hex = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) s += '-';
    else if (i === 14) s += '4';
    else if (i === 19) s += hex[(Math.random() * 4 | 0) + 8];
    else s += hex[Math.random() * 16 | 0];
  }
  return s;
}
function randInt(min, max) { return min + Math.floor(Math.random() * (max - min + 1)); }

export const options = {
  scenarios: {
    multi: {
      executor: 'ramping-vus', startVUs: 0,
      stages: [
        { duration: __ENV.S1 || '45s', target: Math.max(1, Math.round(VUS * 0.25)) },
        { duration: __ENV.S2 || '45s', target: Math.max(1, Math.round(VUS * 0.5)) },
        { duration: __ENV.S3 || '45s', target: VUS },
        { duration: __ENV.HOLD || '1m', target: VUS },
        { duration: __ENV.DOWN || '20s', target: 0 },
      ],
      gracefulRampDown: '5s', exec: 'trade',
    },
  },
  thresholds: {
    'http_req_duration{name:place_order}': ['p(95)<500'], // 관측용(gate 아님, abortOnFail 없음)
  },
};

export function setup() {
  if (STOCKS.length === 0) throw new Error('STOCKS env 비어있음');
  return {};
}

export function trade() {
  // 유저마다 다른 계좌 → 각자 자기 행만 락. u==a.
  const id = randInt(A_MIN, A_MAX);
  const sym = STOCKS[Math.floor(Math.random() * STOCKS.length)];
  const side = Math.random() < SELL_RATE ? 'SELL' : 'BUY';
  const body = JSON.stringify({
    accountId: id, clientOrderId: uuidv4(),
    symbol: sym, marketCountry: MARKET, side, quantity: QTY,
  });
  const res = http.post(`${BASE_URL}/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': String(id) },
    tags: { name: 'place_order', side },
  });
  check(res, { 'order 201': (r) => r.status === 201, 'not 5xx': (r) => r.status < 500 });
  if (THINK > 0) sleep(THINK);
}
