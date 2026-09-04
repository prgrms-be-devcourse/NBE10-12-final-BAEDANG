import { TickMarkType, type Time, type UTCTimestamp } from "lightweight-charts";
import type { Candle } from "@/lib/api";

/**
 * 차트 데이터는 백엔드 거래일 경계 정의(`docs/erd.md`의 `daily_candle` — "KST 날짜로
 * 변환, UTC로 자르면 미국 종목 날짜가 하루 밀린다")와 일치시키기 위해 항상 KST(Asia/Seoul)
 * 기준으로 표시한다. 뷰어의 브라우저/OS 타임존에 맡기면(기본 `Intl`/`Date` 동작) 사람마다
 * 다른 날짜가 보이고, 문서에 명시된 것과 같은 종류의 하루 밀림 문제도 재현될 수 있다.
 */
export const CHART_TIME_ZONE = "Asia/Seoul";

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

/**
 * `lightweight-charts`는 데이터가 `time` 기준 엄격한 오름차순이어야 하고, 시각이
 * 중복되면 "Value is not strictly increasing" 예외를 던지며 렌더링을 통째로
 * 멈춘다. 백엔드가 오름차순·중복 없이 내려준다는 걸 이미 테스트로 확인해뒀지만,
 * API 계약이 나중에 바뀌거나 응답이 뒤섞여 오는 경우까지 프론트에서 방어해야
 * 화면이 죽지 않는다(제미나이 코드 리뷰, PR #82).
 *
 * <p>같은 시각이 중복되면 먼저 온 값을 우선한다 — 백엔드
 * {@code TossMarketDataAdapter.fetchCandles}의 {@code putIfAbsent}와 같은 규칙이다.
 */
function sortAndDedupeByTime<T extends { time: UTCTimestamp }>(points: T[]): T[] {
  const byTime = new Map<UTCTimestamp, T>();
  for (const point of points) {
    if (!byTime.has(point.time)) {
      byTime.set(point.time, point);
    }
  }
  return [...byTime.values()].sort((a, b) => a.time - b.time);
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
  return sortAndDedupeByTime(points);
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
  return sortAndDedupeByTime(points);
}

const tickFormatCache = new Map<string, Intl.DateTimeFormat>();

/** `Intl.DateTimeFormat` 인스턴스 생성 비용을 아끼려고 옵션 조합별로 하나씩만 만들어 재사용한다. */
function getTickFormat(options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = JSON.stringify(options);
  let format = tickFormatCache.get(key);
  if (!format) {
    format = new Intl.DateTimeFormat("ko-KR", { ...options, timeZone: CHART_TIME_ZONE });
    tickFormatCache.set(key, format);
  }
  return format;
}

/**
 * `CandlestickChart`의 `timeScale.tickMarkFormatter`로 넘기는 KST 고정 포맷터.
 * 우리 데이터는 항상 `UTCTimestamp`(초)라 `BusinessDay`/문자열 `Time`은 고려하지 않는다.
 */
export function formatKstTickMark(time: Time, tickMarkType: TickMarkType): string {
  const date = new Date((time as UTCTimestamp) * 1000);
  switch (tickMarkType) {
    case TickMarkType.Year:
      return getTickFormat({ year: "numeric" }).format(date);
    case TickMarkType.Month:
      return getTickFormat({ month: "short" }).format(date);
    case TickMarkType.DayOfMonth:
      return getTickFormat({ month: "2-digit", day: "2-digit" }).format(date);
    case TickMarkType.TimeWithSeconds:
      return getTickFormat({ hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false }).format(date);
    case TickMarkType.Time:
    default:
      return getTickFormat({ hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
  }
}
