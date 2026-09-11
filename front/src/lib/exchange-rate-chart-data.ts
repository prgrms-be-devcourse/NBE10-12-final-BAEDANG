import { TickMarkType, type Time, type UTCTimestamp } from "lightweight-charts";
import type { ExchangeRateHistoryItem, ExchangeRatePeriod } from "./api";

export type LinePoint = { time: UTCTimestamp; value: number };

/** 최신 점이 보이던 화면만 새 시각을 따라갑니다. 확대 폭과 과거 탐색 범위는 보존합니다. */
export function nextExchangeRateRange(
  range: { from: UTCTimestamp; to: UTCTimestamp },
  previousLast: UTCTimestamp | undefined,
  nextLast: UTCTimestamp,
): { from: UTCTimestamp; to: UTCTimestamp } {
  const shift = previousLast !== undefined && range.to >= previousLast
    ? Math.max(0, nextLast - previousLast) : 0;
  return { from: (range.from + shift) as UTCTimestamp, to: (range.to + shift) as UTCTimestamp };
}

/**
 * 기간별로 화면에 보여줄 데이터 간격(버킷 크기, 초 단위).
 *
 * <p>백엔드는 동일한 KST 자정 기준 버킷별 마지막 원본 한 건만 내려줍니다.
 * 여기서는 원본 validFrom을 버킷 시작 시각으로 매핑하여 차트 축을 정렬합니다.
 * 여러 값이 주어져도 마지막 원본을 고르는 처리는 동일하며 환율을 평균내지 않습니다.
 */
const BUCKET_SECONDS: Record<ExchangeRatePeriod, number> = {
  "1d": 60, // 1분
  "1w": 30 * 60, // 30분
  "1m": 2 * 60 * 60, // 2시간
  "3m": 6 * 60 * 60, // 6시간
  "1y": 24 * 60 * 60, // 1일
};
const KST_OFFSET_SECONDS = 9 * 60 * 60;

/** 분 단위 버킷("1일" 기간)에서만 축에 시:분까지 보여준다. 그 밖엔 날짜만으로 충분하다. */
export function isTimeVisible(period: ExchangeRatePeriod): boolean {
  return period === "1d";
}

/**
 * `{validFrom, rate}[]` → `lightweight-charts` LineSeries가 요구하는 숫자 포맷으로 변환하면서,
 * 기간에 맞는 버킷 단위로 다운샘플링한다.
 *
 * <p>같은 버킷에 여러 원본 값이 있으면 그 구간에서 가장 나중(최신) 값을 대표값으로 쓴다 —
 * 캔들의 "종가"와 같은 의미다. 백엔드가 이미 `validFrom` 오름차순으로 내려주지만
 * (`OrderByValidFromAsc`), 순서에 기대지 않고 각 버킷 안에서 원본 시각을 직접 비교해 결정한다.
 */
export function toLinePoints(items: ExchangeRateHistoryItem[], period: ExchangeRatePeriod): LinePoint[] {
  const bucketSeconds = BUCKET_SECONDS[period];
  const byBucket = new Map<UTCTimestamp, { rawTime: number; value: number }>();

  for (const item of items) {
    const rawTime = Math.floor(new Date(item.validFrom).getTime() / 1000);
    const value = Number(item.rate);
    if (!Number.isFinite(rawTime) || !Number.isFinite(value)) continue;

    const bucketTime = (Math.floor((rawTime + KST_OFFSET_SECONDS) / bucketSeconds)
      * bucketSeconds - KST_OFFSET_SECONDS) as UTCTimestamp;
    const existing = byBucket.get(bucketTime);
    if (!existing || rawTime >= existing.rawTime) {
      byBucket.set(bucketTime, { rawTime, value });
    }
  }

  return [...byBucket.entries()]
    .sort(([a], [b]) => a - b)
    .map(([time, { value }]) => ({ time, value }));
}

/**
 * `lightweight-charts`의 기본 축 눈금 표기는 "31일"·"9월"처럼 날짜 눈금과 월 눈금을
 * 따로 찍어서, 월이 바뀌는 지점이 아니면 몇 월인지 한눈에 알기 어렵다. 시:분 눈금을
 * 제외한 모든 날짜 눈금(연/월/일)을 "8월 31일"처럼 월+일을 항상 함께 보여주도록
 * 통일한다.
 */
export function formatTickMark(time: Time, tickMarkType: TickMarkType): string {
  if (typeof time !== "number") return String(time);
  const parts = kstParts(time);

  if (tickMarkType === TickMarkType.Time || tickMarkType === TickMarkType.TimeWithSeconds) {
    return `${parts.hour}:${parts.minute}`;
  }

  return `${Number(parts.month)}월 ${Number(parts.day)}일`;
}

const kstFormatter = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});

function kstParts(time: number): Record<string, string> {
  return Object.fromEntries(kstFormatter.formatToParts(new Date(time * 1000))
    .map(({ type, value }) => [type, value]));
}

/** 십자선 시간 라벨도 축과 동일한 KST로 표시합니다. 원본 UTC timestamp는 바꾸지 않습니다. */
export function formatCrosshairTime(time: Time, period: ExchangeRatePeriod): string {
  if (typeof time !== "number") return String(time);
  const parts = kstParts(time);
  const date = `${parts.year}-${parts.month}-${parts.day}`;
  return period === "1d" ? `${date} ${parts.hour}:${parts.minute}` : date;
}
