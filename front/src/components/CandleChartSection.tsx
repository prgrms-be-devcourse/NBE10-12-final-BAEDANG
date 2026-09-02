"use client";

import { PillTabs } from "./PillTabs";
import { CandlestickChart } from "./CandlestickChart";
import type { Candle } from "@/lib/api";

export type CandleUnit = "일봉" | "1분봉";
export type CandlePeriod = "1개월" | "6개월" | "1년";

/**
 * `lastCandleAt`은 백엔드가 ISO 8601 문자열로 내려준다고 가정하지만, 형식이
 * 어긋나거나 빈 문자열이 오면 `new Date(...)`가 Invalid Date를 만들어
 * `toLocaleDateString`이 "Invalid Date" 같은 값을 그대로 보여줄 수 있다.
 * 파싱에 실패하면 null을 돌려줘서 호출부가 그 부분을 통째로 생략하게 한다.
 */
function formatLastCandleDate(iso: string): string | null {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString("ko-KR", { month: "2-digit", day: "2-digit" });
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

  return (
    <>
      <div className="my-4.5 flex flex-wrap items-center gap-2.5" data-tour={tourIds?.toggle}>
        <PillTabs
          options={[
            { value: "일봉", label: "일봉" },
            { value: "1분봉", label: "1분봉" },
          ]}
          value={candleUnit}
          onChange={(v) => onCandleUnitChange(v as CandleUnit)}
          trackClassName="w-fit rounded-full p-[3px]"
          trackStyle={trackStyle}
          buttonClassName="rounded-full px-4 py-1.5 text-[13.5px] font-bold"
          inactiveTextStyle={{ color: "var(--mut)" }}
        />
        {candleUnit === "일봉" && (
          <PillTabs
            options={[
              { value: "1개월", label: "1개월" },
              { value: "6개월", label: "6개월" },
              { value: "1년", label: "1년" },
            ]}
            value={period}
            onChange={(v) => onPeriodChange(v as CandlePeriod)}
            trackClassName="w-fit rounded-full p-[3px]"
            trackStyle={trackStyle}
            buttonClassName="rounded-full px-3.5 py-1.5 text-[13px] font-bold"
            inactiveTextStyle={{ color: "var(--mut)" }}
          />
        )}
        <span className="ml-auto text-[12.5px]" style={{ color: "var(--mut2)" }}>
          {candleUnit === "일봉"
            ? `일봉 · ${period}${lastCandleDateLabel ? ` · ${lastCandleDateLabel} 종가까지` : ""}`
            : `1분봉 · 최근 ${candleItems.length}봉`}
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
