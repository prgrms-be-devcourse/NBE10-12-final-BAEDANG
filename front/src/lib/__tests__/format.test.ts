import { describe, it, expect } from 'vitest';
import {
  formatNumber,
  formatKoreanAmount,
  formatSigned,
  formatPercent,
  formatUsd,
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

