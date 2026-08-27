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

// 테스트에서 쓰는 수수료·세율 (mock-data.ts 상수와 동일한 값)
const FEE_RATE    = 0.0001;   // 0.01%
const KR_TAX_RATE = 0.002;    // 0.2%
const RATE        = 1400;     // USD/KRW 환율 (고정 시나리오)

// ─────────────────────────────────────────────
// 국내(KRW) 매수
// ─────────────────────────────────────────────
describe('국내(KRW) 매수', () => {
  it('gross = qty * price (원 단위)', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.grossAmount).toBe(500_000);
  });

  it('fee = floor(gross * 0.0001) — HALF_UP 반올림', () => {
    // 10 * 50000 = 500,000 → fee = 500,000 * 0.0001 = 50.0 → 50
    const r = calculateOrderAmount({ side: '매수', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.fee).toBe(Math.round(500_000 * FEE_RATE));
  });

  it('매수 세금 = 0', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 5, price: 241500, currency: 'KRW', usdKrwRate: RATE });
    expect(r.tax).toBe(0);
  });

  it('netAmount(매수) = gross + fee', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.netAmount).toBe(r.grossAmount + r.fee);
  });

  it('HALF_UP 반올림 — fee 0.5 이상은 올림', () => {
    // 1주 * 10001원 = 10001 → fee = 10001 * 0.0001 = 1.0001 → 반올림 1
    const r = calculateOrderAmount({ side: '매수', quantity: 1, price: 10001, currency: 'KRW', usdKrwRate: RATE });
    expect(r.fee).toBe(1);
  });

  it('HALF_UP 반올림 — fee 정확히 0.5 → 올림(1)', () => {
    // 1주 * 5000원 = 5000 → fee = 5000 * 0.0001 = 0.5 → HALF_UP → 1
    const r = calculateOrderAmount({ side: '매수', quantity: 1, price: 5000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.fee).toBe(1);
  });
});

// ─────────────────────────────────────────────
// 국내(KRW) 매도
// ─────────────────────────────────────────────
describe('국내(KRW) 매도', () => {
  it('매도세 = round(gross * 0.002)', () => {
    // 10 * 50000 = 500,000 → tax = 500,000 * 0.002 = 1000
    const r = calculateOrderAmount({ side: '매도', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.tax).toBe(Math.round(500_000 * KR_TAX_RATE));
  });

  it('netAmount(매도) = gross - fee - tax', () => {
    const r = calculateOrderAmount({ side: '매도', quantity: 10, price: 50000, currency: 'KRW', usdKrwRate: RATE });
    expect(r.netAmount).toBe(r.grossAmount - r.fee - r.tax);
  });
});

// ─────────────────────────────────────────────
// 미국(USD) 매수
// ─────────────────────────────────────────────
describe('미국(USD) 매수', () => {
  it('grossUsd 센트 반올림 → KRW 환산 → 원 단위 반올림', () => {
    // 2주 * 182.456 → grossUsd = 364.91(센트 반올림) → KRW = 364.91 * 1400 = 510,874
    const r = calculateOrderAmount({ side: '매수', quantity: 2, price: 182.456, currency: 'USD', usdKrwRate: RATE });
    const grossUsd  = Math.round(2 * 182.456 * 100) / 100; // 364.91
    const expected  = Math.round(grossUsd * RATE);
    expect(r.grossAmount).toBe(expected);
  });

  it('feeUsd 센트 반올림 후 KRW 환산', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 2, price: 182.456, currency: 'USD', usdKrwRate: RATE });
    const grossUsd = Math.round(2 * 182.456 * 100) / 100;
    const feeUsd   = Math.round(grossUsd * FEE_RATE * 100) / 100;
    expect(r.fee).toBe(Math.round(feeUsd * RATE));
  });

  it('매수 세금 = 0', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 1, price: 100, currency: 'USD', usdKrwRate: RATE });
    expect(r.tax).toBe(0);
  });

  it('netAmount(매수) = gross + fee', () => {
    const r = calculateOrderAmount({ side: '매수', quantity: 3, price: 250, currency: 'USD', usdKrwRate: RATE });
    expect(r.netAmount).toBe(r.grossAmount + r.fee);
  });
});

// ─────────────────────────────────────────────
// 미국(USD) 매도 — SEC Fee
// ─────────────────────────────────────────────
describe('미국(USD) 매도 — SEC Fee', () => {
  it('SEC Fee = max(gross_usd * 0.0000206, 0.01) — 일반 케이스', () => {
    // 100주 * $200 = $20,000 → SEC = max(20000 * 0.0000206, 0.01) = max(0.412, 0.01) = 0.41 (센트 반올림)
    const r = calculateOrderAmount({ side: '매도', quantity: 100, price: 200, currency: 'USD', usdKrwRate: RATE });
    const grossUsd  = Math.round(100 * 200 * 100) / 100;
    const secRaw    = Math.round(grossUsd * 0.0000206 * 100) / 100;
    const secFeeUsd = Math.max(secRaw, 0.01);
    expect(r.tax).toBe(Math.round(secFeeUsd * RATE));
  });

  it('SEC Fee 최솟값 $0.01 — 매우 소액 거래', () => {
    // 1주 * $1 = $1 → gross * 0.0000206 = 0.0000206 → min($0.01) 적용
    const r = calculateOrderAmount({ side: '매도', quantity: 1, price: 1, currency: 'USD', usdKrwRate: RATE });
    const expectedTax = Math.round(0.01 * RATE); // $0.01 * 1400 = 14원
    expect(r.tax).toBe(expectedTax);
  });

  it('netAmount(매도) = gross - fee - tax', () => {
    const r = calculateOrderAmount({ side: '매도', quantity: 10, price: 150, currency: 'USD', usdKrwRate: RATE });
    expect(r.netAmount).toBe(r.grossAmount - r.fee - r.tax);
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
