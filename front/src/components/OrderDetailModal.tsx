"use client";

import { useEffect, useState } from "react";
import {
  ApiError,
  cancelOrder,
  getOrderExecutions,
  type ExecutionResponse,
  type OrderDetailResponse,
} from "@/lib/api";
import { formatNumber, formatUsd } from "@/lib/format";

const ORDER_TYPE_LABEL: Record<OrderDetailResponse["orderType"], string> = {
  MARKET: "시장가",
  LIMIT: "지정가",
};

const ORDER_STATUS_STYLE: Record<OrderDetailResponse["status"], { background: string; color: string; label: string }> = {
  PENDING: { background: "var(--accentSoft)", color: "var(--onAccentSoftText)", label: "접수" },
  PARTIALLY_FILLED: { background: "var(--warnBg)", color: "var(--warnText)", label: "부분체결" },
  FILLED: { background: "var(--upBg)", color: "var(--up)", label: "체결완료" },
  REJECTED: { background: "var(--dangerBg)", color: "var(--dangerText)", label: "거절" },
  CANCELED: { background: "var(--fill)", color: "var(--mut2)", label: "취소됨" },
  EXPIRED: { background: "var(--fill)", color: "var(--mut2)", label: "만료" },
};

export function OrderSideBadge({ side }: { side: OrderDetailResponse["side"] }) {
  // LedgerBadge(마이페이지 체결 내역)와 같은 배색 규칙을 그대로 따른다 — 매수는 downBg/down,
  // 매도는 upBg/up.
  const style = side === "BUY" ? { background: "var(--downBg)", color: "var(--down)" } : { background: "var(--upBg)", color: "var(--up)" };
  return (
    <span className="w-fit rounded-md px-2.5 py-1 text-[12px] font-bold" style={style}>
      {side === "BUY" ? "매수" : "매도"}
    </span>
  );
}

export function OrderStatusBadge({ status }: { status: OrderDetailResponse["status"] }) {
  const s = ORDER_STATUS_STYLE[status];
  return (
    <span className="w-fit rounded-md px-2.5 py-1 text-[12px] font-bold" style={{ background: s.background, color: s.color }}>
      {s.label}
    </span>
  );
}

function Row({ label, value, valueStyle }: { label: string; value: string; valueStyle?: React.CSSProperties }) {
  return (
    <div className="mb-1.5 flex justify-between last:mb-0">
      <span style={{ color: "var(--mut)" }}>{label}</span>
      <span style={{ color: "var(--ink)", ...valueStyle }}>{value}</span>
    </div>
  );
}

/**
 * 주문 1건의 상세 정보 + 체결(부분 체결 포함) 내역을 보여주는 팝업.
 * PENDING/PARTIALLY_FILLED 주문은 이 안에서 바로 취소할 수 있다(PATCH /api/orders/{orderId}).
 * 부모(마이페이지 주문 내역 탭)가 이미 갖고 있는 OrderDetailResponse를 그대로 받아 쓰고,
 * 체결 내역만 이 컴포넌트가 직접 조회한다 — 목록 응답에 이미 전체 상세가 담겨 있어
 * 상세를 다시 조회할 필요가 없다.
 */
export function OrderDetailModal({
  order,
  onClose,
  onUpdated,
}: {
  order: OrderDetailResponse;
  onClose: () => void;
  /** 취소가 성공해 주문 상태가 바뀌면 부모 목록도 갱신할 수 있도록 최신 주문을 넘긴다. */
  onUpdated: (order: OrderDetailResponse) => void;
}) {
  const [executions, setExecutions] = useState<ExecutionResponse[]>([]);
  const [execLoading, setExecLoading] = useState(true);
  const [execCursor, setExecCursor] = useState<string | null>(null);
  const [execHasNext, setExecHasNext] = useState(false);
  const [execLoadingMore, setExecLoadingMore] = useState(false);
  const [canceling, setCanceling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setExecLoading(true);
    getOrderExecutions(order.orderId)
      .then((res) => {
        if (cancelled) return;
        setExecutions(res.items);
        setExecCursor(res.nextCursor);
        setExecHasNext(res.hasNext);
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setExecLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [order.orderId]);

  function loadMoreExecutions() {
    if (execLoadingMore || !execCursor) return;
    setExecLoadingMore(true);
    getOrderExecutions(order.orderId, { cursor: execCursor })
      .then((res) => {
        setExecutions((prev) => [...prev, ...res.items]);
        setExecCursor(res.nextCursor);
        setExecHasNext(res.hasNext);
      })
      .catch(() => {})
      .finally(() => setExecLoadingMore(false));
  }

  const cancelable = order.status === "PENDING" || order.status === "PARTIALLY_FILLED";
  const isUsd = order.marketCountry === "US";

  async function handleCancel() {
    if (canceling) return;
    setCanceling(true);
    setCancelError(null);
    try {
      const updated = await cancelOrder(order.orderId);
      onUpdated(updated);
    } catch (err) {
      setCancelError(err instanceof ApiError ? err.message : "주문 취소에 실패했어요.");
    } finally {
      setCanceling(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-[150] flex items-center justify-center px-4"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
      onClick={onClose}
    >
      <div
        className="max-h-[85vh] w-full max-w-[480px] overflow-y-auto rounded-[24px] p-7"
        style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-1 flex items-start justify-between gap-3">
          <h3 className="text-[18px] font-bold" style={{ color: "var(--ink)" }}>
            {order.name} <span className="text-[13px] font-normal" style={{ color: "var(--mut2)" }}>{order.symbol}</span>
          </h3>
          <button type="button" onClick={onClose} className="shrink-0 cursor-pointer text-[13px]" style={{ color: "var(--mut2)" }}>
            닫기
          </button>
        </div>

        <div className="mb-4 flex flex-wrap gap-1.5">
          <OrderSideBadge side={order.side} />
          <span className="w-fit rounded-md px-2.5 py-1 text-[12px] font-bold" style={{ background: "var(--fill)", color: "var(--ink)" }}>
            {ORDER_TYPE_LABEL[order.orderType]}
          </span>
          <OrderStatusBadge status={order.status} />
        </div>

        <div className="mb-4 rounded-xl p-4 text-[13.5px]" style={{ background: "var(--fill)" }}>
          <Row label="수량" value={`${formatNumber(order.filledQuantity)} / ${formatNumber(order.quantity)}주`} />
          {order.orderType === "LIMIT" && (
            <Row
              label="지정가"
              value={order.requestedLimitCurrency === "USD" ? formatUsd(order.requestedLimitPrice) : `${formatNumber(order.requestedLimitPrice)}원`}
            />
          )}
          <Row label="주문금액" value={`${formatNumber(order.grossAmount)}원`} />
          <Row label="수수료" value={`${formatNumber(order.fee)}원`} />
          <Row label="세금" value={`${formatNumber(order.tax)}원`} />
          <Row label="예약금액" value={`${formatNumber(order.reservedCash)}원`} />
          <Row label="주문시각" value={new Date(order.orderedAt).toLocaleString("ko-KR")} />
          {order.expiresAt && <Row label="만료시각" value={new Date(order.expiresAt).toLocaleString("ko-KR")} />}
          {order.closedAt && <Row label="종료시각" value={new Date(order.closedAt).toLocaleString("ko-KR")} />}
          {order.rejectReason && <Row label="거절사유" value={order.rejectReason} valueStyle={{ color: "var(--dangerText)" }} />}
        </div>

        <div className="mb-2 text-[13px] font-bold" style={{ color: "var(--mut)" }}>
          체결 내역
        </div>
        {execLoading ? (
          <div className="py-6 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
            불러오는 중…
          </div>
        ) : executions.length === 0 ? (
          <div className="py-6 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
            아직 체결된 내역이 없어요
          </div>
        ) : (
          <div className="mb-2 overflow-hidden rounded-xl" style={{ border: "1px solid var(--line2)" }}>
            {executions.map((ex) => (
              <div
                key={ex.executionId}
                className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 px-3.5 py-2.5 text-[12.5px]"
                style={{ borderBottom: "1px solid var(--line2)" }}
              >
                <span style={{ color: "var(--mut2)" }}>#{ex.sequenceNo}</span>
                <span style={{ color: "var(--ink)" }}>
                  {formatNumber(ex.quantity)}주 @ {isUsd ? formatUsd(ex.price) : `${formatNumber(ex.price)}원`}
                </span>
                <span style={{ color: "var(--ink)" }}>{formatNumber(ex.netAmount)}원</span>
                <span style={{ color: "var(--mut2)" }}>
                  {new Date(ex.executedAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
                </span>
              </div>
            ))}
          </div>
        )}
        {execHasNext && (
          <button
            type="button"
            onClick={loadMoreExecutions}
            disabled={execLoadingMore}
            className="mb-4 w-full cursor-pointer rounded-lg py-2 text-[12.5px] font-bold disabled:cursor-not-allowed disabled:opacity-60"
            style={{ background: "var(--fill)", color: "var(--ink)" }}
          >
            {execLoadingMore ? "불러오는 중…" : "더 보기"}
          </button>
        )}

        {cancelable && (
          <>
            {cancelError && (
              <p className="mb-2 text-[12.5px]" style={{ color: "var(--dangerText)" }}>
                {cancelError}
              </p>
            )}
            <button
              type="button"
              onClick={handleCancel}
              disabled={canceling}
              className="w-full cursor-pointer rounded-xl py-3 text-[14px] font-bold disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--dangerBg)", color: "var(--dangerText)" }}
            >
              {canceling ? "취소하는 중…" : "주문 취소"}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
