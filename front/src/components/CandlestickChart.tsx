"use client";

import { useEffect, useRef } from "react";
import {
  createChart,
  CandlestickSeries,
  HistogramSeries,
  CrosshairMode,
  type IChartApi,
  type ISeriesApi,
} from "lightweight-charts";
import type { Candle } from "@/lib/api";
import { toCandlestickData, toVolumeData } from "@/lib/candle-chart-data";

/**
 * `lightweight-charts`(TradingView)로 그리는 실제 캔들스틱 + 거래량 차트 (이슈 #76).
 *
 * <p>차트 색은 CSS 변수(`--up`/`--down`/`--ink`/`--line2`)의 "지금 이 순간" 계산된 값을
 * 읽어서 쓴다 — canvas 렌더링이라 `var(--up)` 문자열을 그대로 넘길 수 없고, 다크/라이트
 * 값을 이 컴포넌트에 다시 하드코딩하면 전역 토큰이 바뀔 때 여기만 따로 관리해야 한다.
 *
 * <p>차트 인스턴스는 마운트 시 한 번만 만든다 — 데이터나 테마가 바뀔 때마다 새로
 * 만들면 사용자가 확대·스크롤해둔 상태가 매번 초기화된다. 대신 별도 effect에서
 * `setData`/`applyOptions`로 갱신한다.
 */

// 아래 resolveCssColor 전용 1x1 캔버스 — 매번 새로 만들 필요 없이 재사용한다.
let colorProbeContext: CanvasRenderingContext2D | null | undefined;

/**
 * CSS 변수 값을 `lightweight-charts`가 이해하는 구체적 `rgb()` 문자열로 바꾼다.
 *
 * <p>이 앱의 다크 모드 색은 `oklch(...)`로 선언돼 있는데, `getComputedStyle`로 바로
 * 읽으면(엘리먼트의 `color` 속성으로 우회해도 마찬가지) 브라우저가 `lab(...)`처럼
 * lightweight-charts의 색 파서가 모르는 표기 그대로 돌려준다 — "Failed to parse color:
 * lab(...)" 런타임 에러로 실제 확인됨. Canvas 2D의 `fillStyle`은 oklch/lab을 포함한
 * 모든 CSS `<color>` 표기를 그대로 받아들이고, `getImageData`는 표기와 무관하게
 * 항상 구체적인 0~255 RGBA 픽셀값을 돌려준다 — 그래서 1x1 캔버스에 실제로 "그려서"
 * 되읽는 방식으로 정규화한다.
 */
function resolveCssColor(name: string, fallback: string): string {
  if (typeof document === "undefined") return fallback;
  if (colorProbeContext === undefined) {
    const canvas = document.createElement("canvas");
    canvas.width = 1;
    canvas.height = 1;
    colorProbeContext = canvas.getContext("2d", { willReadFrequently: true });
  }
  const ctx = colorProbeContext;
  if (!ctx) return fallback;

  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  if (!raw) return fallback;

  try {
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = raw;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
    return a === 255 ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${(a / 255).toFixed(3)})`;
  } catch {
    return fallback;
  }
}

export function CandlestickChart({
  items,
  theme,
  height = 260,
}: {
  items: Candle[];
  theme: "light" | "dark";
  height?: number;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const volumeSeriesRef = useRef<ISeriesApi<"Histogram"> | null>(null);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const line2 = resolveCssColor("--line2", "#e9f2f9");
    const chart = createChart(container, {
      width: container.clientWidth,
      height,
      layout: { background: { color: "transparent" }, textColor: resolveCssColor("--ink", "#071829") },
      grid: { vertLines: { color: line2 }, horzLines: { color: line2 } },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: { borderColor: line2 },
      timeScale: { borderColor: line2, timeVisible: true, secondsVisible: false },
    });

    const upColor = resolveCssColor("--up", "#d33d3d");
    const downColor = resolveCssColor("--down", "#3366cc");
    const candleSeries = chart.addSeries(CandlestickSeries, {
      upColor,
      downColor,
      borderVisible: false,
      wickUpColor: upColor,
      wickDownColor: downColor,
    });
    const volumeSeries = chart.addSeries(HistogramSeries, {
      priceFormat: { type: "volume" },
      priceScaleId: "volume",
    });
    // 거래량을 별도 차트 없이 같은 차트 하단 20%만 차지하는 보조 눈금으로 겹쳐 그린다.
    volumeSeries.priceScale().applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });

    chartRef.current = chart;
    candleSeriesRef.current = candleSeries;
    volumeSeriesRef.current = volumeSeries;
    candleSeries.setData(toCandlestickData(items));
    volumeSeries.setData(toVolumeData(items, upColor, downColor));
    chart.timeScale().fitContent();

    const resizeObserver = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width;
      if (width) chart.applyOptions({ width });
    });
    resizeObserver.observe(container);

    return () => {
      resizeObserver.disconnect();
      chart.remove();
      chartRef.current = null;
      candleSeriesRef.current = null;
      volumeSeriesRef.current = null;
    };
    // 최초 마운트 시에만 차트를 만든다 — items/theme 변경은 아래 별도 effect가 처리한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [height]);

  // 봉/기간 전환 등으로 데이터가 바뀌면 기존 차트 인스턴스에 새 데이터만 반영한다.
  useEffect(() => {
    if (!candleSeriesRef.current || !volumeSeriesRef.current) return;
    const upColor = resolveCssColor("--up", "#d33d3d");
    const downColor = resolveCssColor("--down", "#3366cc");
    candleSeriesRef.current.setData(toCandlestickData(items));
    volumeSeriesRef.current.setData(toVolumeData(items, upColor, downColor));
    chartRef.current?.timeScale().fitContent();
  }, [items]);

  // 라이트/다크 전환 시 색만 다시 입힌다 — 거래량 막대 색은 데이터에 박혀 있어 다시 만들어야 한다.
  useEffect(() => {
    const chart = chartRef.current;
    const candleSeries = candleSeriesRef.current;
    const volumeSeries = volumeSeriesRef.current;
    if (!chart || !candleSeries || !volumeSeries) return;

    const ink = resolveCssColor("--ink", "#071829");
    const line2 = resolveCssColor("--line2", "#e9f2f9");
    const upColor = resolveCssColor("--up", "#d33d3d");
    const downColor = resolveCssColor("--down", "#3366cc");

    chart.applyOptions({
      layout: { textColor: ink },
      grid: { vertLines: { color: line2 }, horzLines: { color: line2 } },
      rightPriceScale: { borderColor: line2 },
      timeScale: { borderColor: line2 },
    });
    candleSeries.applyOptions({ upColor, downColor, wickUpColor: upColor, wickDownColor: downColor });
    volumeSeries.setData(toVolumeData(items, upColor, downColor));
    // items는 최신 값을 읽기만 하면 되고, 이 effect 자체는 테마가 바뀔 때만 다시 돌면 된다
    // (items 변경 자체는 위 effect가 이미 처리한다).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme]);

  return <div ref={containerRef} className="w-full" />;
}
