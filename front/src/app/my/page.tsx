"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Tag } from "@/components/Tag";
import { PillTabs } from "@/components/PillTabs";
import { Reveal } from "@/components/Reveal";
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
      "보유 종목과 체결 내역이 모두 정리되고 모의 투자금이 5,000만원으로 되돌아가요. 되돌릴 수 없어요. 초기화할까요?"
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
    <div>
      <Reveal delay={0}>
        <div className="mb-4.5 flex items-baseline gap-3">
          <h2 className="text-[28px] font-extrabold" style={{ color: "var(--ink)" }}>내 계좌</h2>
          <span className="text-[13px]" style={{ color: "var(--mut2)" }}>12:36:59 기준</span>
        </div>
      </Reveal>

      <Reveal delay={0.1} className="mb-6 flex gap-4 max-md:flex-col">
        <SummaryCard label="총 자산" value={formatNumber(totalAssets.round().toNumber())} />
        <SummaryCard label="예수금" value={formatNumber(cash)} />
        <SummaryCard label="주식 평가금액" value={formatNumber(stockValue.round().toNumber())} />
        <SummaryCard
          label="평가손익"
          value={
            <>
              {formatSigned(pnl.round().toNumber())}{" "}
              <span className="text-[15px]">({formatPercent(pnlRate)})</span>
            </>
          }
          tone={pnl.greaterThanOrEqualTo(0) ? "up" : "down"}
        />
      </Reveal>

      <Reveal delay={0.2}>
        <PillTabs
          options={[
            { value: "holdings", label: "보유 종목" },
            { value: "ledger", label: "체결 내역" },
          ]}
          value={tab}
          onChange={(v) => setTab(v as "holdings" | "ledger")}
          trackClassName="mb-4.5 w-[200px] gap-0.5 rounded-full p-[3px]"
          trackStyle={{ background: "rgba(15,56,104,.06)", border: "0.1px solid rgba(15,56,104,.12)" }}
          buttonClassName="rounded-full px-0 py-2 text-[12px] font-bold"
          inactiveTextStyle={{ color: "var(--mut)" }}
        />
      </Reveal>

      <Reveal delay={0.3}>
      {tab === "holdings" ? (
        holdings.length === 0 ? (
          <div className="rounded-[20px] py-16 text-center text-[13.5px]" style={{ background: "var(--card)", color: "var(--mut2)" }}>
            보유 중인 종목이 없어요
          </div>
        ) : (
          <>
            <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
              <div
                className="grid px-5 py-2.5 text-[12px] font-bold"
                style={{
                  gridTemplateColumns: "1.6fr 1fr 1fr 1fr 1.3fr 1.4fr 80px",
                  borderBottom: "1px solid var(--line2)",
                  color: "var(--mut2)",
                }}
              >
                <span>종목</span>
                <span className="text-right">보유수량</span>
                <span className="text-right">평균단가</span>
                <span className="text-right">현재가</span>
                <span className="text-right">평가금액</span>
                <span className="text-right">평가손익</span>
                <span />
              </div>
              {holdings.map((h) => {
                const isUsd = h.currency === "USD";
                const value = holdingAmountKrw(h.quantity, h.lastPrice, h.currency, rate);
                const cost = holdingAmountKrw(h.quantity, h.avgBuyPrice, h.currency, rate);
                const hPnl = value.minus(cost);
                const hRate = cost.greaterThan(0) ? hPnl.dividedBy(cost).toNumber() : 0;
                const priceCell = (usdValue: number) => {
                  const krw = isUsd ? new D(usdValue).times(rate) : new D(usdValue);
                  return (
                    <>
                      {formatNumber(krw.round().toNumber())}
                      {isUsd && <div className="text-[10.5px]" style={{ color: "var(--mut2)" }}>{formatUsd(usdValue)}</div>}
                    </>
                  );
                };
                return (
                  <div
                    key={h.symbol}
                    className="grid items-center px-5 py-3 text-[13.5px]"
                    style={{ gridTemplateColumns: "1.6fr 1fr 1fr 1fr 1.3fr 1.4fr 80px", borderBottom: "1px solid var(--line2)" }}
                  >
                    <span style={{ color: "var(--ink)" }}>
                      {h.name} <Tag>{h.symbol}</Tag>
                    </span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{h.quantity}</span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{priceCell(h.avgBuyPrice)}</span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{priceCell(h.lastPrice)}</span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{formatNumber(value.round().toNumber())}</span>
                    <span
                      className="text-right tabular-nums font-semibold"
                      style={{ color: hPnl.greaterThanOrEqualTo(0) ? "var(--up)" : "var(--down)" }}
                    >
                      {formatSigned(hPnl.round().toNumber())} <span className="text-[11.5px]">({formatPercent(hRate)})</span>
                    </span>
                    <span className="text-right">
                      <Link
                        href={`/stocks/${h.symbol}`}
                        className="rounded-full px-2.5 py-1 text-[12px] font-semibold"
                        style={{ background: "var(--fill)", color: "var(--ink)" }}
                      >
                        거래
                      </Link>
                    </span>
                  </div>
                );
              })}
            </div>
            <div className="mt-2.5 text-[11.5px]" style={{ color: "var(--mut2)" }}>
              해외 종목 평가금액은 적용 환율({formatNumber(rate)} KRW/USD)로 환산돼요
            </div>
          </>
        )
      ) : (
        <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
          <div
            className="grid px-5 py-2.5 text-[12px] font-medium"
            style={{ gridTemplateColumns: "80px 2fr 1fr 1fr 1.2fr", borderBottom: "1px solid var(--line2)", color: "var(--mut2)" }}
          >
            <span>구분</span>
            <span>설명</span>
            <span className="text-right">증감액</span>
            <span className="text-right">잔액</span>
            <span>발생시각</span>
          </div>
          {ledger.map((entry, i) => (
            <div
              key={i}
              className="grid items-center px-5 py-3 text-[13.5px]"
              style={{ gridTemplateColumns: "80px 2fr 1fr 1fr 1.2fr", borderBottom: "1px solid var(--line2)" }}
            >
              <span>
                <LedgerBadge type={entry.type} />
              </span>
              <span style={{ color: "var(--body)" }}>{entry.memo}</span>
              <span
                className="text-right tabular-nums font-semibold"
                style={{ color: entry.amount >= 0 ? "var(--up)" : "var(--down)" }}
              >
                {formatSigned(entry.amount)}
              </span>
              <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{formatNumber(entry.balanceAfter)}</span>
              <span className="text-[11.5px]" style={{ color: "var(--mut2)" }}>{entry.occurredAt}</span>
            </div>
          ))}
        </div>
      )}
      </Reveal>

      <Reveal delay={0.4} className="mt-7 rounded-[20px] px-6 py-5.5" style={{ background: "var(--dangerBg)" }}>
        <div className="flex flex-wrap items-center gap-5">
          <div>
            <div className="mb-1 text-[15px] font-bold" style={{ color: "var(--ink)" }}>포트폴리오 초기화</div>
            <div className="text-[13px] leading-relaxed" style={{ color: "var(--dangerTextSoft)" }}>
              보유 종목과 체결 내역이 모두 정리되고 모의 투자금이 <b>5,000만원</b>으로 되돌아가요. 되돌릴 수
              없어요.
            </div>
          </div>
          <button
            onClick={handleReset}
            className="ml-auto rounded-xl px-5 py-3 text-[14px] font-bold"
            style={{ background: "var(--card)", color: "var(--dangerText)" }}
          >
            포트폴리오 초기화
          </button>
        </div>
      </Reveal>
    </div>
  );
}

function SummaryCard({ label, value, tone }: { label: string; value: React.ReactNode; tone?: "up" | "down" }) {
  return (
    <div className="flex-1 rounded-[20px] px-6 py-5.5" style={{ background: "var(--card)" }}>
      <div className="text-[13px]" style={{ color: "var(--mut)" }}>{label}</div>
      <div
        className="mt-1.5 text-[24px] font-extrabold"
        style={{ color: tone === "up" ? "var(--up)" : tone === "down" ? "var(--down)" : "var(--ink)" }}
      >
        {value}
      </div>
    </div>
  );
}

function LedgerBadge({ type }: { type: LedgerEntry["type"] }) {
  const style =
    type === "매수"
      ? { background: "var(--downBg)", color: "var(--down)" }
      : type === "매도"
        ? { background: "var(--upBg)", color: "var(--up)" }
        : { background: "var(--accentSoft)", color: "var(--onAccentSoftText)" };
  return (
    <span className="w-fit rounded-lg px-1.5 py-0.5 text-[10.5px] font-bold" style={style}>
      {type}
    </span>
  );
}
