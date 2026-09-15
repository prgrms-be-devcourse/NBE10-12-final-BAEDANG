// 홍보 영상용 화면 녹화 스크립트. Playwright는 e2e 패키지 것을 재사용한다.
//   BASE_URL=https://... node recording/record.mjs login          로그인 상태 저장(직접 로그인)
//   BASE_URL=https://... node recording/record.mjs intro|shot3|shot4|shot5a|shot5b|all
//   node recording/record.mjs check                               샷 5 시나리오 숫자 일관성 검사
// 결과: recording/videos/<샷>-<시각>.webm
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdirSync, renameSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const dir = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000';
const PROFILE = path.join(dir, 'profile');
const VIDEOS = path.join(dir, 'videos');
const SIZE = { width: 1920, height: 1080 };
const TERM = process.env.WIKI_TERM ?? 'PBR'; // TERM은 셸이 이미 쓰는 변수라 피한다
const WARM_UP = { intro: '/intro' };

// ── 샷 5a·5b 연출 시나리오 ────────────────────────────────────────────────────
// 8/10에 5,000만원으로 시작해 국내 위주로 나눠 담아 온 계좌(4주 경과 → 성향 리포트 공개)가
// 오늘 삼성전자 10주를 지정가로 산다. 화면에 나오는 숫자는 전부 아래 표에서 계산한다.
const FX = 1360;
const INITIAL_CASH = 50_000_000;
const OPENED_AT = '2026-08-10 09:00';
const PORTFOLIO = [
  { symbol: '000660', name: 'SK하이닉스', market: 'KR', category: 'INDIVIDUAL', quantity: 6, avgPrice: 1512000, lastPrice: 1689000, boughtAt: '2026-08-10 09:12' },
  { symbol: '069500', name: 'KODEX 200', market: 'KR', category: 'ETF', quantity: 60, avgPrice: 101450, lastPrice: 104275, boughtAt: '2026-08-10 09:20' },
  { symbol: '034020', name: '두산에너빌리티', market: 'KR', category: 'INDIVIDUAL', quantity: 80, avgPrice: 79800, lastPrice: 86100, boughtAt: '2026-08-12 10:05' },
  { symbol: '360750', name: 'TIGER 미국S&P500', market: 'KR', category: 'ETF', quantity: 150, avgPrice: 24380, lastPrice: 25625, boughtAt: '2026-08-13 11:40' },
  { symbol: 'NVDA', name: '엔비디아', market: 'US', category: 'INDIVIDUAL', quantity: 20, avgPrice: 198.4, buyFx: 1352.1, lastPrice: 210.96, boughtAt: '2026-08-14 23:40' },
];
const ORDER = {
  symbol: '005930', name: '삼성전자', quantity: 10,
  basePrice: 247500, limitPrice: 248000, // 지정가 = 기준가 바로 위 매도 1호가 → 즉시 전량 체결
  laterPrice: 249500, // 5b 시점 현재가
  orderAt: process.env.MOCK_TIME ?? '10:30',
};
// 5a 배경에서 5초 폴링마다 오가는 현재가(기준가 ↔ 매수 1호가). 호가창은 기준가에 두고 잔량만 출렁여서
// 매도 1호가가 늘 지정가(248,000)와 같다 — 언제 주문을 넣어도 그 가격에 전량 체결돼 5b와 맞는다.
const PRICE_PATH = [247500, 247000];
const FILL_SECONDS = 8; // 5a에서 주문 버튼을 누르는 시점(시세 시각 orderAt:00 기준 경과 초)
const LIKES = [
  { stockId: 4750, symbol: '042700', name: '한미반도체', marketCountry: 'KR', prevClose: 226000, lastPrice: 222500 },
  { stockId: 7170, symbol: '108490', name: '로보티즈', marketCountry: 'KR', prevClose: 317500, lastPrice: 325500 },
  { stockId: 12069, symbol: 'TSLA', name: '테슬라', marketCountry: 'US', prevClose: 365.44, lastPrice: 358.97 },
  { stockId: 8153, symbol: 'AAPL', name: '애플', marketCountry: 'US', prevClose: 332.27, lastPrice: 333.08 },
];

const kst = (s) => new Date(`${s.replace(' ', 'T')}:00+09:00`).toISOString();
// 오늘 날짜의 KST 시각을 ISO(UTC)로.
function kstToday(hhmm, seconds = 0) {
  const [hh, mm] = hhmm.split(':').map(Number);
  const d = new Date(Date.now() + 9 * 3600_000);
  d.setUTCHours(hh, mm, seconds, 0);
  return new Date(d.getTime() - 9 * 3600_000).toISOString();
}
const byTime = (a, b) => Date.parse(a.boughtIso) - Date.parse(b.boughtIso);
// 국내 호가 단위(가격대별).
const krTick = (p) => (p < 2000 ? 1 : p < 5000 ? 5 : p < 20000 ? 10 : p < 50000 ? 50 : p < 200000 ? 100 : p < 500000 ? 500 : 1000);

// 계좌 상태를 한 번에 계산한다 — 요약·보유·주문·원장·리포트가 모두 여기서 나온다.
function buildAccount({ filled, samsungPrice }) {
  const positions = PORTFOLIO.map((p) => ({ ...p, boughtIso: kst(p.boughtAt), orderType: 'MARKET' }));
  if (filled) {
    positions.push({
      symbol: ORDER.symbol, name: ORDER.name, market: 'KR', category: 'INDIVIDUAL', quantity: ORDER.quantity,
      avgPrice: ORDER.limitPrice, lastPrice: samsungPrice, boughtIso: kstToday(ORDER.orderAt, FILL_SECONDS), orderType: 'LIMIT',
    });
  }
  for (const p of positions) {
    const usd = p.market === 'US';
    p.currency = usd ? 'USD' : 'KRW';
    p.buyFx = usd ? p.buyFx : 1;
    p.cost = Math.round(p.avgPrice * p.quantity * p.buyFx);
    p.fee = Math.round(p.cost * 0.0001);
    p.evalKrw = Math.round(p.lastPrice * p.quantity * (usd ? FX : 1));
    p.pnl = p.evalKrw - p.cost;
  }
  const sum = (list, f) => list.reduce((acc, p) => acc + f(p), 0);
  const cash = INITIAL_CASH - sum(positions, (p) => p.cost + p.fee);
  const stockValue = sum(positions, (p) => p.evalKrw);
  const costSum = sum(positions, (p) => p.cost);

  // 백엔드는 4주 창의 일별 원가 비중을 평균낸다. 4주 넘게 들고 있던 종목은 창 내내 같은 원가라
  // 그 원가 비중이 곧 평균이고, 오늘 산 종목은 평균에 거의 잡히지 않는다.
  const longHeld = positions.filter((p) => Date.parse(p.boughtIso) <= Date.now() - 28 * 86400_000).sort(byTime);
  const lhCost = sum(longHeld, (p) => p.cost);
  const share = (f) => sum(longHeld, (p) => (f(p) ? p.cost : 0)) / lhCost;
  const shares = {
    concentration: Math.max(...longHeld.map((p) => p.cost)) / lhCost,
    domestic: share((p) => p.market === 'KR'),
    individual: share((p) => p.category !== 'ETF'),
    aggressive: share((p) => p.leveraged),
  };
  const axes = [
    shares.concentration >= 0.5 ? ['C', '집중'] : ['D', '분산'],
    shares.domestic >= 0.5 ? ['K', '국내'] : ['G', '해외'],
    shares.individual >= 0.5 ? ['S', '개별주'] : ['E', 'ETF'],
    shares.aggressive >= 0.2 ? ['A', '공격'] : ['B', '안정'],
  ];
  return {
    positions, cash, stockValue, costSum, longHeld, shares,
    typeCode: axes.map(([l]) => l).join(''), typeLabel: `${axes.map(([, n]) => n).join('·')}형`,
  };
}

function orderItems(a) {
  return [...a.positions].sort(byTime).map((p, i) => ({
    orderId: i + 1, accountId: 1, stockId: i + 1, symbol: p.symbol, name: p.name, marketCountry: p.market,
    orderType: p.orderType, side: 'BUY', status: 'FILLED', quantity: String(p.quantity), filledQuantity: String(p.quantity),
    activeRemainingQuantity: '0', reservedCash: '0', grossAmount: String(p.cost), fee: String(p.fee), tax: '0',
    netAmount: String(p.cost + p.fee), orderedAt: p.boughtIso, closedAt: p.boughtIso,
    ...(p.orderType === 'LIMIT' ? {
      requestedLimitPrice: String(p.avgPrice), requestedLimitCurrency: 'KRW', limitPrice: String(p.avgPrice),
      acceptanceExchangeRate: '1', rejectReason: null, expiresAt: kstToday('15:30'),
    } : {}),
  })).reverse();
}

function ledgerItems(a) {
  let balance = INITIAL_CASH;
  const rows = [{ entryType: 'INITIAL_DEPOSIT', amount: INITIAL_CASH, balanceAfter: INITIAL_CASH, exchangeRate: '1', memo: '모의 투자금 지급', orderId: null, symbol: null, name: null, occurredAt: kst(OPENED_AT) }];
  [...a.positions].sort(byTime).forEach((p, i) => {
    balance -= p.cost + p.fee;
    rows.push({ entryType: 'BUY', amount: -(p.cost + p.fee), balanceAfter: balance, exchangeRate: String(p.buyFx), memo: `${p.name} ${p.quantity}주 @ ${p.avgPrice} (수수료 ${p.fee}원 포함)`, orderId: i + 1, symbol: p.symbol, name: p.name, occurredAt: p.boughtIso });
  });
  return rows.map((r, i) => ({ ...r, entryId: i + 1, amount: String(r.amount), balanceAfter: String(r.balanceAfter) })).reverse();
}

function reportBody(a) {
  const total = a.cash + a.stockValue;
  return {
    accountId: 1, roundNo: 1, locked: false, unlockAt: new Date(Date.parse(kst(OPENED_AT)) + 28 * 86400_000).toISOString(),
    initialCash: String(INITIAL_CASH), cashBalance: String(a.cash), stockValue: String(a.stockValue), totalAsset: String(total),
    totalPnl: String(total - INITIAL_CASH), returnRate: ((total - INITIAL_CASH) / INITIAL_CASH).toFixed(4),
    classified: a.positions.length >= 2, typeCode: a.typeCode, typeLabel: a.typeLabel,
    shares: Object.fromEntries(Object.entries(a.shares).map(([k, v]) => [k, v.toFixed(4)])),
    holdingCount: a.positions.length, holdingPeriodWeeks: 4,
    longHeldStocks: a.longHeld.map((p) => ({
      symbol: p.symbol, name: p.name, currency: p.currency, avgBuyPrice: String(p.avgPrice), lastPrice: String(p.lastPrice),
      returnRate: ((p.lastPrice - p.avgPrice) / p.avgPrice).toFixed(4), heldSince: p.boughtIso,
    })),
    asOf: new Date().toISOString(),
  };
}

async function smoothScroll(page, target, duration) {
  await page.evaluate(async ({ target, duration }) => {
    const y = target === 'bottom' ? document.documentElement.scrollHeight - innerHeight : target;
    const start = scrollY, t0 = performance.now();
    await new Promise((done) => {
      const step = (now) => {
        const p = Math.min((now - t0) / duration, 1);
        const ease = p < 0.5 ? 2 * p * p : 1 - (-2 * p + 2) ** 2 / 2;
        scrollTo(0, start + (y - start) * ease);
        p < 1 ? requestAnimationFrame(step) : done();
      };
      requestAnimationFrame(step);
    });
  }, { target, duration });
}

// 장 마감 시간에도 샷 5a·5b를 찍기 위한 응답 가로채기. 로그인·장 상태·시세·계좌·주문 응답을 시나리오대로
// 만들어 화면에 넘기고, 주문·계좌·관심 종목·인증 변경 요청은 서버로 절대 나가지 않는다 — 실제 로그인이 필요 없고
// 배포 서버 계좌도 바뀌지 않는다.
async function mockScenario(page, { filled }) {
  const state = { filled, samsungPrice: 0, detailCalls: 0, bookCalls: 0 };
  const startedAt = Date.now();
  // 시세 시각은 orderAt:00부터 실제 경과 시간만큼 흐른다 — 폴링마다 "기준" 시각이 바뀌어 보인다.
  const clock = () => new Date(Date.parse(kstToday(ORDER.orderAt)) + Date.now() - startedAt).toISOString();
  const now = () => buildAccount({ filled: state.filled, samsungPrice: state.samsungPrice || ORDER.laterPrice });
  const api = (re) => (url) => re.test(new URL(url).pathname);
  const json = (route, body) => route.fulfill({ json: body });

  // 먼저 등록한 라우트가 나중 것보다 우선순위가 낮다 — 목으로 처리하지 않은 변경 요청은 전부 차단.
  await page.route(api(/^\/api\/(orders|accounts|stocks\/likes|auth|users)/), (route) =>
    route.request().method() === 'GET' ? route.fallback() : route.abort());

  // 로그인도 흉내 낸다 — 새로고침 복원(refresh → users/me)만 성공시키면 화면은 로그인 상태가 된다.
  // 실제 계정·쿠키가 필요 없고, 이 토큰으로 가는 실서버 요청은 위에서 모두 목으로 처리한다.
  await page.route(api(/^\/api\/auth\/refresh$/), (route) => json(route, { accessToken: 'recording-demo-token' }));
  await page.route(api(/^\/api\/users\/me$/), (route) => json(route, { userId: 1, email: 'demo@investup.local', nickname: 'test' }));

  await page.route(api(/^\/api\/market\/status$/), async (route) => {
    const body = await (await route.fetch()).json();
    await json(route, { ...body, markets: body.markets.map((m) => ({ ...m, open: true })) });
  });
  await page.route(api(/^\/api\/exchange-rates\/latest$/), async (route) => {
    const body = await (await route.fetch()).json();
    await json(route, { ...body, rate: FX.toFixed(2) });
  });
  await page.route(api(/^\/api\/stocks\/[^/]+$/), async (route) => {
    const res = await route.fetch();
    const body = await res.json();
    if (!body.price) return route.fulfill({ response: res }); // /api/stocks/search 같은 목록 응답은 그대로
    let price = body.price;
    if (body.symbol === ORDER.symbol) {
      // 실시세 대신 PRICE_PATH를 5초 폴링마다 번갈아 보여준다.
      const last = PRICE_PATH[state.detailCalls++ % PRICE_PATH.length];
      const prevClose = Number(price.prevClose);
      price = { ...price, lastPrice: String(last), changeAmount: String(last - prevClose), changeRate: ((last - prevClose) / prevClose).toFixed(6) };
      state.samsungPrice = last;
    }
    await json(route, { ...body, tradable: true, tradableReason: null, price: { ...price, quoteAt: clock(), realtime: true } });
  });
  await page.route(api(/^\/api\/stocks\/likes$/), (route) => json(route, {
    items: LIKES.map((l, i) => ({
      ...l, stockLikeId: LIKES.length - i, prevClose: String(l.prevClose), lastPrice: String(l.lastPrice),
      changeRate: ((l.lastPrice - l.prevClose) / l.prevClose).toFixed(6),
    })),
    nextCursor: null, hasNext: false,
  }));
  await page.route(api(/^\/api\/stocks\/[^/]+\/orderbook$/), (route) => {
    const base = ORDER.basePrice;
    const tick = krTick(base);
    const n = ++state.bookCalls;
    // 3초 폴링마다 잔량이 조금씩 출렁인다(결정적이라 다시 찍어도 같은 모습).
    const level = (i, sign) => ({ level: i, price: String(base + sign * i * tick), quantity: String(40 + ((i * 37 + n * (sign > 0 ? 23 : 41) + i * n * 7) % 140)) });
    return json(route, {
      symbol: ORDER.symbol, marketCountry: 'KR', bookVersion: 1, revision: n, basePrice: String(base), currency: 'KRW',
      quoteAt: clock(), generatedAt: clock(), virtual: true, description: '',
      asks: [...Array(10)].map((_, i) => level(i + 1, 1)), bids: [...Array(10)].map((_, i) => level(i + 1, -1)),
    });
  });
  await page.route(api(/^\/api\/accounts\/me$/), (route) => {
    const a = now();
    return json(route, {
      accountId: 1, roundNo: 1, initialCash: String(INITIAL_CASH), cashBalance: String(a.cash), stockValue: String(a.stockValue),
      totalAsset: String(a.cash + a.stockValue), unrealizedPnl: String(a.stockValue - a.costSum),
      unrealizedPnlRate: ((a.stockValue - a.costSum) / a.costSum).toFixed(4), exchangeRate: FX.toFixed(2), asOf: new Date().toISOString(),
    });
  });
  await page.route(api(/^\/api\/accounts\/me\/holdings$/), (route) => json(route, {
    items: now().positions.map((p) => ({
      symbol: p.symbol, name: p.name, currency: p.currency, quantity: String(p.quantity), avgBuyPrice: String(p.avgPrice),
      avgExchangeRate: String(p.buyFx), lastPrice: String(p.lastPrice), evaluationAmount: String(p.evalKrw),
      unrealizedPnl: String(p.pnl), unrealizedPnlRate: (p.pnl / p.cost).toFixed(4), realtime: true,
    })),
    asOf: new Date().toISOString(),
  }));
  await page.route(api(/^\/api\/accounts\/me\/orders$/), (route) => json(route, { items: orderItems(now()), nextCursor: null, hasNext: false }));
  await page.route(api(/^\/api\/accounts\/me\/ledger$/), (route) => json(route, { items: ledgerItems(now()), nextCursor: null, hasNext: false }));
  await page.route(api(/^\/api\/reports\/me$/), (route) => json(route, reportBody(now())));
  await page.route(api(/^\/api\/orders\/quote\/limit$/), (route) => {
    const q = new URL(route.request().url()).searchParams;
    const quantity = Number(q.get('quantity'));
    const limit = Number(q.get('limitPrice'));
    const ask1 = ORDER.basePrice + krTick(ORDER.basePrice); // 호가창과 같은 기준
    const fills = limit >= ask1;
    const gross = limit * quantity;
    const fee = Math.round(gross * 0.0001);
    const fillGross = ask1 * quantity;
    const fillFee = Math.round(fillGross * 0.0001);
    return json(route, {
      requestedLimitPrice: String(limit), requestedLimitCurrency: 'KRW', limitPrice: String(limit), acceptanceExchangeRate: '1',
      acceptable: true, reason: null, availableCash: String(now().cash), availableQuantity: '0', expiresAt: kstToday('15:30'),
      limitEstimate: { grossAmount: String(gross), fee: String(fee), tax: '0', netAmount: String(gross + fee), reservedCash: String(gross + fee) },
      executionPreview: {
        status: 'AVAILABLE', reason: fills ? 'FILLED' : 'NO_LIQUIDITY', bookVersion: 1, revision: 1,
        quoteAt: clock(), generatedAt: clock(), evaluatedAt: clock(),
        expectedFilledQuantity: String(fills ? quantity : 0), remainingQuantity: String(fills ? 0 : quantity),
        avgExecutionPrice: fills ? String(ask1) : null, grossAmountKrw: fills ? String(fillGross) : null,
        feeKrw: fills ? String(fillFee) : null, taxKrw: fills ? '0' : null, netAmountKrw: fills ? String(fillGross + fillFee) : null,
        remainingReservedCash: '0', releasedCash: fills ? String(gross + fee - fillGross - fillFee) : '0',
      },
    });
  });
  await page.route(api(/^\/api\/orders\/limit$/), async (route) => {
    const body = route.request().postDataJSON();
    state.filled = true;
    await json(route, orderItems(now())[0]);
    console.log(`모의 지정가 체결(서버 미전송): ${body.symbol} ${body.quantity}주 @ ${body.limitPrice}`);
  });
}

const SHOTS = {
  // 인트로: 섹션별로 멈추며 스크롤 → 마지막 zoom 섹션은 스크롤 진행률 연출이라 천천히 → 시작하기 → /main
  async intro(page) {
    await page.goto('/intro');
    await page.mouse.move(SIZE.width - 20, SIZE.height / 2);
    await page.getByText('Enter 키를 누르면 SKIP이 가능합니다.', { exact: true }).waitFor();
    await page.waitForTimeout(3000);
    for (const id of ['practices', 'compare']) {
      await smoothScroll(page, await page.locator(`#${id}`).evaluate((el) => el.offsetTop), 1800);
      await page.waitForTimeout(2000);
    }
    await smoothScroll(page, 'bottom', 8000);
    const cta = page.getByRole('link', { name: '시작하기', exact: true });
    await cta.evaluate((el) => new Promise((done) => {
      const check = () => (getComputedStyle(el).opacity === '1' ? done() : requestAnimationFrame(check));
      check();
    }));
    await page.waitForTimeout(1500);
    await cta.hover();
    await page.waitForTimeout(800);
    await cta.click();
    await page.waitForURL('**/main');
    await page.mouse.move(SIZE.width - 20, SIZE.height / 2);
    await page.waitForTimeout(3500);
  },
  // 샷 3: 랭킹 테이블 위→아래 스크롤 → 국내↔해외 탭 전환
  async shot3(page) {
    await page.goto('/rankings');
    // 반응형이라 종목명 텍스트는 숨은 모바일 복제본이 먼저 잡힌다 — 탭 버튼으로 로딩을 기다린다.
    await page.getByRole('button', { name: '국내 주식', exact: true }).click();
    await page.getByText('거래대금', { exact: false }).locator('visible=true').first().waitFor();
    await page.waitForTimeout(2500);
    // 커서가 표 위에 남으면 종목 미리보기 카드가 계속 떠 있다 — 여백으로 치운다.
    await page.mouse.move(SIZE.width - 20, SIZE.height / 2);
    await smoothScroll(page, 1600, 3000);
    await page.waitForTimeout(800);
    await smoothScroll(page, 0, 1500);
    await page.waitForTimeout(800);
    await page.getByRole('button', { name: '해외 주식', exact: true }).click();
    await page.waitForTimeout(3000);
    await page.getByRole('button', { name: '국내 주식', exact: true }).click();
    await page.waitForTimeout(3000);
  },
  // 샷 4: 용어 위키 → 전체 리스트 노출 → 검색어 한 글자씩 → 풀이 카드
  async shot4(page) {
    await page.goto('/guide');
    await page.getByRole('button', { name: '금융 용어 위키', exact: true }).click();
    const search = page.getByPlaceholder('용어·별칭·초성(ㅅㄱ)으로 검색');
    await search.waitFor();
    await page.waitForTimeout(2500);
    await search.click();
    await page.waitForTimeout(500);
    await search.pressSequentially(TERM, { delay: 280 });
    await page.waitForTimeout(1500);
    await page.getByRole('button', { name: new RegExp(TERM) }).first().click();
    await page.getByRole('dialog').waitFor();
    await page.waitForTimeout(4000);
  },
  // 샷 5a: 삼성전자 상세 → 지정가 → 가격·수량 입력 → 즉시 체결 예상 미리보기 → 주문 접수
  async shot5a(page) {
    // 15초 안에 끝낸다 — 현재가(5초)·호가(3초)는 배경에서 계속 바뀌고, 기다리지 않고 바로 주문한다.
    await mockScenario(page, { filled: false });
    await page.goto(`/stocks/${ORDER.symbol}?marketCountry=KR`);
    const qty = page.locator('[data-tour="quantity"] input');
    await qty.waitFor();
    await page.mouse.move(SIZE.width - 20, SIZE.height / 2);
    await page.waitForTimeout(3000);
    await page.getByRole('button', { name: '지정가', exact: true }).click();
    await page.waitForTimeout(400);
    await page.getByRole('button', { name: '매수', exact: true }).click();
    await page.waitForTimeout(700);
    // 지정가를 먼저 넣는다 — 가격이 비어 있으면 최대 매수 수량이 0이라 수량 입력이 0으로 깎인다.
    // 지정가 칸은 현재가로 미리 채워지므로 비우고 입력한다.
    const price = page.getByPlaceholder('예: 72000');
    await price.click();
    await price.fill('');
    await price.pressSequentially(String(ORDER.limitPrice), { delay: 180 });
    await page.waitForTimeout(400);
    await qty.click();
    await qty.fill('');
    await qty.pressSequentially(String(ORDER.quantity), { delay: 300 });
    await page.getByText('호가 기준 즉시 체결 예상').waitFor();
    await page.getByText('예상 동결 예수금').hover();
    await page.waitForTimeout(2800);
    await page.getByRole('button', { name: '매수 주문 접수', exact: true }).click();
    await page.getByRole('heading', { name: '주문이 접수됐어요', exact: true }).waitFor();
    await page.waitForTimeout(2600);
  },
  // 샷 5b: 5a 체결 이후의 마이페이지 — 요약·보유 종목 → 주문 내역 → 체결 내역 → 관심 종목 → 투자 성향 리포트
  async shot5b(page) {
    await mockScenario(page, { filled: true });
    await page.goto('/my');
    await page.getByRole('heading', { name: '내 계좌' }).waitFor();
    await page.getByText(ORDER.name, { exact: false }).locator('visible=true').first().waitFor();
    await page.locator('img[src*="/personality-types/"]').first().waitFor();
    // 15초 안에 끝낸다.
    await page.mouse.move(SIZE.width - 20, SIZE.height / 2);
    await page.waitForTimeout(2600);
    // 탭을 누르면 목록을 다시 불러와 빈 상자가 잠깐 보인다 — 목록이 그려진 뒤부터 시간을 잰다.
    for (const [tab, marker] of [['주문 내역', '체결완료'], ['체결 내역', '초기지급'], ['관심 종목', LIKES[0].name]]) {
      await page.getByRole('button', { name: tab, exact: true }).click();
      await page.mouse.move(SIZE.width - 20, SIZE.height / 2);
      await page.getByText(marker).locator('visible=true').first().waitFor(); // 관심 종목 행은 종목명과 다른 글자가 한 요소에 섞여 있어 부분 일치로 찾는다
      await page.waitForTimeout(1900);
    }
    const reportTop = await page.getByText('투자 성향 리포트', { exact: true }).locator('visible=true').first()
      .evaluate((el) => el.getBoundingClientRect().top + scrollY - 90);
    // 리포트 머리부터 4주 이상 보유 종목 표까지 한 화면에 들어온다 — 더 내리면 페이지 끝의 초기화·탈퇴 영역이 잡힌다.
    await smoothScroll(page, reportTop, 1400);
    await page.waitForTimeout(3400);
  },
};

// refresh 토큰이 매번 회전(RTR)해서 storageState 스냅샷은 한 번 쓰면 무효가 된다.
// 프로필 폴더를 유지해 회전된 쿠키가 다음 실행에도 이어지게 한다.
const launch = (extra = {}) => {
  const { chromium } = createRequire(import.meta.url)('../e2e/node_modules/@playwright/test');
  return chromium.launchPersistentContext(PROFILE, {
    headless: false, baseURL: BASE_URL, viewport: SIZE, deviceScaleFactor: 1, locale: 'ko-KR', timezoneId: 'Asia/Seoul', ...extra,
  });
};

async function login() {
  const context = await launch();
  const page = context.pages()[0] ?? await context.newPage();
  await page.goto('/login');
  console.log('브라우저에서 직접 로그인하세요. 로그인되면 자동으로 저장합니다.');
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 300_000 });
  await page.goto('/my');
  await page.getByRole('heading', { name: '내 계좌' }).waitFor();
  await context.close();
  console.log(`저장: ${PROFILE}`);
}

async function record(name) {
  if (!existsSync(PROFILE)) throw new Error('먼저 `node recording/record.mjs login` 으로 로그인하세요.');
  mkdirSync(VIDEOS, { recursive: true });
  // 첫 방문은 이미지·스크립트 로딩 중에 등장 애니메이션이 재생돼 끊겨 찍힌다 — 녹화 없이 한 번 열어 캐시를 채운다.
  if (WARM_UP[name]) {
    const warm = await launch();
    const warmPage = warm.pages()[0] ?? await warm.newPage();
    await warmPage.goto(WARM_UP[name], { waitUntil: 'networkidle' });
    await warm.close();
  }
  // 창이 다른 창에 가려지면 Chromium이 페인트를 멈춰 영상이 정지 화면이 된다(headless는 아예 프레임이 안 온다).
  const context = await launch({
    recordVideo: { dir: VIDEOS, size: SIZE },
    args: ['--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding', '--disable-background-timer-throttling'],
  });
  // 종목 상세 첫 진입 투어 팝업이 영상에 끼지 않게 한다.
  await context.addInitScript(() => localStorage.setItem('stockDetailTourSeen_v1', '1'));
  const page = context.pages()[0] ?? await context.newPage();
  try {
    await SHOTS[name](page);
  } finally {
    const video = page.video();
    await context.close();
    const out = path.join(VIDEOS, `${name}-${new Date().toISOString().replace(/[:.]/g, '-')}.webm`);
    renameSync(await video.path(), out);
    console.log(`녹화: ${out}`);
  }
}

// 샷 5 시나리오가 화면끼리 어긋나지 않는지 확인한다(원장 잔액 = 예수금, 5a 전후 예수금 차이 = 주문 금액, 성향 판정).
function check() {
  const before = buildAccount({ filled: false, samsungPrice: ORDER.basePrice });
  const after = buildAccount({ filled: true, samsungPrice: ORDER.laterPrice });
  for (const a of [before, after]) assert.equal(Number(ledgerItems(a)[0].balanceAfter), a.cash);
  const samsung = orderItems(after)[0];
  assert.equal(samsung.symbol, ORDER.symbol);
  assert.equal(before.cash - after.cash, Number(samsung.netAmount));
  assert.equal(after.longHeld.length, PORTFOLIO.length);
  assert.equal(after.typeCode, 'DKSB');
  const report = reportBody(after);
  assert.equal(Number(report.totalAsset), after.cash + after.stockValue);
  console.log({
    cashBefore: before.cash, cashAfter: after.cash, stockValue: after.stockValue, totalAsset: report.totalAsset,
    returnRate: report.returnRate, typeCode: report.typeCode, typeLabel: report.typeLabel, shares: report.shares,
  });
}

const arg = process.argv[2];
if (arg === 'check') check();
else if (arg === 'login') await login();
else if (arg === 'all') for (const name of Object.keys(SHOTS)) await record(name);
else if (SHOTS[arg]) await record(arg);
else console.log('사용법: node recording/record.mjs check | login | intro | shot3 | shot4 | shot5a | shot5b | all');
