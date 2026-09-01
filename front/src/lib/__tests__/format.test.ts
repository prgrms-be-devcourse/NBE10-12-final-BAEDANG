import { describe, it, expect } from 'vitest';
import {
  formatNumber,
  formatKoreanAmount,
  formatSigned,
  formatPercent,
  formatAbsolute,
  formatUsd,
  toKrw,
} from '../format';
import { D } from '../decimal';

describe('formatNumber', () => {
  it('1000 → "1,000"', () => {
    expect(formatNumber(1000)).toBe('1,000');
  });

  it('문자열 "1000" 지원', () => {
    expect(formatNumber('1000')).toBe('1,000');
  });

  it('Decimal 인스턴스 지원', () => {
    expect(formatNumber(new D(1000))).toBe('1,000');
  });

  it('0 → "0"', () => {
    expect(formatNumber(0)).toBe('0');
  });

  it('음수 → "-1,234"', () => {
    expect(formatNumber(-1234)).toBe('-1,234');
  });

  it('소수점은 HALF_UP 반올림 후 정수 포맷', () => {
    expect(formatNumber(1234.5)).toBe('1,235');
    expect(formatNumber(1234.4)).toBe('1,234');
    expect(formatNumber('1234.5')).toBe('1,235');
  });

  it('큰 수 — 100,000,000', () => {
    expect(formatNumber(100_000_000)).toBe('100,000,000');
  });

  it('지수 표기 범위의 큰 문자열도 전체 자릿수로 표시', () => {
    expect(formatNumber('1000000000000000000000')).toBe('1,000,000,000,000,000,000,000');
  });
});

describe('formatKoreanAmount', () => {
  it('1억 미만 — 일반 숫자 포맷', () => {
    expect(formatKoreanAmount(99_999_999)).toBe('99,999,999');
  });

  it('정확히 1억 → "1억"', () => {
    expect(formatKoreanAmount(100_000_000)).toBe('1억');
  });

  it('5억 2천만 → "5억"', () => {
    expect(formatKoreanAmount(520_000_000)).toBe('5억');
  });

  it('1조 → "1.00조"', () => {
    expect(formatKoreanAmount(1_000_000_000_000)).toBe('1.00조');
  });

  it('1.24조 → "1.24조"', () => {
    expect(formatKoreanAmount(1_240_000_000_000)).toBe('1.24조');
  });

  it('조 경계 바로 아래 (999,999,999,999) → 억 단위에 콤마 정상 적용 ("10,000억")', () => {
    expect(formatKoreanAmount(999_999_999_999)).toBe('10,000억');
  });

  it('음수 금액 지원 (-520,000,000 → "-5억", -1,240,000,000,000 → "-1.24조")', () => {
    expect(formatKoreanAmount(-520_000_000)).toBe('-5억');
    expect(formatKoreanAmount(-1_240_000_000_000)).toBe('-1.24조');
  });
});

describe('formatSigned', () => {
  it('양수 → "+" 접두사', () => {
    expect(formatSigned(1234)).toBe('+1,234');
  });

  it('음수 → "-" 부호 유지', () => {
    expect(formatSigned(-1234)).toBe('-1,234');
  });

  it('정수 0 → "±0"', () => {
    expect(formatSigned(0)).toBe('±0');
  });

  it('반올림 시 0이 되는 소수값(+0.4, -0.4) → "+0"이나 "-0" 대신 "±0"으로 일관 표기', () => {
    expect(formatSigned(0.4)).toBe('±0');
    expect(formatSigned(-0.4)).toBe('±0');
    expect(formatSigned('0.49')).toBe('±0');
  });

  it('반올림 시 1이 되는 소수값(+0.5) → "+1"', () => {
    expect(formatSigned(0.5)).toBe('+1');
  });
});

describe('formatPercent', () => {
  it('양수 → "+" 접두사 + 소수점 2자리 "%"', () => {
    expect(formatPercent(0.1234)).toBe('+12.34%');
  });

  it('음수 → "-" 부호 + 소수점 2자리 "%"', () => {
    expect(formatPercent(-0.0567)).toBe('-5.67%');
  });

  it('부동소수점 오차 방지 (0.07 * 100 → 정확히 "+7.00%")', () => {
    expect(formatPercent(0.07)).toBe('+7.00%');
    expect(formatPercent('0.07')).toBe('+7.00%');
  });

  it('0 → "0.00%"', () => {
    expect(formatPercent(0)).toBe('0.00%');
  });

  it('정확히 1% (0.01) → "+1.00%"', () => {
    expect(formatPercent(0.01)).toBe('+1.00%');
  });
});

describe('formatAbsolute', () => {
  it('절댓값을 소수점 둘째 자리에서 HALF_UP으로 표시', () => {
    expect(formatAbsolute('1.005')).toBe('1.01');
    expect(formatAbsolute('-1.005')).toBe('1.01');
  });

  it('비정상 입력 시 fallback 반환', () => {
    expect(formatAbsolute(null)).toBe('-');
  });
});

describe('formatUsd', () => {
  it('정수 → "$182.00"', () => {
    expect(formatUsd(182)).toBe('$182.00');
  });

  it('소수점 2자리 → "$182.40"', () => {
    expect(formatUsd(182.4)).toBe('$182.40');
  });

  it('소수점 3자리 HALF_UP 반올림 ("1.005" → "$1.01")', () => {
    // Decimal.js의 ROUND_HALF_UP으로 1.005가 정확히 $1.01로 반올림됨
    expect(formatUsd('1.005')).toBe('$1.01');
  });

  it('0 → "$0.00"', () => {
    expect(formatUsd(0)).toBe('$0.00');
  });
});

describe('toKrw', () => {
  it('KRW 종목은 환율과 무관하게 그대로 반환', () => {
    expect(toKrw('1000', 'KRW', 1370)?.toString()).toBe('1000');
  });

  it('USD 종목은 환율을 곱해서 반환', () => {
    expect(toKrw('10', 'USD', 1370)?.toNumber()).toBe(13700);
  });

  it('원가가 없으면(시세 미수집 등) null 반환 — 렌더 크래시 방지', () => {
    expect(toKrw(null, 'KRW', 1370)).toBeNull();
    expect(toKrw(undefined, 'USD', 1370)).toBeNull();
    expect(toKrw('', 'KRW', 1370)).toBeNull();
  });

  it('USD인데 환율이 없으면 1로 대체(원 단위 그대로) — 호출부가 항상 환율을 들고 있진 않을 수 있음', () => {
    expect(toKrw('10', 'USD', null)?.toNumber()).toBe(10);
  });
});

describe('비정상 입력(null / undefined / "" / NaN) 방어 (React 렌더 크래시 방지)', () => {
  it('formatNumber: null, undefined, "", NaN 입력 시 기본 fallback ("-") 반환 (예외 안 던짐)', () => {
    expect(formatNumber(null)).toBe('-');
    expect(formatNumber(undefined)).toBe('-');
    expect(formatNumber('')).toBe('-');
    expect(formatNumber(NaN)).toBe('-');
    expect(formatNumber(null, '0')).toBe('0');
  });

  it('formatUsd: null, undefined 입력 시 fallback ("-") 반환', () => {
    expect(formatUsd(null)).toBe('-');
    expect(formatUsd(undefined)).toBe('-');
    expect(formatUsd('', '$0.00')).toBe('$0.00');
  });

  it('formatKoreanAmount: null, undefined 입력 시 fallback ("-") 반환', () => {
    expect(formatKoreanAmount(null)).toBe('-');
    expect(formatKoreanAmount(undefined)).toBe('-');
  });

  it('formatSigned: null, undefined 입력 시 fallback ("-") 반환', () => {
    expect(formatSigned(null)).toBe('-');
    expect(formatSigned(undefined)).toBe('-');
  });

  it('formatPercent: null, undefined 입력 시 fallback ("-") 반환', () => {
    expect(formatPercent(null)).toBe('-');
    expect(formatPercent(undefined)).toBe('-');
  });
});

