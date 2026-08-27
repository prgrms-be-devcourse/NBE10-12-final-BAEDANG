"use client";

import { useMemo, useState } from "react";
import { Tag } from "./Tag";
import { SignupModal } from "./SignupModal";
import { useAuth } from "./AuthProvider";
import { useExchangeRate } from "./ExchangeRateProvider";
import { getHolding, getMockChartPoints, AVAILABLE_CASH, type StockDetail } from "@/lib/mock-data";
import { calculateOrderAmount } from "@/lib/order-amount";
import { formatNumber, formatPercent, formatSigned, formatUsd } from "@/lib/format";
import { ApiError, placeOrder } from "@/lib/api";
import { nextClientOrderId } from "@/lib/order-retry-policy";

const TRADABLE_REASON_LABEL: Record<string, string> = {
  MARKET_CLOSED: "장 마감 · 거래 시간이 아니에요",
  NOT_IN_UNIVERSE: "이 종목은 아직 거래를 지원하지 않아요",
  SUSPENDED: "거래정지 종목이에요",
  LIQUIDATION: "정리매매 종목이에요",
};

const CATEGORY_GUIDE: Record<string, string> = {
  개별주:
    "개별주는 특정 기업 한 곳의 지분을 사는 것입니다. 그 회사가 잘되면 오르고 어려워지면 내립니다. 여러 기업에 나눠 담는 ETF보다 변동이 크기 때문에, 한 종목에 자산을 몰아넣지 않는 것이 중요합니다.",
  배당주:
    "배당주는 이익의 일부를 주주에게 정기적으로 나눠주는 기업입니다. 주가 상승이 크지 않아도 배당으로 수익이 발생할 수 있습니다.",
  ETF: "ETF는 여러 기업에 나눠 투자하는 상품입니다. 한 기업이 흔들려도 전체 영향은 희석돼서, 개별주보다 변동이 작습니다.",
};

export function StockDetailClient({ detail }: { detail: StockDetail }) {
  const { isLoggedIn, user } = useAuth();
  const { rate: usdKrwRate } = useExchangeRate();
  const [candleUnit, setCandleUnit] = useState<"일봉" | "1분봉">("일봉");
  const [period, setPeriod] = useState<"1개월" | "6개월" | "1년">("6개월");
  const [side, setSide] = useState<"매수" | "매도">("매수");
  const [quantityInput, setQuantityInput] = useState("10");
  const [modalOpen, setModalOpen] = useState(false);
  const [orderResult, setOrderResult] = useState<string | null>(null);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // 실패한 주문을 재시도할 때 이 clientOrderId를 재사용할지, 새로 발급할지는
  // 백엔드가 응답에 실어주는 retryPolicy로 결정한다 (lib/order-retry-policy.ts 참고).
  // null이면 "지금 이 주문 시도는 끝났다" — 다음 제출 때 완전히 새로 발급한다.
  const [clientOrderId, setClientOrderId] = useState<string | null>(null);

  const quantity = Math.max(0, Math.floor(Number(quantityInput) || 0));
  const isUp = detail.changeAmount >= 0;
  const holding = getHolding(detail.symbol);
  const availableQuantity = holding?.quantity ?? 0;

  const chartPoints = useMemo(
    () => getMockChartPoints(detail.symbol + candleUnit + period),
    [detail.symbol, candleUnit, period]
  );
  const polyline = chartPoints
    .map((v, i) => `${(i / (chartPoints.length - 1)) * 600},${145 - v}`)
    .join(" ");

  const amount = calculateOrderAmount({
    side,
    quantity,
    price: detail.lastPrice,
    currency: detail.currency,
    usdKrwRate,
  });

  const priceLabel = detail.currency === "USD" ? formatUsd(detail.lastPrice) : formatNumber(detail.lastPrice);

  let blockReason: string | null = null;
  if (!detail.tradable) {
    blockReason = detail.tradableReason ? TRADABLE_REASON_LABEL[detail.tradableReason] ?? "지금은 거래할 수 없어요" : "지금은 거래할 수 없어요";
  } else if (quantity <= 0) {
    blockReason = "수량은 1주 이상의 정수로 입력해주세요";
  } else if (side === "매도" && quantity > availableQuantity) {
    blockReason = "보유 수량이 부족해요";
  } else if (side === "매수" && amount.netAmount > AVAILABLE_CASH) {
    blockReason = "주문가능금액이 부족해요";
  }

  async function handleSubmit() {
    if (blockReason || submitting) return;
    if (!isLoggedIn || !user) {
      setModalOpen(true);
      return;
    }

    // 이전 시도의 clientOrderId가 남아있으면(SAME_CLIENT_ORDER_ID 재시도) 그대로 쓰고,
    // 없으면(첫 시도이거나 직전에 NOT_RETRYABLE로 리셋됨) 새로 발급한다.
    const idToUse = clientOrderId ?? crypto.randomUUID();

    setSubmitting(true);
    setOrderError(null);
    try {
      const response = await placeOrder(user.userId, {
        clientOrderId: idToUse,
        symbol: detail.symbol,
        marketCountry: detail.marketCountry,
        side: side === "매수" ? "BUY" : "SELL",
        quantity: quantityInput,
      });
      setOrderResult(
        `${detail.name} ${response.quantity}주 시장가 ${side} 체결 (체결가 ${response.executedPrice}` +
          `${detail.currency === "USD" ? "$" : "원"} · 총 ${side === "매수" ? "차감" : "입금"}액 ` +
          `${formatNumber(Number(response.netAmount))}원)`
      );
      setClientOrderId(null); // 성공했으니 다음 주문은 완전히 새로 시작한다.
    } catch (err) {
      if (err instanceof ApiError) {
        setOrderError(err.message);
        setClientOrderId(nextClientOrderId(err.retryPolicy, idToUse));
      } else {
        setOrderError("주문 처리 중 오류가 발생했어요.");
        setClientOrderId(idToUse); // 정책 정보가 없는 예상 밖 오류는 안전하게 같은 ID로 재시도 허용.
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="p-6">
      <div className="mb-3.5 text-[11.5px] text-gray-400">
        <span className="cursor-pointer hover:text-gray-700">주식 종목 랭킹</span> › {detail.name}
      </div>

      <div className="flex gap-4">
        <div className="flex-[1.9]">
          {detail.warning && (
            <div className="mb-3.5 rounded-md border border-gray-300 bg-gray-50 px-3.5 py-2 text-[12.5px] text-gray-700">
              ⚠ 이 종목은 {detail.warning}
            </div>
          )}

          <div className="mb-1.5 flex items-end gap-3.5">
            <div>
              <div className="text-[19px] font-bold text-gray-900">
                {detail.name} <Tag>{detail.symbol}</Tag> <Tag>{detail.market}</Tag>{" "}
                <Tag variant="dark">{detail.category}</Tag>
              </div>
              <div className="mt-1 text-[29px] font-bold text-gray-900">
                {priceLabel}{" "}
                <span className={`text-[15px] font-semibold ${isUp ? "text-gray-900" : "text-gray-400"}`}>
                  {isUp ? "▲" : "▼"}{" "}
                  {detail.currency === "USD" ? formatUsd(detail.changeAmount) : formatSigned(detail.changeAmount)} (
                  {formatPercent(detail.changeRate)})
                </span>
              </div>
            </div>
            <div className="ml-auto text-right text-[11.5px] text-gray-400">
              2026-08-25 12:36:59 기준 · 5초마다 갱신
              <br />
              조회는 장 시간과 무관하게 항상 가능
            </div>
          </div>

          {/* 세그먼트 토글 */}
          <div className="my-4.5 flex flex-wrap items-center gap-2.5">
            <div className="inline-flex overflow-hidden rounded-md border border-gray-300">
              {(["일봉", "1분봉"] as const).map((u) => (
                <button
                  key={u}
                  onClick={() => setCandleUnit(u)}
                  className={`border-r border-gray-300 px-3.5 py-1 text-[12.5px] last:border-r-0 ${
                    candleUnit === u ? "bg-gray-900 text-white" : "text-gray-500 hover:bg-gray-50"
                  }`}
                >
                  {u}
                </button>
              ))}
            </div>
            {candleUnit === "일봉" && (
              <div className="inline-flex overflow-hidden rounded-md border border-gray-300">
                {(["1개월", "6개월", "1년"] as const).map((p) => (
                  <button
                    key={p}
                    onClick={() => setPeriod(p)}
                    className={`border-r border-gray-300 px-2.5 py-0.5 text-[11.5px] last:border-r-0 ${
                      period === p ? "bg-gray-900 text-white" : "text-gray-500 hover:bg-gray-50"
                    }`}
                  >
                    {p}
                  </button>
                ))}
              </div>
            )}
            <span className="ml-auto text-[11.5px] text-gray-400">
              {candleUnit === "일봉" ? `일봉 · ${period} · 08/25 종가까지` : "1분봉 · 최근 200봉"}
            </span>
          </div>

          <div className="mb-4 flex h-[200px] items-center justify-center rounded-md border border-dashed border-gray-300 bg-gray-50">
            <svg width="94%" height="145" viewBox="0 0 600 145" preserveAspectRatio="none">
              <polyline points={polyline} fill="none" stroke="#9ca3af" strokeWidth={2} />
            </svg>
          </div>

          <div className="mb-3.5 rounded-lg border border-gray-200 p-4">
            <h4 className="mb-2 text-[14px] font-semibold text-gray-900">
              이 종목은 어떤 주식인가요? <Tag variant="dark">{detail.category}</Tag>
            </h4>
            <p className="text-[13px] leading-relaxed text-gray-600">
              {CATEGORY_GUIDE[detail.category] ?? detail.description}
            </p>
          </div>

          <div className="rounded-lg border border-gray-200 p-4">
            <h4 className="mb-2 text-[14px] font-semibold text-gray-900">종목 정보</h4>
            <table className="w-full text-[13px]">
              <tbody>
                <tr>
                  <td className="w-1/4 py-1 text-gray-500">상한가</td>
                  <td className="py-1 font-medium">{detail.upperLimit ? formatNumber(detail.upperLimit) : "—"}</td>
                  <td className="w-1/4 py-1 text-gray-500">하한가</td>
                  <td className="py-1 text-gray-500">{detail.lowerLimit ? formatNumber(detail.lowerLimit) : "—"}</td>
                </tr>
                <tr>
                  <td className="py-1 text-gray-500">시가총액</td>
                  <td className="py-1">{detail.marketCap}</td>
                  <td className="py-1 text-gray-500">상장주식수</td>
                  <td className="py-1">{detail.sharesOutstanding}</td>
                </tr>
                <tr>
                  <td className="py-1 text-gray-500">거래 상태</td>
                  <td className="py-1">{detail.tradable ? "정상" : "제한"}</td>
                  <td className="py-1 text-gray-500">통화</td>
                  <td className="py-1">{detail.currency}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        {/* 거래 패널 */}
        <div className="min-w-[292px] flex-1">
          <div className="rounded-lg border border-gray-200 p-4">
            <div className="mb-1 text-[14px] font-semibold text-gray-900">거래하기</div>
            <div className="mb-3.5 text-[11.5px] text-gray-400">시장가 주문 · 즉시 체결</div>

            <div className="mb-4 flex border-b border-gray-200">
              {(["매수", "매도"] as const).map((s) => (
                <button
                  key={s}
                  onClick={() => {
                    setSide(s);
                    // 주문 내용이 바뀌면 이전 clientOrderId를 그대로 재사용하면 안 된다 —
                    // "같은 ID인데 다른 내용"은 NOT_RETRYABLE(DUPLICATE_ORDER)로 거절된다.
                    setClientOrderId(null);
                    setOrderError(null);
                  }}
                  className={`flex-1 border-b-2 py-1.5 text-center text-[13px] ${
                    side === s
                      ? "border-gray-900 font-semibold text-gray-900"
                      : "border-transparent text-gray-400"
                  }`}
                >
                  {s}
                </button>
              ))}
            </div>

            <label className="text-[11.5px] text-gray-400">주문 수량</label>
            <div className="my-1 flex items-center gap-1.5">
              <input
                className="min-w-0 flex-1 rounded-md border border-gray-300 px-3 py-2 text-[13px] outline-none focus:border-gray-500"
                inputMode="numeric"
                value={quantityInput}
                onChange={(e) => {
                  setQuantityInput(e.target.value.replace(/[^0-9]/g, ""));
                  setClientOrderId(null);
                  setOrderError(null);
                }}
              />
              <span className="flex-none text-[11.5px] text-gray-400">주</span>
            </div>
            <div className="mb-3.5 text-[11.5px] text-gray-400">
              정수만 입력 · 최소 1주
              {side === "매도" && ` · 보유 ${availableQuantity}주`}
            </div>

            <div className="mb-3.5 flex justify-between text-[11.5px] text-gray-500">
              <span>체결 예상 단가</span>
              <span>{priceLabel} (현재가)</span>
            </div>

            <div className="mb-3.5 rounded-md bg-gray-50 p-3.5 text-[12.5px]">
              <div className="mb-1 flex justify-between">
                <span className="text-gray-500">주문 금액</span>
                <b>{formatNumber(amount.grossAmount)}</b>
              </div>
              <div className="mb-1 flex justify-between">
                <span className="text-gray-500">수수료 0.01%</span>
                <span>{formatNumber(amount.fee)}</span>
              </div>
              <div className="mb-1.5 flex justify-between">
                <span className="text-gray-500">
                  세금{" "}
                  <span className="text-[11px]">
                    ({side === "매수" ? "매수는 없음" : detail.marketCountry === "KR" ? "증권거래세 0.2%" : "SEC Fee"})
                  </span>
                </span>
                <span>{formatNumber(amount.tax)}</span>
              </div>
              <div className="flex justify-between border-t border-gray-200 pt-1.5">
                <b>{side === "매수" ? "총 차감액" : "총 입금액"}</b>
                <b>{formatNumber(amount.netAmount)}</b>
              </div>
            </div>

            <div className="mb-3 flex justify-between text-[11.5px] text-gray-500">
              <span>주문가능금액</span>
              <span>{formatNumber(AVAILABLE_CASH)}원</span>
            </div>

            {blockReason ? (
              <button
                disabled
                className="w-full cursor-not-allowed rounded-md border border-gray-200 bg-gray-100 py-2.5 text-[13px] text-gray-400"
              >
                {blockReason}
              </button>
            ) : (
              <button
                onClick={handleSubmit}
                disabled={submitting}
                className="w-full rounded-md bg-gray-900 py-2.5 text-[13px] font-medium text-white hover:bg-black disabled:cursor-not-allowed disabled:opacity-50"
              >
                {submitting ? "처리 중…" : side === "매수" ? "매수하기" : "매도하기"}
              </button>
            )}
            {!isLoggedIn && (
              <div className="mt-1.5 text-center text-[11.5px] text-gray-400">
                비로그인 상태에서 누르면 회원가입으로 안내됩니다
              </div>
            )}
            {orderError && (
              <div className="mt-3 rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-[12px] text-gray-700">
                {orderError}
                {clientOrderId && (
                  <div className="mt-1 text-[11px] text-gray-400">
                    같은 주문으로 다시 시도하시려면 버튼을 다시 눌러주세요.
                  </div>
                )}
                {!clientOrderId && (
                  <div className="mt-1 text-[11px] text-gray-400">
                    주문 내용을 확인한 뒤 다시 시도해주세요.
                  </div>
                )}
              </div>
            )}
            {orderResult && (
              <div className="mt-3 rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-[12px] text-gray-700">
                {orderResult}
              </div>
            )}
          </div>
        </div>
      </div>

      <SignupModal open={modalOpen} onClose={() => setModalOpen(false)} />
    </div>
  );
}
