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

/**
 * 종목 상세의 재무제표 섹션 — 초보 투자자가 먼저 흐름을 읽도록 최근 기간 그래프와
 * 핵심 계산 PER을 위에 배치하고, 상세 표는 최근 기간만 기본 노출한다.
 */
export function StockFinancialsSection({ symbol, marketCountry }: { symbol: string; marketCountry: MarketCountry }) {
  const { theme } = useTheme();
  const [financials, setFinancials] = useState<StockFinancials | null>(null);
  const [unsupported, setUnsupported] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState<FinancialRange>("annual");
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setLoadError(false);
    setUnsupported(false);
    setExpanded(false);
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
              <div className="grid gap-3 lg:grid-cols-2">
                <FinancialAmountChart periods={graphPeriods} />
                <FinancialRatioChart periods={graphPeriods} />
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
    </div>
  );
}

function ValuationSummary({ financials }: { financials: StockFinancials }) {
  const latestAnnual = financials.annual[0];
  const calculatedPer = formatCalculatedPer(financials.valuation.calculatedPer, latestAnnual?.ratios.eps);

  return (
    <div className="mb-4 rounded-[16px] px-4 py-3.5" style={{ background: "var(--accentSoft)" }}>
      <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
        <div>
          <div className="text-[12px] font-bold" style={{ color: "var(--onAccentSoftText)" }}>
            계산 PER
          </div>
          <div className="mt-1 text-[25px] font-extrabold tabular-nums" style={{ color: "var(--accentText)" }}>
            {calculatedPer}
          </div>
        </div>
        <div className="text-right text-[12px] leading-relaxed" style={{ color: "var(--onAccentSoftText)" }}>
          <div className="font-semibold">최근 연간 EPS 기준</div>
          <div>현재가를 최근 연간 EPS로 나눈 참고용 계산값이에요.</div>
        </div>
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

function FinancialAmountChart({ periods }: { periods: StockFinancialPeriod[] }) {
  const values = AMOUNT_SERIES.flatMap((series) => periods.map((period) => toNumber(series.getValue(period))))
    .filter((value): value is number => value !== null);
  if (values.length === 0) return <ChartCard title="실적 추이"><EmptyChart /></ChartCard>;

  const maxAbs = Math.max(1, ...values.map((value) => Math.abs(value)));
  const baseline = 132;
  const plotHeight = 92;
  const left = 22;
  const right = 618;
  const groupWidth = (right - left) / Math.max(periods.length, 1);
  const barWidth = Math.min(18, Math.max(7, groupWidth / (AMOUNT_SERIES.length + 2)));

  return (
    <ChartCard title="실적 추이" subtitle="최근 기간의 매출과 이익 흐름">
      <svg className="h-auto w-full" viewBox="0 0 640 212" role="img" aria-label="최근 매출액과 이익 추이 막대그래프">
        <line x1={left} y1={baseline} x2={right} y2={baseline} stroke="var(--line2)" strokeWidth="1" />
        {periods.map((period, periodIndex) => {
          const center = left + groupWidth * (periodIndex + 0.5);
          return (
            <g key={period.statementYearMonth}>
              {AMOUNT_SERIES.map((series, seriesIndex) => {
                const value = toNumber(series.getValue(period));
                if (value === null) return null;
                const height = Math.max(2, (Math.abs(value) / maxAbs) * plotHeight);
                const x = center + (seriesIndex - (AMOUNT_SERIES.length - 1) / 2) * (barWidth + 2) - barWidth / 2;
                const y = value >= 0 ? baseline - height : baseline;
                return (
                  <rect key={series.label} x={x} y={y} width={barWidth} height={height} rx="3" fill={series.color} opacity=".9">
                    <title>{`${series.label} ${formatWon(String(value))}`}</title>
                  </rect>
                );
              })}
              <text x={center} y="193" textAnchor="middle" fontSize="10" fill="var(--mut2)">
                {formatStatementMonth(period.statementYearMonth)}
              </text>
            </g>
          );
        })}
      </svg>
      <ChartLegend items={AMOUNT_SERIES} />
    </ChartCard>
  );
}

type RatioSeries = {
  label: string;
  color: string;
  getValue: (period: StockFinancialPeriod) => string | null;
};

const RATIO_SERIES: RatioSeries[] = [
  { label: "영업이익률", color: "var(--accent)", getValue: (period) => period.ratios.operatingProfitMargin },
  { label: "ROE", color: "var(--up)", getValue: (period) => period.ratios.roe },
  { label: "부채비율", color: "var(--warnText)", getValue: (period) => period.ratios.debtRatio },
];

function FinancialRatioChart({ periods }: { periods: StockFinancialPeriod[] }) {
  const values = RATIO_SERIES.flatMap((series) => periods.map((period) => toNumber(series.getValue(period))))
    .filter((value): value is number => value !== null);
  if (values.length === 0) return <ChartCard title="수익성·안정성"><EmptyChart /></ChartCard>;

  const maxValue = Math.max(100, ...values);
  const left = 28;
  const right = 618;
  const top = 18;
  const bottom = 154;
  const groupWidth = (right - left) / Math.max(periods.length - 1, 1);

  return (
    <ChartCard title="수익성·안정성" subtitle="비율은 높고 낮음보다 흐름을 먼저 봐요">
      <svg className="h-auto w-full" viewBox="0 0 640 212" role="img" aria-label="최근 수익성 및 부채비율 추이 선그래프">
        <line x1={left} y1={top} x2={right} y2={top} stroke="var(--line2)" strokeWidth="1" strokeDasharray="3 4" />
        <line x1={left} y1={bottom} x2={right} y2={bottom} stroke="var(--line2)" strokeWidth="1" />
        <text x="4" y={top + 4} fontSize="10" fill="var(--mut2)">{`${Math.round(maxValue)}%`}</text>
        <text x="10" y={bottom + 4} fontSize="10" fill="var(--mut2)">0%</text>
        {RATIO_SERIES.map((series) => {
          const points = periods
            .map((period, index) => {
              const value = toNumber(series.getValue(period));
              if (value === null) return null;
              const x = periods.length === 1 ? (left + right) / 2 : left + groupWidth * index;
              const y = bottom - (Math.max(0, value) / maxValue) * (bottom - top);
              return `${x},${y}`;
            })
            .filter((point): point is string => point !== null)
            .join(" ");
          if (!points) return null;
          return <polyline key={series.label} points={points} fill="none" stroke={series.color} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />;
        })}
        {periods.map((period, index) => {
          const x = periods.length === 1 ? (left + right) / 2 : left + groupWidth * index;
          return (
            <text key={period.statementYearMonth} x={x} y="193" textAnchor="middle" fontSize="10" fill="var(--mut2)">
              {formatStatementMonth(period.statementYearMonth)}
            </text>
          );
        })}
      </svg>
      <ChartLegend items={RATIO_SERIES} />
    </ChartCard>
  );
}

function ChartCard({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <div className="rounded-[16px] px-3.5 py-3" style={{ background: "var(--bg)", border: "1px solid var(--line2)" }}>
      <div className="mb-1 flex items-baseline justify-between gap-2">
        <h5 className="text-[13.5px] font-bold" style={{ color: "var(--ink)" }}>{title}</h5>
        {subtitle && <span className="text-[10.5px]" style={{ color: "var(--mut2)" }}>{subtitle}</span>}
      </div>
      {children}
    </div>
  );
}

function EmptyChart() {
  return <div className="flex h-[164px] items-center justify-center text-[12px]" style={{ color: "var(--mut2)" }}>그래프로 볼 수 있는 데이터가 아직 없어요.</div>;
}

function ChartLegend({ items }: { items: Array<{ label: string; color: string }> }) {
  return (
    <div className="flex flex-wrap gap-x-3 gap-y-1 px-1 text-[10.5px]" style={{ color: "var(--mut2)" }}>
      {items.map((item) => (
        <span key={item.label} className="inline-flex items-center gap-1">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: item.color }} />
          {item.label}
        </span>
      ))}
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
