/**
 * calculateOrderAmount 테스트
 *
 * AGENTS.md · docs/erd.md 반올림 규칙:
 *   - 국내: gross(원 단위 HALF_UP) → fee(gross * 0.01% HALF_UP) → 매도세(gross * 0.2% HALF_UP)
 *   - 미국: USD 센트 반올림 → KRW 환산 → 원 단위 HALF_UP
 *   - SEC Fee: max(gross_usd * 0.0000206, $0.01) → KRW 환산 → 원 단위 HALF_UP
 */
import { describe, it, expect } from 'vitest';
import { calculateOrderAmount } from '../order-amount';

// 테스트에서 쓰는 고정 환율 시나리오
const RATE = 1400; // USD/KRW

// ─────────────────────────────────────────────
// 국내(KRW) 매수
// ─────────────────────────────────────────────
describe('국내(KRW) 매수', () => {
  it('gross = qty * price (원 단위)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.grossAmount).toBe(500_000);
  });

  it('fee = floor(gross * 0.0001) — HALF_UP 반올림 (500,000 * 0.0001 = 50원)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.fee).toBe(50);
  });

  it('매수 세금 = 0', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 5, price: 241500, currency: 'KRW', usdKrwRate: RATE });
    expect(r.tax).toBe(0);
  });

  it('netAmount(매수) = gross + fee (500,000 + 50 = 500,050원)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.netAmount).toBe(500_050);
  });

  it('HALF_UP 반올림 — fee 0.5 이상은 올림 (10,001 * 0.0001 = 1.0001원 -> 1원)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 1, price: 10001, currency: 'KRW', usdKrwRate: RATE });
    expect(r.fee).toBe(1);
  });

  it('HALF_UP 반올림 — fee 정확히 0.5 -> 올림 (5,000 * 0.0001 = 0.5원 -> 1원)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 1, price: 5000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.fee).toBe(1);
  });
});

// ─────────────────────────────────────────────
// 국내(KRW) 매도
// ─────────────────────────────────────────────
describe('국내(KRW) 매도', () => {
  it('매도세 = round(gross * 0.002) (500,000 * 0.002 = 1,000원)', () => {
    const r = calculateOrderAmount({ side: '매도', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.tax).toBe(1_000);
  });

  it('netAmount(매도) = gross - fee - tax (500,000 - 50 - 1,000 = 498,950원)', () => {
    const r = calculateOrderAmount({ side: '매도', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.netAmount).toBe(498_950);
  });
});

// ─────────────────────────────────────────────
// 미국(USD) 매수
// ─────────────────────────────────────────────
describe('미국(USD) 매수', () => {
  it('grossUsd 센트 반올림 -> KRW 환산 -> 원 단위 반올림 (2 * 182.456 = $364.912 -> $364.91 * 1,400 = 510,874원)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 2, price: 182.456, currency: 'USD', usdKrwRate: RATE });
    expect(r.grossAmount).toBe(510_874);
  });

  it('feeUsd 센트 반올림 후 KRW 환산 ($364.91 * 0.0001 = $0.036491 -> $0.04 * 1,400 = 56원)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 2, price: 182.456, currency: 'USD', usdKrwRate: RATE });
    expect(r.fee).toBe(56);
  });

  it('매수 세금 = 0', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 1, price: 100, currency: 'USD', usdKrwRate: RATE });
    expect(r.tax).toBe(0);
  });

  it('netAmount(매수) = gross + fee (750 * 1,400 + fee)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 3, price: 250, currency: 'USD', usdKrwRate: RATE });
    // $750.00 * 1400 = 1,050,000원 / fee: $750 * 0.0001 = $0.08 * 1400 = 112원
    expect(r.grossAmount).toBe(1_050_000);
    expect(r.fee).toBe(112);
    expect(r.netAmount).toBe(1_050_112);
  });
});

// ─────────────────────────────────────────────
// 미국(USD) 매도 — SEC Fee
// ─────────────────────────────────────────────
describe('미국(USD) 매도 — SEC Fee', () => {
  it('SEC Fee = max(gross_usd * 0.0000206, 0.01) — 일반 케이스 ($20,000 * 0.0000206 = $0.412 -> $0.41 * 1,400 = 574원)', () => {
    const r = calculateOrderAmount({ side: '매도', quantity: 100, price: 200, currency: 'USD', usdKrwRate: RATE });
    expect(r.tax).toBe(574);
  });

  it('SEC Fee 최솟값 $0.01 — 매우 소액 거래 ($1 * 0.0000206 -> min $0.01 * 1,400 = 14원)', () => {
    const r = calculateOrderAmount({ side: '매도', quantity: 1, price: 1, currency: 'USD', usdKrwRate: RATE });
    expect(r.tax).toBe(14);
  });

  it('netAmount(매도) = gross - fee - tax (10 * $150 = $1,500 * 1,400 = 2,100,000원)', () => {
    const r = calculateOrderAmount({ side: '매도', quantity: 10, price: 150, currency: 'USD', usdKrwRate: RATE });
    // gross: $1500 * 1400 = 2,100,000원
    // fee: $1500 * 0.0001 = $0.15 * 1400 = 210원
    // SEC Fee: max($1500 * 0.0000206, 0.01) = max($0.0309, 0.01) = $0.03 * 1400 = 42원
    // net: 2,100,000 - 210 - 42 = 2,099,748원
    expect(r.grossAmount).toBe(2_100_000);
    expect(r.fee).toBe(210);
    expect(r.tax).toBe(42);
    expect(r.netAmount).toBe(2_099_748);
  });
});

// ─────────────────────────────────────────────
// 엣지 케이스
// ─────────────────────────────────────────────
describe('엣지 케이스', () => {
  it('수량 0 — 모든 결과 0', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 0, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.grossAmount).toBe(0);
    expect(r.fee).toBe(0);
    expect(r.tax).toBe(0);
    expect(r.netAmount).toBe(0);
  });

  it('가격 0 — 모든 결과 0', () => {
    const r = calculateOrderAmount({ side: '매도', quantity: 5, price: 0, currency: 'KRW', usdKrwRate: RATE });
    expect(r.grossAmount).toBe(0);
    expect(r.fee).toBe(0);
    expect(r.tax).toBe(0);
    expect(r.netAmount).toBe(0);
  });
});
