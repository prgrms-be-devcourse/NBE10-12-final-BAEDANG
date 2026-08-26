"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Tag } from "@/components/Tag";
import {
  KR_RANKINGS,
  US_RANKINGS,
  SEARCHABLE_STOCKS,
  USD_KRW_RATE,
  type MarketCountry,
} from "@/lib/mock-data";
import { formatKoreanAmount, formatNumber, formatPercent, formatSigned, formatUsd } from "@/lib/format";

const PAGE_SIZE = 20;

export default function RankingsPage() {
  const [market, setMarket] = useState<MarketCountry>("KR");
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState("");

  const rankings = market === "KR" ? KR_RANKINGS : US_RANKINGS;
  const shown = rankings.slice(0, visibleCount);
  const hasNext = visibleCount < rankings.length;

  const searchResults = useMemo(() => {
    if (query.trim().length < 2) return [];
    const q = query.trim().toLowerCase();
    return SEARCHABLE_STOCKS.filter(
      (s) => s.name.toLowerCase().includes(q) || s.symbol.toLowerCase().includes(q)
    ).slice(0, 8);
  }, [query]);

  function switchMarket(next: MarketCountry) {
    setMarket(next);
    setVisibleCount(PAGE_SIZE);
  }

  function loadMore() {
    if (loading || !hasNext) return;
    setLoading(true);
    // 실제 API 응답 지연을 흉내내기 위한 목 딜레이. 실제 연동 시 fetch로 교체.
    setTimeout(() => {
      setVisibleCount((v) => Math.min(rankings.length, v + PAGE_SIZE));
      setLoading(false);
    }, 500);
  }

  return (
    <div className="p-6">
      <h2 className="text-[19px] font-bold text-gray-900">주식 종목 랭킹</h2>
      <p className="mb-4.5 text-[13px] text-gray-500">
        거래대금 기준 상위 100개 · 무엇을 살지 모르겠다면 여기서 시작하세요
      </p>

      {/* 환율 배너 */}
      <div className="mb-4 flex items-center gap-2.5 rounded-lg border border-gray-200 bg-gray-50 px-3.5 py-2 text-[12.5px]">
        <span className="font-bold text-gray-900">USD / KRW</span>
        <span className="text-[14px] font-bold tabular-nums text-gray-900">
          {formatNumber(USD_KRW_RATE)}
        </span>
        <span className="font-semibold text-gray-700">▲ 2.30 (+0.16%)</span>
        <span className="text-gray-400">15:00 기준</span>
        <span
          className="ml-auto cursor-default text-gray-300"
          title="환율 추이 그래프는 2주차 MVP 예정입니다"
        >
          환율 추이 그래프 →
        </span>
      </div>

      {/* 검색 */}
      <div className="relative mb-5 max-w-[760px]">
        <input
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-[13px] text-gray-900 outline-none focus:border-gray-500"
          placeholder="티커 또는 종목명으로 검색 (2자 이상)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {searchResults.length > 0 && (
          <div className="absolute z-10 w-full rounded-b-md border border-t-0 border-gray-300 bg-white text-[13px] shadow-sm">
            {searchResults.map((s) => (
              <Link
                key={`${s.market}-${s.symbol}`}
                href={`/stocks/${s.symbol}`}
                className="flex items-center gap-1.5 border-b border-gray-100 px-3 py-2 last:border-b-0 hover:bg-gray-50"
              >
                {s.name} <Tag>{s.symbol}</Tag> <Tag>{s.market}</Tag> <Tag variant="dark">{s.category}</Tag>
              </Link>
            ))}
          </div>
        )}
      </div>

      {/* 탭 */}
      <div className="mb-3 flex gap-0.5 border-b border-gray-200">
        {(["KR", "US"] as const).map((m) => (
          <button
            key={m}
            onClick={() => switchMarket(m)}
            className={`border-b-2 px-3.5 py-1.5 text-[13px] ${
              market === m
                ? "border-gray-900 font-semibold text-gray-900"
                : "border-transparent text-gray-400 hover:text-gray-700"
            }`}
          >
            {m === "KR" ? "국내 주식" : "해외 주식"}
          </button>
        ))}
      </div>

      <div className="mb-3 flex items-center">
        <span className="text-[11.5px] text-gray-400">
          최근 <b className="text-gray-600">1주 거래대금</b> 상위 100개 · 개별주 · 배당주 · ETF 모두
          포함 · 매주 월요일 갱신
        </span>
        <span className="ml-auto text-[11.5px] text-gray-400">12:36:59 기준 · 5초마다 갱신</span>
      </div>

      <table className="w-full text-[13px]">
        <thead>
          <tr>
            <th className="w-11 border-b border-gray-200 py-2.5 text-left text-[12px] font-medium text-gray-500">
              순위
            </th>
            <th className="border-b border-gray-200 py-2.5 text-left text-[12px] font-medium text-gray-500">
              종목명
            </th>
            <th className="w-24 border-b border-gray-200 py-2.5 text-left text-[12px] font-medium text-gray-500">
              티커
            </th>
            <th className="w-20 border-b border-gray-200 py-2.5 text-left text-[12px] font-medium text-gray-500">
              종류
            </th>
            <th className="border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">
              현재가
            </th>
            <th className="w-36 border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">
              전일대비
            </th>
            <th className="w-24 border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">
              거래대금
            </th>
            <th className="w-16 border-b border-gray-200 py-2.5" />
          </tr>
        </thead>
        <tbody>
          {shown.map((item) => {
            const isUp = item.changeAmount >= 0;
            const price = item.currency === "USD" ? formatUsd(item.lastPrice) : formatNumber(item.lastPrice);
            const change = item.currency === "USD" ? formatUsd(item.changeAmount) : formatSigned(item.changeAmount);
            return (
              <tr key={item.symbol}>
                <td className="border-b border-gray-100 py-2.5 text-gray-500">{item.rank}</td>
                <td className="border-b border-gray-100 py-2.5">{item.name}</td>
                <td className="border-b border-gray-100 py-2.5">
                  <Tag>{item.symbol}</Tag>
                </td>
                <td className="border-b border-gray-100 py-2.5">
                  <Tag variant="dark">{item.category}</Tag>
                </td>
                <td className="border-b border-gray-100 py-2.5 text-right tabular-nums">{price}</td>
                <td
                  className={`border-b border-gray-100 py-2.5 text-right tabular-nums ${
                    isUp ? "font-semibold text-gray-900" : "text-gray-400"
                  }`}
                >
                  {isUp ? "▲" : "▼"} {change} ({formatPercent(item.changeRate)})
                </td>
                <td className="border-b border-gray-100 py-2.5 text-right tabular-nums">
                  {formatKoreanAmount(item.tradingAmount)}
                </td>
                <td className="border-b border-gray-100 py-2.5">
                  <Link
                    href={`/stocks/${item.symbol}`}
                    className="rounded border border-gray-300 px-2.5 py-1 text-[12px] text-gray-900 hover:bg-gray-50"
                  >
                    거래
                  </Link>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <div className="mt-5 text-center">
        {hasNext ? (
          <button
            onClick={loadMore}
            disabled={loading}
            className="rounded-md border border-gray-300 px-8 py-2 text-[13px] text-gray-900 hover:bg-gray-50 disabled:cursor-not-allowed disabled:text-gray-400"
          >
            {loading ? "불러오는 중…" : "더 보기"}
          </button>
        ) : (
          <span className="text-[13px] text-gray-400">모든 종목을 불러왔어요</span>
        )}
        <div className="mt-1.5 text-[11.5px] text-gray-400">
          {shown.length} / {rankings.length}개 표시 중
        </div>
      </div>
    </div>
  );
}
