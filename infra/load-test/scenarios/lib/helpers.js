// 이슈 #70 — k6 부하 테스트 공통 헬퍼
//   심볼 하드코딩 금지: rankings 응답에서 심볼을 파생한다(설계 §4-2).
//   rankings 는 market=KR|US 로 호출하므로 한 페이지의 모든 종목은 그 market 에 속한다.
//   → marketCountry 는 응답 item 이 아니라 "요청한 market" 에서 얻는다.
import http from 'k6/http';
import { check } from 'k6';

// 기본값은 호스트에서 직접 띄운 앱(로컬 부하 테스트)을 가리킨다.
// 앱을 network `common` 컨테이너(back)로 배포했다면 BASE_URL=http://back_1:8080/api 로 덮어쓴다.
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080/api';

// KR 우선 + US 혼합(설계 §8-3). 0.0~1.0.
export const MARKET_KR_WEIGHT = Number(__ENV.MARKET_KR_WEIGHT || 0.7);

// rankings 커서 순회 페이지 수(기본 5 = 상위 100건).
const RANKINGS_PAGES = Number(__ENV.RANKINGS_PAGES || 5);

export function pickMarket() {
  return Math.random() < MARKET_KR_WEIGHT ? 'KR' : 'US';
}

export function pickRandom(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// 한 market 의 rankings 를 커서로 순회하며 심볼을 모은다.
// tagName 으로 부하 요청/워밍업을 구분해 대시보드에서 분해할 수 있다.
export function collectSymbols(market, pages = RANKINGS_PAGES, tagName = 'rankings_page') {
  const symbols = [];
  let cursor = null;
  for (let i = 0; i < pages; i++) {
    const url =
      `${BASE_URL}/stocks/rankings?market=${market}&size=20` +
      (cursor ? `&cursor=${encodeURIComponent(cursor)}` : '');
    const res = http.get(url, { tags: { name: tagName } });
    const ok = check(res, {
      'rankings 200': (r) => r.status === 200,
    });
    if (!ok) break;

    let body;
    try {
      body = res.json();
    } catch (e) {
      break;
    }
    const items = (body && body.items) || [];
    for (const it of items) {
      if (it && it.symbol) {
        // marketCountry 는 item 이 아니라 요청 market 에서 파생(item.market 은 KOSPI 등 거래소명).
        symbols.push({ symbol: it.symbol, marketCountry: market });
      }
    }
    if (!body || !body.hasNext || !body.nextCursor) break;
    cursor = body.nextCursor;
  }
  return symbols;
}

// stock detail — 파생 심볼로만 호출한다(랭킹 종목 → DB/캐시 응답 보장, 라이브 Toss 회피).
export function getDetail(pick) {
  return http.get(
    `${BASE_URL}/stocks/${encodeURIComponent(pick.symbol)}?marketCountry=${pick.marketCountry}`,
    { tags: { name: 'stock_detail' } },
  );
}

export function getMarketStatus() {
  return http.get(`${BASE_URL}/market/status`, { tags: { name: 'market_status' } });
}
