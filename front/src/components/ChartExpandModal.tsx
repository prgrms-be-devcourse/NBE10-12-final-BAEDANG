"use client";

import { Tag } from "./Tag";
import { CandleChartSection, type CandlePeriod, type CandleUnit } from "./CandleChartSection";
import type { Candle } from "@/lib/api";

/**
 * 종목 상세 화면의 "차트 크게보기" 버튼을 누르면 뜨는 확대보기 모달.
 *
 * <p>토글 상태(`candleUnit`/`period`)는 `StockDetailClient`가 그대로 들고 있는 걸
 * 그대로 넘겨받는다 — 모달 안에서 기간을 바꾸면 닫은 뒤에도 그 선택이 유지되는 게
 * 자연스럽고, 상태를 이중으로 관리할 필요도 없어진다.
 */
export function ChartExpandModal({
  name,
  symbol,
  market,
  candleUnit,
  onCandleUnitChange,
  period,
  onPeriodChange,
  candleItems,
  candleLoading,
  theme,
  lastCandleAt,
  onClose,
}: {
  name: string;
  symbol: string;
  market: string;
  candleUnit: CandleUnit;
  onCandleUnitChange: (value: CandleUnit) => void;
  period: CandlePeriod;
  onPeriodChange: (value: CandlePeriod) => void;
  candleItems: Candle[];
  candleLoading: boolean;
  theme: "light" | "dark";
  lastCandleAt: string | null;
  onClose: () => void;
}) {
  return (
    <div
      className="fixed inset-0 z-[150] flex items-center justify-center px-4"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
      onClick={onClose}
    >
      <div
        className="w-full min-w-0 max-w-[920px] rounded-[24px] p-6.5"
        style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <h3 className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-[18px] font-bold" style={{ color: "var(--ink)" }}>
            <span className="truncate">{name}</span>
            <Tag>{symbol}</Tag>
            <span className="text-[13px] font-normal" style={{ color: "var(--mut2)" }}>{market}</span>
          </h3>
          <button
            onClick={onClose}
            className="shrink-0 rounded-full px-3 py-1.5 text-[13px] font-semibold"
            style={{ background: "var(--fill)", color: "var(--mut)" }}
            aria-label="닫기"
          >
            닫기
          </button>
        </div>

        <CandleChartSection
          candleUnit={candleUnit}
          onCandleUnitChange={onCandleUnitChange}
          period={period}
          onPeriodChange={onPeriodChange}
          candleItems={candleItems}
          candleLoading={candleLoading}
          theme={theme}
          lastCandleAt={lastCandleAt}
          chartHeight={520}
        />
      </div>
    </div>
  );
}
