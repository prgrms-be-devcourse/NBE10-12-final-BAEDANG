"use client";

import { useEffect, useState, type ReactNode } from "react";
import { ApiError, getStockFinancials, type MarketCountry, type StockFinancialPeriod, type StockFinancials } from "@/lib/api";
import { chartPeriods, formatCalculatedPer, recentPeriods, type FinancialRange } from "@/lib/financial-view";
import { formatNumber } from "@/lib/format";
import { PillTabs } from "./PillTabs";
import { useTheme } from "./ThemeProvider";

/** 재무 비율은 이미 "몇 %"로 계산돼 내려온다 — formatPercent(×100)를 쓰지 않는다. */
function formatRatioPercent(value: string | null): string {
  const n = value === null ? NaN : Number(value);
  return Number.isFinite(n) ? `${n.toFixed(2)}%` : "-";
}

function formatWon(value: string | null | undefined): string {
  return value == null ? "-" : `${formatNumber(value)}원`;
}

function formatStatementMonth(yyyymm: string): string {
  if (yyyymm.length !== 6) return yyyymm;
  return `${yyyymm.slice(0, 4)}.${yyyymm.slice(4, 6)}`;
}

function toNumber(value: string | null | undefined): number | null {
  if (value == null || value.trim() === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

type ChartKind = "performance" | "profitability" | "position";

/**
 * 종목 상세의 재무제표 섹션 — 종목 분류 배지는 제목 옆에 유지하고, 계산 PER은 보조 지표로
 * 줄였다. 차트는 Toss처럼 콘텐츠 폭을 넓게 쓰며, 필요할 때 개별 차트를 크게 연다.
 */
export function StockFinancialsSection({ symbol, marketCountry }: { symbol: string; marketCountry: MarketCountry }) {
  const { theme } = useTheme();
  const [financials, setFinancials] = useState<StockFinancials | null>(null);
  const [unsupported, setUnsupported] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState<FinancialRange>("annual");
  const [expanded, setExpanded] = useState(false);
  const [expandedChart, setExpandedChart] = useState<ChartKind | null>(null);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setLoadError(false);
    setUnsupported(false);
    setExpanded(false);
    setExpandedChart(null);
    getStockFinancials(symbol, marketCountry)
      .then((res) => {
        if (cancelled) return;
        setFinancials(res);
        setRange(res.annual.length > 0 ? "annual" : "quarterly");
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && (err.code === "FINANCIALS_NOT_SUPPORTED" || err.code === "STOCK_NOT_FOUND")) {
          setUnsupported(true);
        } else {
          setLoadError(true);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [symbol, marketCountry]);

  useEffect(() => {
    if (expandedChart === null) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setExpandedChart(null);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [expandedChart]);

  if (unsupported) return null;

  const periods = financials ? (range === "annual" ? financials.annual : financials.quarterly) : [];
  const previewPeriods = recentPeriods(periods, range);
  const visiblePeriods = expanded ? periods : previewPeriods;
  const graphPeriods = chartPeriods(periods, range);

  return (
    <div className="mb-3.5 rounded-[20px] p-5.5" style={{ background: "var(--card)" }}>
      <div className="mb-3 flex flex-wrap items-center gap-2.5">
        <h4 className="text-[16px] font-bold" style={{ color: "var(--ink)" }}>
          재무제표
        </h4>
        {financials?.industry && (
          <span
            className="rounded-md px-2 py-0.5 text-[11.5px] font-semibold"
            style={{ background: "var(--fill)", color: "var(--mut)" }}
          >
            {financials.industry.small.name}
          </span>
        )}
        {financials?.dataStatus === "STALE" && (
          <span className="text-[11.5px]" style={{ color: "var(--mut2)" }}>
            일부 정보가 최신이 아닐 수 있어요
          </span>
        )}
        {financials && (
          <PillTabs
            options={[
              { value: "annual", label: "연간" },
              { value: "quarterly", label: "분기" },
            ]}
            value={range}
            onChange={(value) => {
              setRange(value as FinancialRange);
              setExpanded(false);
            }}
            trackClassName="ml-auto w-[132px] gap-0.5 rounded-full p-[3px]"
            trackStyle={{
              background: theme === "dark" ? "rgba(255,255,255,.03)" : "rgba(15,56,104,.06)",
              border: theme === "dark" ? "1px solid rgba(255,255,255,.06)" : "1px solid rgba(15,56,104,.12)",
            }}
            buttonClassName="rounded-full px-0 py-1.5 text-[12.5px] font-bold"
            inactiveTextStyle={{ color: "var(--mut)" }}
          />
        )}
      </div>

      {loading ? (
        <div className="py-10 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
          재무 정보를 불러오는 중…
        </div>
      ) : loadError || !financials ? (
        <div className="py-10 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
          재무 정보를 조회할 수 없어요. 잠시 후 다시 시도해주세요.
        </div>
      ) : (
        <>
          <ValuationSummary financials={financials} />
          {periods.length === 0 ? (
            <div className="py-10 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
              {range === "annual" ? "연간" : "분기"} 재무 정보가 아직 없어요.
            </div>
          ) : (
            <>
              <div className="space-y-3">
                <FinancialAmountChart periods={graphPeriods} onExpand={() => setExpandedChart("performance")} />
                <FinancialProfitabilityChart periods={graphPeriods} onExpand={() => setExpandedChart("profitability")} />
                <FinancialPositionChart periods={graphPeriods} onExpand={() => setExpandedChart("position")} />
              </div>

              <div className="mt-4 overflow-x-auto">
                <div className="min-w-[560px]">
                  <div
                    className="grid gap-3 px-3 py-2 text-[12px] font-bold"
                    style={{ gridTemplateColumns: "1fr 1.3fr 1.2fr 1.2fr 1fr 0.9fr 1fr", color: "var(--mut2)" }}
                  >
                    <span>기준월</span>
                    <span className="text-right">매출액</span>
                    <span className="text-right">영업이익</span>
                    <span className="text-right">순이익</span>
                    <span className="text-right">영업이익률</span>
                    <span className="text-right">ROE</span>
                    <span className="text-right">부채비율</span>
                  </div>
                  {visiblePeriods.map((period) => (
                    <FinancialPeriodRow key={period.statementYearMonth} period={period} />
                  ))}
                </div>
              </div>
              {periods.length > previewPeriods.length && (
                <button
                  type="button"
                  className="mt-3 w-full cursor-pointer rounded-xl py-2.5 text-[13px] font-bold"
                  style={{ background: "var(--fill)", color: "var(--accentText)" }}
                  onClick={() => setExpanded((current) => !current)}
                  aria-label={expanded ? "최근 재무제표만 보기" : "전체 재무제표 더 보기"}
                >
                  {expanded ? "간단히 보기" : "더 보기"}
                </button>
              )}
            </>
          )}
        </>
      )}

      {expandedChart && financials && (
        <FinancialChartModal title={chartTitle(expandedChart)} onClose={() => setExpandedChart(null)}>
          {expandedChart === "performance" && <FinancialAmountChart periods={graphPeriods} large />}
          {expandedChart === "profitability" && <FinancialProfitabilityChart periods={graphPeriods} large />}
          {expandedChart === "position" && <FinancialPositionChart periods={graphPeriods} large />}
        </FinancialChartModal>
      )}
    </div>
  );
}

function ValuationSummary({ financials }: { financials: StockFinancials }) {
  const latestAnnual = financials.annual[0];
  const calculatedPer = formatCalculatedPer(financials.valuation.calculatedPer, latestAnnual?.ratios.eps);

  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-1.5 rounded-[14px] px-3.5 py-2.5" style={{ background: "var(--accentSoft)" }}>
      <div className="flex items-baseline gap-2.5">
        <span className="text-[12px] font-bold" style={{ color: "var(--onAccentSoftText)" }}>계산 PER</span>
        <strong className="text-[20px] font-extrabold tabular-nums" style={{ color: "var(--accentText)" }}>{calculatedPer}</strong>
      </div>
      <div className="ml-auto text-right text-[11.5px] leading-relaxed" style={{ color: "var(--onAccentSoftText)" }}>
        <span className="font-semibold">최근 연간 EPS 기준</span>
        <span className="ml-2 hidden sm:inline">현재가를 최근 연간 EPS로 나눈 참고용 계산값이에요.</span>
      </div>
    </div>
  );
}

type AmountSeries = {
  label: string;
  color: string;
  getValue: (period: StockFinancialPeriod) => string | null;
};

const AMOUNT_SERIES: AmountSeries[] = [
  { label: "매출액", color: "var(--accent)", getValue: (period) => period.incomeStatement.sales },
  { label: "영업이익", color: "var(--up)", getValue: (period) => period.incomeStatement.operatingProfit },
  { label: "순이익", color: "var(--down)", getValue: (period) => period.incomeStatement.netIncome },
];

const POSITION_SERIES: AmountSeries[] = [
  { label: "총자산", color: "var(--accent)", getValue: (period) => period.balanceSheet.totalAssets },
  { label: "총부채", color: "var(--down)", getValue: (period) => period.balanceSheet.totalLiabilities },
  { label: "총자본", color: "var(--up)", getValue: (period) => period.balanceSheet.totalEquity },
];

type RatioSeries = {
  label: string;
  color: string;
  getValue: (period: StockFinancialPeriod) => string | null;
};

const PROFITABILITY_SERIES: RatioSeries[] = [
  { label: "영업이익률", color: "var(--accent)", getValue: (period) => period.ratios.operatingProfitMargin },
  { label: "순이익률", color: "var(--down)", getValue: (period) => period.ratios.netProfitMargin },
  { label: "ROE", color: "var(--up)", getValue: (period) => period.ratios.roe },
];

function FinancialAmountChart({ periods, large = false, onExpand }: { periods: StockFinancialPeriod[]; large?: boolean; onExpand?: () => void }) {
  return (
    <FinancialBarChart
      title="실적 추이"
      subtitle="매출과 이익이 어떻게 변했는지 봐요"
      periods={periods}
      series={AMOUNT_SERIES}
      large={large}
      onExpand={onExpand}
      ariaLabel="최근 매출액과 이익 추이 막대그래프"
    />
  );
}

function FinancialPositionChart({ periods, large = false, onExpand }: { periods: StockFinancialPeriod[]; large?: boolean; onExpand?: () => void }) {
  return (
    <FinancialBarChart
      title="재무상태 추이"
      subtitle="자산·부채·자본의 흐름을 비교해요"
      periods={periods}
      series={POSITION_SERIES}
      large={large}
      onExpand={onExpand}
      ariaLabel="최근 총자산과 총부채와 총자본 추이 막대그래프"
    />
  );
}

function FinancialBarChart({
  title,
  subtitle,
  periods,
  series,
  large,
  onExpand,
  ariaLabel,
}: {
  title: string;
  subtitle: string;
  periods: StockFinancialPeriod[];
  series: AmountSeries[];
  large: boolean;
  onExpand?: () => void;
  ariaLabel: string;
}) {
  const values = series.flatMap((item) => periods.map((period) => toNumber(item.getValue(period))))
    .filter((value): value is number => value !== null);
  if (values.length === 0) return <ChartCard title={title} subtitle={subtitle} onExpand={onExpand}><EmptyChart /></ChartCard>;

  const maxAbs = Math.max(1, ...values.map((value) => Math.abs(value)));
  const baseline = large ? 180 : 154;
  const plotHeight = large ? 128 : 108;
  const left = large ? 34 : 28;
  const right = 726;
  const chartWidth = right - left;
  const groupWidth = chartWidth / Math.max(periods.length, 1);
  const barWidth = Math.min(28, Math.max(10, groupWidth / (series.length + 2)));
  const labelSize = large ? 13 : 12;
  const height = large ? 300 : 250;

  return (
    <ChartCard title={title} subtitle={subtitle} onExpand={onExpand} large={large}>
      <svg className={large ? "h-[300px] w-full" : "h-[250px] w-full"} viewBox={`0 0 760 ${height}`} role="img" aria-label={ariaLabel}>
        <line x1={left} y1={baseline} x2={right} y2={baseline} stroke="var(--line2)" strokeWidth="1" />
        {periods.map((period, periodIndex) => {
          const center = left + groupWidth * (periodIndex + 0.5);
          return (
            <g key={period.statementYearMonth}>
              {series.map((item, seriesIndex) => {
                const value = toNumber(item.getValue(period));
                if (value === null) return null;
                const barHeight = Math.max(3, (Math.abs(value) / maxAbs) * plotHeight);
                const x = center + (seriesIndex - (series.length - 1) / 2) * (barWidth + 3) - barWidth / 2;
                const y = value >= 0 ? baseline - barHeight : baseline;
                return (
                  <rect key={item.label} x={x} y={y} width={barWidth} height={barHeight} rx="4" fill={item.color} opacity=".9">
                    <title>{`${item.label} ${formatWon(String(value))}`}</title>
                  </rect>
                );
              })}
              <text x={center} y={large ? 232 : 207} textAnchor="middle" fontSize={labelSize} fill="var(--mut2)">
                {formatStatementMonth(period.statementYearMonth)}
              </text>
            </g>
          );
        })}
      </svg>
      <ChartLegend items={series} large={large} />
    </ChartCard>
  );
}

function FinancialProfitabilityChart({ periods, large = false, onExpand }: { periods: StockFinancialPeriod[]; large?: boolean; onExpand?: () => void }) {
  const values = PROFITABILITY_SERIES.flatMap((item) => periods.map((period) => toNumber(item.getValue(period))))
    .filter((value): value is number => value !== null);
  if (values.length === 0) return <ChartCard title="수익성 추이" subtitle="이익을 남기는 힘의 흐름" onExpand={onExpand}><EmptyChart /></ChartCard>;

  const maxValue = Math.max(100, ...values);
  const left = large ? 38 : 32;
  const right = 726;
  const top = large ? 26 : 22;
  const bottom = large ? 190 : 160;
  const chartWidth = right - left;
  const groupWidth = chartWidth / Math.max(periods.length - 1, 1);
  const labelSize = large ? 13 : 12;
  const height = large ? 300 : 250;

  return (
    <ChartCard title="수익성 추이" subtitle="이익을 남기는 힘의 흐름을 비교해요" onExpand={onExpand} large={large}>
      <svg className={large ? "h-[300px] w-full" : "h-[250px] w-full"} viewBox={`0 0 760 ${height}`} role="img" aria-label="최근 영업이익률과 순이익률과 ROE 추이 선그래프">
        <line x1={left} y1={top} x2={right} y2={top} stroke="var(--line2)" strokeWidth="1" strokeDasharray="4 5" />
        <line x1={left} y1={bottom} x2={right} y2={bottom} stroke="var(--line2)" strokeWidth="1" />
        <text x="4" y={top + 5} fontSize={labelSize} fill="var(--mut2)">{`${Math.round(maxValue)}%`}</text>
        <text x="12" y={bottom + 5} fontSize={labelSize} fill="var(--mut2)">0%</text>
        {PROFITABILITY_SERIES.map((item) => {
          const points = periods
            .map((period, index) => {
              const value = toNumber(item.getValue(period));
              if (value === null) return null;
              const x = periods.length === 1 ? (left + right) / 2 : left + groupWidth * index;
              const y = bottom - (Math.max(0, value) / maxValue) * (bottom - top);
              return `${x},${y}`;
            })
            .filter((point): point is string => point !== null)
            .join(" ");
          if (!points) return null;
          return <polyline key={item.label} points={points} fill="none" stroke={item.color} strokeWidth={large ? 3 : 2.5} strokeLinecap="round" strokeLinejoin="round" />;
        })}
        {periods.map((period, index) => {
          const x = periods.length === 1 ? (left + right) / 2 : left + groupWidth * index;
          return (
            <text key={period.statementYearMonth} x={x} y={large ? 232 : 207} textAnchor="middle" fontSize={labelSize} fill="var(--mut2)">
              {formatStatementMonth(period.statementYearMonth)}
            </text>
          );
        })}
      </svg>
      <ChartLegend items={PROFITABILITY_SERIES} large={large} />
    </ChartCard>
  );
}

function ChartCard({ title, subtitle, children, onExpand, large = false }: { title: string; subtitle?: string; children: ReactNode; onExpand?: () => void; large?: boolean }) {
  return (
    <div className={large ? "rounded-[18px] px-4 py-4" : "rounded-[18px] px-4 py-3.5"} style={{ background: "var(--bg)", border: "1px solid var(--line2)" }}>
      <div className="mb-1.5 flex items-center gap-3">
        <div className="min-w-0">
          <h5 className={large ? "text-[16px] font-bold" : "text-[15px] font-bold"} style={{ color: "var(--ink)" }}>{title}</h5>
          {subtitle && <p className={large ? "mt-0.5 text-[12px]" : "mt-0.5 text-[11.5px]"} style={{ color: "var(--mut2)" }}>{subtitle}</p>}
        </div>
        {onExpand && (
          <button
            type="button"
            className="ml-auto shrink-0 cursor-pointer rounded-lg px-2.5 py-1.5 text-[11.5px] font-bold"
            style={{ background: "var(--fill)", color: "var(--accentText)" }}
            onClick={onExpand}
            aria-label={`${title} 크게 보기`}
          >
            크게 보기
          </button>
        )}
      </div>
      {children}
    </div>
  );
}

function EmptyChart() {
  return <div className="flex h-[190px] items-center justify-center text-[13px]" style={{ color: "var(--mut2)" }}>그래프로 볼 수 있는 데이터가 아직 없어요.</div>;
}

function ChartLegend({ items, large = false }: { items: Array<{ label: string; color: string }>; large?: boolean }) {
  return (
    <div className={large ? "flex flex-wrap gap-x-4 gap-y-1 px-1 text-[12px]" : "flex flex-wrap gap-x-4 gap-y-1 px-1 text-[11.5px]"} style={{ color: "var(--mut2)" }}>
      {items.map((item) => (
        <span key={item.label} className="inline-flex items-center gap-1.5">
          <span className={large ? "h-2 w-2 rounded-full" : "h-1.5 w-1.5 rounded-full"} style={{ background: item.color }} />
          {item.label}
        </span>
      ))}
    </div>
  );
}

function chartTitle(kind: ChartKind): string {
  if (kind === "performance") return "실적 추이";
  if (kind === "profitability") return "수익성 추이";
  return "재무상태 추이";
}

function FinancialChartModal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/45 p-4"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      onMouseDown={onClose}
    >
      <div
        className="max-h-[90vh] w-full max-w-[900px] overflow-y-auto rounded-[22px] p-4 sm:p-5"
        style={{ background: "var(--card)" }}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="mb-2 flex justify-end">
          <button
            type="button"
            className="ml-auto cursor-pointer rounded-full px-3 py-1.5 text-[12px] font-bold"
            style={{ background: "var(--fill)", color: "var(--mut)" }}
            onClick={onClose}
            aria-label="차트 닫기"
          >
            닫기
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

function FinancialPeriodRow({ period }: { period: StockFinancialPeriod }) {
  return (
    <div
      className="grid items-center gap-3 px-3 py-2.5 text-[13.5px]"
      style={{ gridTemplateColumns: "1fr 1.3fr 1.2fr 1.2fr 1fr 0.9fr 1fr", borderTop: "1px solid var(--line2)" }}
    >
      <span className="font-semibold" style={{ color: "var(--ink)" }}>{formatStatementMonth(period.statementYearMonth)}</span>
      <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{formatWon(period.incomeStatement.sales)}</span>
      <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{formatWon(period.incomeStatement.operatingProfit)}</span>
      <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{formatWon(period.incomeStatement.netIncome)}</span>
      <span className="text-right tabular-nums" style={{ color: "var(--mut)" }}>{formatRatioPercent(period.ratios.operatingProfitMargin)}</span>
      <span className="text-right tabular-nums" style={{ color: "var(--mut)" }}>{formatRatioPercent(period.ratios.roe)}</span>
      <span className="text-right tabular-nums" style={{ color: "var(--mut)" }}>{formatRatioPercent(period.ratios.debtRatio)}</span>
    </div>
  );
}
