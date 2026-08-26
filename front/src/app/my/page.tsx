"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Tag } from "@/components/Tag";
import { useExchangeRate } from "@/components/ExchangeRateProvider";
import { D } from "@/lib/decimal";
import {
  AVAILABLE_CASH,
  INITIAL_CASH,
  MOCK_HOLDINGS,
  MOCK_LEDGER,
  type Holding,
  type LedgerEntry,
} from "@/lib/mock-data";
import { formatNumber, formatPercent, formatSigned, formatUsd } from "@/lib/format";

/** 보유 수량 × 단가를 원화로 환산한다. 평가금액·평가손익처럼 정확해야 하는
 * 계산이라 순수 number 대신 decimal.js(D)로 계산한다 (다훈님 리뷰, PR #17). */
function holdingAmountKrw(quantity: number, unitPrice: number, currency: "KRW" | "USD", usdKrwRate: number) {
  const amount = new D(quantity).times(unitPrice);
  return currency === "USD" ? amount.times(usdKrwRate) : amount;
}

export default function MyPage() {
  const { rate } = useExchangeRate();
  const [tab, setTab] = useState<"holdings" | "ledger">("holdings");
  const [holdings, setHoldings] = useState<Holding[]>(MOCK_HOLDINGS);
  const [ledger, setLedger] = useState<LedgerEntry[]>(MOCK_LEDGER);
  const [cash, setCash] = useState(AVAILABLE_CASH);

  const stockValue = useMemo(
    () => holdings.reduce((sum, h) => sum.plus(holdingAmountKrw(h.quantity, h.lastPrice, h.currency, rate)), new D(0)),
    [holdings, rate]
  );
  const costBasis = useMemo(
    () => holdings.reduce((sum, h) => sum.plus(holdingAmountKrw(h.quantity, h.avgBuyPrice, h.currency, rate)), new D(0)),
    [holdings, rate]
  );
  const pnl = stockValue.minus(costBasis);
  const pnlRate = costBasis.greaterThan(0) ? pnl.dividedBy(costBasis).toNumber() : 0;
  const totalAssets = new D(cash).plus(stockValue);

  function handleReset() {
    const ok = window.confirm(
      "보유 종목과 체결 내역이 모두 정리되고 모의 투자금이 5,000만원으로 되돌아갑니다. 되돌릴 수 없습니다. 초기화할까요?"
    );
    if (!ok) return;
    setHoldings([]);
    setCash(INITIAL_CASH);
    setLedger([
      {
        type: "초기지급",
        memo: "모의 투자금 지급 · 2회차 (포트폴리오 초기화)",
        amount: INITIAL_CASH,
        balanceAfter: INITIAL_CASH,
        occurredAt: new Date().toISOString().slice(0, 16).replace("T", " "),
      },
    ]);
  }

  return (
    <div className="p-6">
      <div className="mb-4 flex items-baseline gap-3">
        <h2 className="text-[19px] font-bold text-gray-900">내 계좌</h2>
        <span className="text-[11.5px] text-gray-400">12:36:59 기준</span>
      </div>

      <div className="mb-5 flex gap-4">
        <SummaryCard label="총 자산" value={formatNumber(totalAssets.round().toNumber())} />
        <SummaryCard label="예수금" value={formatNumber(cash)} />
        <SummaryCard label="주식 평가금액" value={formatNumber(stockValue.round().toNumber())} />
        <SummaryCard
          label="평가손익"
          value={
            <>
              {formatSigned(pnl.round().toNumber())}{" "}
              <span className="text-[14px]">({formatPercent(pnlRate)})</span>
            </>
          }
          emphasize={pnl.greaterThanOrEqualTo(0)}
        />
      </div>

      <div className="mb-3.5 flex gap-0.5 border-b border-gray-200">
        {(["holdings", "ledger"] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`border-b-2 px-3.5 py-1.5 text-[13px] ${
              tab === t ? "border-gray-900 font-semibold text-gray-900" : "border-transparent text-gray-400"
            }`}
          >
            {t === "holdings" ? "보유 종목" : "체결 내역"}
          </button>
        ))}
      </div>

      {tab === "holdings" ? (
        holdings.length === 0 ? (
          <div className="py-14 text-center text-[13px] text-gray-400">보유 중인 종목이 없어요</div>
        ) : (
          <>
            <table className="w-full text-[13px]">
              <thead>
                <tr>
                  <th className="border-b border-gray-200 py-2.5 text-left text-[12px] font-medium text-gray-500">종목</th>
                  <th className="border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">보유수량</th>
                  <th className="border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">평균단가</th>
                  <th className="border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">현재가</th>
                  <th className="border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">평가금액</th>
                  <th className="border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">평가손익</th>
                  <th className="w-16 border-b border-gray-200 py-2.5" />
                </tr>
              </thead>
              <tbody>
                {holdings.map((h) => {
                  const isUsd = h.currency === "USD";
                  const value = holdingAmountKrw(h.quantity, h.lastPrice, h.currency, rate);
                  const cost = holdingAmountKrw(h.quantity, h.avgBuyPrice, h.currency, rate);
                  const hPnl = value.minus(cost);
                  const hRate = cost.greaterThan(0) ? hPnl.dividedBy(cost).toNumber() : 0;
                  // 정책상 원화만 거래에 쓰이므로, 미국 종목은 단가도 원화 환산액을
                  // 우선 표시하고 원래 달러 값은 보조 텍스트로 같이 보여준다.
                  const priceCell = (usdValue: number) => {
                    const krw = isUsd ? new D(usdValue).times(rate) : new D(usdValue);
                    return (
                      <>
                        {formatNumber(krw.round().toNumber())}
                        {isUsd && <div className="text-[10.5px] text-gray-400">{formatUsd(usdValue)}</div>}
                      </>
                    );
                  };
                  return (
                    <tr key={h.symbol}>
                      <td className="border-b border-gray-100 py-2.5">
                        {h.name} <Tag>{h.symbol}</Tag>
                      </td>
                      <td className="border-b border-gray-100 py-2.5 text-right tabular-nums">{h.quantity}</td>
                      <td className="border-b border-gray-100 py-2.5 text-right tabular-nums">{priceCell(h.avgBuyPrice)}</td>
                      <td className="border-b border-gray-100 py-2.5 text-right tabular-nums">{priceCell(h.lastPrice)}</td>
                      <td className="border-b border-gray-100 py-2.5 text-right tabular-nums">{formatNumber(value.round().toNumber())}</td>
                      <td
                        className={`border-b border-gray-100 py-2.5 text-right tabular-nums ${
                          hPnl.greaterThanOrEqualTo(0) ? "font-semibold text-gray-900" : "text-gray-400"
                        }`}
                      >
                        {formatSigned(hPnl.round().toNumber())} <span className="text-[11.5px]">({formatPercent(hRate)})</span>
                      </td>
                      <td className="border-b border-gray-100 py-2.5">
                        <Link
                          href={`/stocks/${h.symbol}`}
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
            <div className="mt-2.5 text-[11.5px] text-gray-400">
              해외 종목 금액은 적용 환율({formatNumber(rate)} KRW/USD)로 환산 · 매시 정각 갱신
            </div>
          </>
        )
      ) : (
        <table className="w-full text-[13px]">
          <thead>
            <tr>
              <th className="w-24 border-b border-gray-200 py-2.5 text-left text-[12px] font-medium text-gray-500">구분</th>
              <th className="border-b border-gray-200 py-2.5 text-left text-[12px] font-medium text-gray-500">설명</th>
              <th className="w-32 border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">증감액</th>
              <th className="w-32 border-b border-gray-200 py-2.5 text-right text-[12px] font-medium text-gray-500">잔액</th>
              <th className="w-36 border-b border-gray-200 py-2.5 text-left text-[12px] font-medium text-gray-500">발생시각</th>
            </tr>
          </thead>
          <tbody>
            {ledger.map((entry, i) => (
              <tr key={i}>
                <td className="border-b border-gray-100 py-2.5">
                  <Tag variant="dark">{entry.type}</Tag>
                </td>
                <td className="border-b border-gray-100 py-2.5 text-gray-600">{entry.memo}</td>
                <td
                  className={`border-b border-gray-100 py-2.5 text-right tabular-nums ${
                    entry.amount >= 0 ? "font-semibold text-gray-900" : "text-gray-400"
                  }`}
                >
                  {formatSigned(entry.amount)}
                </td>
                <td className="border-b border-gray-100 py-2.5 text-right tabular-nums">{formatNumber(entry.balanceAfter)}</td>
                <td className="border-b border-gray-100 py-2.5 text-[11.5px] text-gray-400">{entry.occurredAt}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="mt-6 rounded-lg border border-gray-300 bg-gray-50 p-4">
        <div className="flex flex-wrap items-center gap-4">
          <div>
            <div className="mb-0.5 text-[14px] font-semibold text-gray-900">포트폴리오 초기화</div>
            <div className="text-[11.5px] leading-relaxed text-gray-500">
              보유 종목과 체결 내역이 모두 정리되고 모의 투자금이 <b>5,000만원</b>으로 되돌아갑니다. 되돌릴 수
              없습니다.
            </div>
          </div>
          <button
            onClick={handleReset}
            className="ml-auto rounded-md border border-gray-400 bg-white px-4 py-2 text-[13px] font-medium text-gray-900 hover:bg-gray-100"
          >
            포트폴리오 초기화
          </button>
        </div>
      </div>
    </div>
  );
}

function SummaryCard({
  label,
  value,
  emphasize,
}: {
  label: string;
  value: React.ReactNode;
  emphasize?: boolean;
}) {
  return (
    <div className="flex-1 rounded-lg border border-gray-200 p-4">
      <div className="text-[11.5px] text-gray-400">{label}</div>
      <div className={`mt-0.5 text-[22px] font-bold ${emphasize ? "text-gray-900" : "text-gray-900"}`}>{value}</div>
    </div>
  );
}
