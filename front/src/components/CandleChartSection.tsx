"use client";

import { PillTabs } from "./PillTabs";
import { CandlestickChart } from "./CandlestickChart";
import { CHART_TIME_ZONE } from "@/lib/candle-chart-data";
import { CANDLE_UNIT_DEFAULT_PERIOD, CANDLE_UNIT_PERIODS, type CandlePeriod, type CandleUnit } from "@/lib/candle-query";
import type { Candle } from "@/lib/api";

export type { CandlePeriod, CandleUnit };

/**
 * `lastCandleAt`은 백엔드가 ISO 8601 문자열로 내려준다고 가정하지만, 형식이
 * 어긋나거나 빈 문자열이 오면 `new Date(...)`가 Invalid Date를 만들어
 * `toLocaleDateString`이 "Invalid Date" 같은 값을 그대로 보여줄 수 있다.
 * 파싱에 실패하면 null을 돌려줘서 호출부가 그 부분을 통째로 생략하게 한다.
 *
 * <p>타임존을 지정하지 않으면 뷰어의 브라우저/OS 타임존을 따라가 버려서 사람마다
 * 다른 날짜가 보일 수 있다 — 캔들 차트 자체(`CandlestickChart`)와 마찬가지로
 * `docs/erd.md`가 정의한 거래일 경계(KST)에 맞춰 `Asia/Seoul`로 고정한다.
 */
function formatLastCandleDate(iso: string): string | null {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString("ko-KR", { month: "2-digit", day: "2-digit", timeZone: CHART_TIME_ZONE });
}

/**
 * 종목 상세의 캔들차트 영역(일봉/1분봉 + 기간 토글 + 차트)을 따로 뺀 컴포넌트.
 *
 * <p>기본 화면(`StockDetailClient`)과 확대보기 모달(`ChartExpandModal`)이 완전히 같은
 * 마크업을 공유해야 해서(토글 상태도 같이 공유 — 모달에서 기간을 바꾸면 닫은 뒤에도
 * 유지되는 게 자연스럽다) 별도 컴포넌트로 뺐다. 상태(`candleUnit`/`period`)는
 * `StockDetailClient`가 들고 있고 여기서는 표시·전환만 담당한다.
 */
export function CandleChartSection({
  candleUnit,
  onCandleUnitChange,
  period,
  onPeriodChange,
  candleItems,
  candleLoading,
  theme,
  lastCandleAt,
  chartHeight = 260,
  onExpand,
  tourIds,
}: {
  candleUnit: CandleUnit;
  onCandleUnitChange: (value: CandleUnit) => void;
  period: CandlePeriod;
  onPeriodChange: (value: CandlePeriod) => void;
  candleItems: Candle[];
  candleLoading: boolean;
  theme: "light" | "dark";
  lastCandleAt: string | null;
  /** 차트 높이(px). 확대보기 모달에서는 더 크게 넘긴다. */
  chartHeight?: number;
  /** "차트 크게보기" 버튼 클릭 핸들러. 넘기지 않으면 버튼 자체를 숨긴다(모달 안에서는 불필요). */
  onExpand?: () => void;
  /** 첫 사용자 안내 투어(`TourGuide`)가 짚어야 할 요소에 붙일 `data-tour` 값들.
   * 확대보기 모달에서는 넘기지 않아 투어 대상에서 제외된다. */
  tourIds?: { toggle?: string; chart?: string; expandButton?: string };
}) {
  const trackStyle = {
    background: theme === "dark" ? "rgba(255,255,255,.03)" : "rgba(15,56,104,.06)",
    border: theme === "dark" ? "1px solid rgba(255,255,255,.06)" : "1px solid rgba(15,56,104,.12)",
  };
  const lastCandleDateLabel = lastCandleAt ? formatLastCandleDate(lastCandleAt) : null;
  const availablePeriods = CANDLE_UNIT_PERIODS[candleUnit];
  // 봉 단위마다 고를 수 있는 기간이 하나뿐이면(1분봉→1일, 10분봉→1주일) 토글 자체가
  // 무의미하므로 숨긴다 — 기존 1분봉 처리와 같은 규칙을 5개 단위 전체로 일반화한 것.
  const hasPeriodChoice = availablePeriods.length > 1;
  // 일봉·1주봉은 "지난 기록을 훑어보는" 차트라 마지막 봉 날짜(종가 기준)를 같이
  // 보여주는 게 유용하지만, 분봉 계열은 "최근 N봉" 표기가 더 직관적이다.
  const showsLastCandleDate = candleUnit === "일봉" || candleUnit === "1주봉";

  return (
    <>
      <div className="my-4.5 flex flex-wrap items-center gap-2.5" data-tour={tourIds?.toggle}>
        <PillTabs
          options={[
            { value: "1분봉", label: "1분봉" },
            { value: "5분봉", label: "5분봉" },
            { value: "10분봉", label: "10분봉" },
            { value: "일봉", label: "일봉" },
            { value: "1주봉", label: "1주봉" },
          ]}
          value={candleUnit}
          onChange={(v) => {
            const nextUnit = v as CandleUnit;
            onCandleUnitChange(nextUnit);
            // 봉 단위를 바꾸면 이전 기간이 새 단위에서 유효하지 않을 수 있다(예: 일봉의
            // "1년"은 5분봉엔 없다) — 백엔드가 허용하는 조합(CandleQueryPolicy)에 맞춰
            // 그 단위의 기본 기간으로 되돌린다.
            onPeriodChange(CANDLE_UNIT_DEFAULT_PERIOD[nextUnit]);
          }}
          trackClassName="w-fit rounded-full p-[3px]"
          trackStyle={trackStyle}
          buttonClassName="rounded-full px-4 py-1.5 text-[13.5px] font-bold"
          inactiveTextStyle={{ color: "var(--mut)" }}
        />
        {hasPeriodChoice && (
          <PillTabs
            options={availablePeriods.map((p) => ({ value: p, label: p }))}
            value={period}
            onChange={(v) => onPeriodChange(v as CandlePeriod)}
            trackClassName="w-fit rounded-full p-[3px]"
            trackStyle={trackStyle}
            buttonClassName="rounded-full px-3.5 py-1.5 text-[13px] font-bold"
            inactiveTextStyle={{ color: "var(--mut)" }}
          />
        )}
        <span className="ml-auto text-[12.5px]" style={{ color: "var(--mut2)" }}>
          {hasPeriodChoice
            ? `${candleUnit} · ${period}${showsLastCandleDate && lastCandleDateLabel ? ` · ${lastCandleDateLabel} 종가까지` : ""}`
            : `${candleUnit} · 최근 ${candleItems.length}봉`}
        </span>
        {onExpand && (
          <button
            type="button"
            onClick={onExpand}
            className="chart-expand-btn flex cursor-pointer items-center gap-1 rounded-full px-3 py-1.5 text-[12.5px] font-bold transition-colors duration-150"
            aria-label="차트 크게보기"
            data-tour={tourIds?.expandButton}
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round">
              <path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7" />
            </svg>
            차트 크게보기
          </button>
        )}
      </div>

      <div className="mb-4 overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }} data-tour={tourIds?.chart}>
        {candleLoading ? (
          <div className="flex items-center justify-center" style={{ height: chartHeight }}>
            <span className="text-[13px]" style={{ color: "var(--mut2)" }}>차트를 불러오는 중…</span>
          </div>
        ) : candleItems.length >= 2 ? (
          <CandlestickChart items={candleItems} theme={theme} height={chartHeight} />
        ) : (
          <div className="flex items-center justify-center" style={{ height: chartHeight }}>
            <span className="text-[13px]" style={{ color: "var(--mut2)" }}>차트 데이터가 아직 없어요</span>
          </div>
        )}
      </div>
    </>
  );
}
