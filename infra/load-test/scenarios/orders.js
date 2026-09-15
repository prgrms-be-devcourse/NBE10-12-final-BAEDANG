// 이슈 #70 후속 — 쓰기(시장가 매수) 경로 동시성 테스트.
//
// 목적: 한 계좌(ACCOUNT_ID)에 다수 VU가 동시에 매수를 걸어 `account FOR UPDATE`
//       락이 요청을 직렬화하는 "계좌당 처리량 상한"을 관측한다. 읽기 테스트(main.js)와의 대비가 산출물.
//
// ⚠️ 이건 현실에 없는 합성 최악 케이스다. 실 트래픽은 유저마다 다른 계좌라
//    각자 자기 행만 락 → 교차 경합 0 → 읽기처럼 확장한다. 이 수치는 "시스템 상한"이 아니라
//    "계좌당 직렬화 상한"으로 읽어야 한다.
//
// 실행(PowerShell — Git Bash는 /scripts 경로가 MSYS 변환됨):
//   docker run --rm --network common --add-host host.docker.internal:host-gateway `
//     -v "<repo>/infra/load-test/scenarios:/scripts" `
//     -e K6_PROMETHEUS_RW_SERVER_URL=http://trading-prometheus:9090/api/v1/write `
//     -e BASE_URL=http://host.docker.internal:8080/api `
//     grafana/k6 run -o experimental-prometheus-rw --tag testid=write-... /scripts/orders.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080/api';
const ACCOUNT_ID = Number(__ENV.ACCOUNT_ID || 1);
const USER_ID = __ENV.USER_ID || '1';
// 초저가 KR 랭킹 종목(예: 252670 KODEX 200선물인버스2X ≈ 81원)이라 5천만으로
// ~60만 건까지 100% 체결된다 → 잔고 소진/거절 노이즈 없이 락 직렬화된 전체 쓰기 경로만 측정.
const SYMBOL = __ENV.ORDER_SYMBOL || '252670';
const MARKET = __ENV.ORDER_MARKET || 'KR';
const QTY = __ENV.ORDER_QTY || '1';
const VUS = Number(__ENV.ORDER_VUS || 80);

// clientOrderId 는 매 요청 새 UUID 여야 한다(재사용 시 멱등 응답 → 체결이 아니라 replay 측정).
// jslib 네트워크 의존을 피해 로컬 RFC4122 v4 생성기를 쓴다.
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

export const options = {
  // think-time 없이 단계적으로 VU를 올려 "어느 부하에서 처리량이 꺾이고 커넥션 타임아웃이
  // 튀는지"를 육안 판독한다(gate 없음, abortOnFail 없음).
  scenarios: {
    orders_ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.ORDER_S1 || '30s', target: Math.max(1, Math.round(VUS * 0.125)) },
        { duration: __ENV.ORDER_S2 || '30s', target: Math.max(1, Math.round(VUS * 0.25)) },
        { duration: __ENV.ORDER_S3 || '30s', target: Math.max(1, Math.round(VUS * 0.5)) },
        { duration: __ENV.ORDER_S4 || '45s', target: VUS },
        { duration: __ENV.ORDER_HOLD || '45s', target: VUS },
        { duration: __ENV.ORDER_DOWN || '15s', target: 0 },
      ],
      gracefulRampDown: '5s',
      exec: 'placeOrder',
    },
  },
};

export function placeOrder() {
  const body = JSON.stringify({
    accountId: ACCOUNT_ID,
    clientOrderId: uuidv4(),
    symbol: SYMBOL,
    marketCountry: MARKET,
    side: 'BUY',
    quantity: QTY,
  });
  const res = http.post(`${BASE_URL}/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'X-User-Id': USER_ID },
    tags: { name: 'place_order' },
  });
  // 201=체결. 5xx=풀+락 상호작용(커넥션 타임아웃 3s) 브레이킹 시그니처. 4xx=검증 거절(잔고 등).
  check(res, {
    'order 201': (r) => r.status === 201,
    'not 5xx': (r) => r.status < 500,
  });
}
