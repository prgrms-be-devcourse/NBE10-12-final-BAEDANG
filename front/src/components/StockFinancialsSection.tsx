"use client";

import { useEffect, useState } from "react";
import { ApiError, getStockFinancials, type MarketCountry, type StockFinancialPeriod, type StockFinancials } from "@/lib/api";
import { formatNumber } from "@/lib/format";
import { PillTabs } from "./PillTabs";
import { useTheme } from "./ThemeProvider";

/** 재무 비율은 이미 "몇 %"로 계산돼 내려온다(예: operatingProfitMargin = 영업이익×100÷매출) —
 * 0~1 소수인 changeRate·returnRate류와 달리 formatPercent(×100)를 쓰면 안 된다. */
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

/**
 * 종목 상세의 재무제표 섹션 — `GET /api/stocks/{symbol}/financials`(#152, 국내만 지원)를
 * 연결한다. 이 화면은 초보 투자자용 서비스라(AGENTS.md), 백엔드가 내려주는 손익계산서·
 * 재무상태표·비율 20여 개 필드를 다 늘어놓지 않고 매출액·영업이익·순이익·영업이익률·
 * ROE·부채비율만 골라 보여준다 — 나머지는 필요해지면 그때 추가한다.
 *
 * <p>US 종목·ETF/ETN 등은 애초에 재무제표가 없어(`FINANCIALS_NOT_SUPPORTED`, 422) 이
 * 섹션 자체를 렌더링하지 않는다 — 종목마다 "이 종목엔 재무 정보가 없어요" 문구를 굳이
 * 보여줄 필요가 없다고 판단했다. 종목을 못 찾은 경우(404)도 마찬가지로 조용히 숨긴다.
 * 그 외 에러(429/502/503 등, 캐시도 없는 첫 조회)만 화면에 안내 문구로 보여준다.
 */
export function StockFinancialsSection({ symbol, marketCountry }: { symbol: string; marketCountry: MarketCountry }) {
  const { theme } = useTheme();
  const [financials, setFinancials] = useState<StockFinancials | null>(null);
  const [unsupported, setUnsupported] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState<"annual" | "quarterly">("annual");

  useEffect(() => {
    let cancelled = false;
    // symbol/marketCountry가 바뀔 때마다 이전 종목의 상태를 지우고 새로 불러온다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setLoadError(false);
    setUnsupported(false);
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
            onChange={(v) => setRange(v as "annual" | "quarterly")}
            trackClassName="ml-auto w-[132px] gap-0.5 rounded-full p-[3px]"
            // 라이트/다크 토글 뒤 트랙과 동일한 스타일로 맞춰달라는 요청 —
            // 기존 alpha 값을 절반으로 낮췄다.
            trackStyle={{
              background: theme === "dark" ? "rgba(255,255,255,.015)" : "rgba(15,56,104,.03)",
              border: theme === "dark" ? "1px solid rgba(255,255,255,.03)" : "1px solid rgba(15,56,104,.06)",
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
      ) : periods.length === 0 ? (
        <div className="py-10 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
          {range === "annual" ? "연간" : "분기"} 재무 정보가 아직 없어요.
        </div>
      ) : (
        <div className="overflow-x-auto">
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
            {periods.map((p) => (
              <FinancialPeriodRow key={p.statementYearMonth} period={p} />
            ))}
          </div>
        </div>
      )}
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
