import type { UTCTimestamp } from "lightweight-charts";
import type { Candle } from "@/lib/api";

/**
 * 백엔드 캔들 응답(`Candle[]`, 금액이 전부 문자열)을 `lightweight-charts`가 요구하는
 * 숫자 기반 데이터로 변환한다. 차트 라이브러리 자체는 렌더링(캔버스)이라 유닛 테스트로
 * 검증하기 어렵지만, 이 변환 로직은 순수 함수라 여기만 따로 떼어 테스트한다(이슈 #76).
 *
 * <p>시간은 초 단위 유닉스 타임스탬프로 통일한다 — 일봉·1분봉을 같은 컴포넌트가
 * 같이 다루는데, `lightweight-charts`의 `BusinessDay` 형식은 시각 정보가 없어
 * 1분봉에는 못 쓰지만 타임스탬프는 두 경우 모두에 쓸 수 있다.
 */

export type CandlestickPoint = {
  time: UTCTimestamp;
  open: number;
  high: number;
  low: number;
  close: number;
};

export type VolumePoint = {
  time: UTCTimestamp;
  value: number;
  color: string;
};

function toUnixSeconds(at: string): UTCTimestamp {
  return Math.floor(new Date(at).getTime() / 1000) as UTCTimestamp;
}

/** `candle.at`이 파싱되지 않거나(NaN) OHLC 중 하나라도 숫자가 아니면 그 봉은 건너뛴다 — 차트 라이브러리는 잘못된 값이 하나만 섞여도 전체를 그리다 멈춘다. */
function isValidPoint(time: number, open: number, high: number, low: number, close: number): boolean {
  return [time, open, high, low, close].every((n) => Number.isFinite(n));
}

export function toCandlestickData(items: Candle[]): CandlestickPoint[] {
  const points: CandlestickPoint[] = [];
  for (const item of items) {
    const time = toUnixSeconds(item.at);
    const open = Number(item.open);
    const high = Number(item.high);
    const low = Number(item.low);
    const close = Number(item.close);
    if (!isValidPoint(time, open, high, low, close)) continue;
    points.push({ time, open, high, low, close });
  }
  return points;
}

/**
 * 거래량 바 색상은 그 봉의 상승/하락(종가-시가)에 맞춘다 — 캔들 색과 통일해야
 * 한눈에 같은 흐름으로 읽힌다.
 */
export function toVolumeData(items: Candle[], upColor: string, downColor: string): VolumePoint[] {
  const points: VolumePoint[] = [];
  for (const item of items) {
    const time = toUnixSeconds(item.at);
    const open = Number(item.open);
    const close = Number(item.close);
    const volume = Number(item.volume);
    if (!Number.isFinite(time) || !Number.isFinite(volume)) continue;
    const color = Number.isFinite(open) && Number.isFinite(close) && close < open ? downColor : upColor;
    points.push({ time, value: Math.max(0, volume), color });
  }
  return points;
}
