"use client";

import { useEffect, useRef, useState } from "react";
import { createChart, LineSeries, type IChartApi, type ISeriesApi, type UTCTimestamp } from "lightweight-charts";
import { PillTabs } from "./PillTabs";
import { useTheme } from "./ThemeProvider";
import { getExchangeRateHistory, type ExchangeRateHistoryItem, type ExchangeRatePeriod } from "@/lib/api";
import { resolveCssColor } from "@/lib/chart-colors";
import { formatNumber } from "@/lib/format";

const PERIOD_OPTIONS: { value: ExchangeRatePeriod; label: string }[] = [
  { value: "1d", label: "1일" },
  { value: "1w", label: "1주" },
  { value: "1m", label: "1개월" },
  { value: "3m", label: "3개월" },
  { value: "1y", label: "1년" },
];

/** `{rateAt, rate}[]` → lightweight-charts LineSeries가 요구하는 숫자 포맷. */
function toLinePoints(items: ExchangeRateHistoryItem[]): { time: UTCTimestamp; value: number }[] {
  const byTime = new Map<UTCTimestamp, number>();
  for (const item of items) {
    const time = Math.floor(new Date(item.rateAt).getTime() / 1000) as UTCTimestamp;
    const value = Number(item.rate);
    if (!Number.isFinite(time) || !Number.isFinite(value)) continue;
    if (!byTime.has(time)) byTime.set(time, value);
  }
  return [...byTime.entries()]
    .sort(([a], [b]) => a - b)
    .map(([time, value]) => ({ time, value }));
}

/**
 * "환율 추이 그래프" 모달 (이슈 랭킹 화면 요청사항). `GET /api/exchange-rates/history`를
 * 기간별로 조회해 `lightweight-charts` 라인 차트로 보여준다.
 *
 * <p>색 처리 방식은 종목 상세의 캔들차트(`CandlestickChart`)와 같다 — 자세한 이유는
 * `@/lib/chart-colors`의 `resolveCssColor` 주석 참고.
 */
export function ExchangeRateTrendModal({ onClose }: { onClose: () => void }) {
  const { theme } = useTheme();
  const [period, setPeriod] = useState<ExchangeRatePeriod>("1m");
  const [items, setItems] = useState<ExchangeRateHistoryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Line"> | null>(null);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setLoadError(false);
    getExchangeRateHistory(period)
      .then((res) => {
        if (!cancelled) setItems(res.items);
      })
      .catch(() => {
        if (!cancelled) setLoadError(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [period]);

  // 차트 인스턴스는 마운트 시 한 번만 만든다 — 기간 전환·테마 변경은 별도 effect가
  // 기존 인스턴스에 반영한다(CandlestickChart와 같은 이유).
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const line2 = resolveCssColor("--line2", "#e9f2f9");
    const chart = createChart(container, {
      width: container.clientWidth,
      height: 320,
      layout: { background: { color: "transparent" }, textColor: resolveCssColor("--ink", "#071829") },
      grid: { vertLines: { color: line2 }, horzLines: { color: line2 } },
      rightPriceScale: { borderColor: line2 },
      timeScale: { borderColor: line2, timeVisible: true, secondsVisible: false },
    });
    const series = chart.addSeries(LineSeries, {
      color: resolveCssColor("--accent", "#0f3868"),
      lineWidth: 2,
    });

    chartRef.current = chart;
    seriesRef.current = series;

    const resizeObserver = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width;
      if (width) chart.applyOptions({ width });
    });
    resizeObserver.observe(container);

    return () => {
      resizeObserver.disconnect();
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, []);

  // 기간이 바뀌어 새 데이터가 오면 기존 인스턴스에 반영한다.
  useEffect(() => {
    if (!seriesRef.current) return;
    seriesRef.current.setData(toLinePoints(items));
    chartRef.current?.timeScale().fitContent();
  }, [items]);

  // 라이트/다크 전환 시 색만 다시 입힌다.
  useEffect(() => {
    const chart = chartRef.current;
    const series = seriesRef.current;
    if (!chart || !series) return;
    const ink = resolveCssColor("--ink", "#071829");
    const line2 = resolveCssColor("--line2", "#e9f2f9");
    chart.applyOptions({
      layout: { textColor: ink },
      grid: { vertLines: { color: line2 }, horzLines: { color: line2 } },
      rightPriceScale: { borderColor: line2 },
      timeScale: { borderColor: line2 },
    });
    series.applyOptions({ color: resolveCssColor("--accent", "#0f3868") });
  }, [theme]);

  const latestRate = items.length > 0 ? items[items.length - 1].rate : null;

  return (
    <div
      className="fixed inset-0 z-[150] flex items-center justify-center px-4"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
      onClick={onClose}
    >
      <div
        className="w-full max-w-[640px] rounded-[24px] p-6.5"
        style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-1 flex items-start justify-between">
          <div>
            <h3 className="text-[18px] font-bold" style={{ color: "var(--ink)" }}>
              USD / KRW 환율 추이
            </h3>
            {latestRate && (
              <p className="mt-1 text-[13px]" style={{ color: "var(--mut2)" }}>
                최근값 {formatNumber(latestRate)}원
              </p>
            )}
          </div>
          <button
            onClick={onClose}
            className="rounded-full px-3 py-1.5 text-[13px] font-semibold"
            style={{ background: "var(--fill)", color: "var(--mut)" }}
            aria-label="닫기"
          >
            닫기
          </button>
        </div>

        <div className="my-4">
          <PillTabs
            options={PERIOD_OPTIONS}
            value={period}
            onChange={(v) => setPeriod(v as ExchangeRatePeriod)}
            trackClassName="w-fit gap-0.5 rounded-full p-[3px]"
            trackStyle={{
              background: theme === "dark" ? "rgba(255,255,255,.03)" : "rgba(15,56,104,.06)",
              border: theme === "dark" ? "1px solid rgba(255,255,255,.06)" : "1px solid rgba(15,56,104,.12)",
            }}
            buttonClassName="rounded-full px-3.5 py-1.5 text-[13px] font-bold"
            inactiveTextStyle={{ color: "var(--mut)" }}
          />
        </div>

        <div className="relative" style={{ minHeight: 320 }}>
          {loading && (
            <div className="absolute inset-0 flex items-center justify-center text-[13px]" style={{ color: "var(--mut2)" }}>
              불러오는 중…
            </div>
          )}
          {!loading && loadError && (
            <div className="absolute inset-0 flex items-center justify-center text-[13px]" style={{ color: "var(--mut2)" }}>
              환율 추이를 불러오지 못했어요. 잠시 후 다시 시도해주세요.
            </div>
          )}
          {!loading && !loadError && items.length < 2 && (
            <div className="absolute inset-0 flex items-center justify-center text-[13px]" style={{ color: "var(--mut2)" }}>
              표시할 데이터가 아직 없어요
            </div>
          )}
          <div ref={containerRef} className="w-full" />
        </div>
      </div>
    </div>
  );
}
