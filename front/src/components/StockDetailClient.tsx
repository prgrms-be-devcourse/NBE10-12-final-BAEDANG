"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Tag } from "./Tag";
import { SignupModal } from "./SignupModal";
import { PillTabs } from "./PillTabs";
import { CandleChartSection } from "./CandleChartSection";
import { ChartExpandModal } from "./ChartExpandModal";
import { TourGuide, type TourStep } from "./TourGuide";
import { useAuth } from "./AuthProvider";
import { useExchangeRate } from "./ExchangeRateProvider";
import { useTheme } from "./ThemeProvider";
import { INITIAL_CASH } from "@/lib/mock-data";
import { CATEGORY_BADGE_STYLE, categoryLabel } from "@/lib/category-badge";
import { calculateOrderAmount } from "@/lib/order-amount";
import { formatKoreanAmount, formatNumber, formatPercent, formatSigned, formatUsd, toDecimal } from "@/lib/format";
import {
  ApiError,
  getAccountSummary,
  getCandles,
  getHoldings,
  placeOrder,
  type AccountSummary,
  type Candle,
  type CandleInterval,
  type CandleRange,
  type HoldingItem,
  type StockDetail,
} from "@/lib/api";
import { generateClientOrderId, nextClientOrderId } from "@/lib/order-retry-policy";

const TRADABLE_REASON_LABEL: Record<string, string> = {
  MARKET_CLOSED: "장 마감 · 거래 시간이 아니에요",
  NOT_IN_UNIVERSE: "이 종목은 아직 거래를 지원하지 않아요",
  SUSPENDED: "거래정지 종목이에요",
  LIQUIDATION: "정리매매 종목이에요",
  QUOTE_NOT_FOUND: "시세 정보가 아직 없어요",
};

// 이 화면을 처음 보는 사용자를 위한 안내 투어. localStorage에 한 번 완료/건너뛰기
// 기록을 남기면 다음 방문부터는 자동으로 뜨지 않는다("거래하기" 옆 안내 버튼으로
// 언제든 다시 볼 수 있다). 버전을 파일명처럼 접미사로 두면, 나중에 단계 구성이
// 크게 바뀌었을 때 키만 올려서 기존 사용자에게도 새 투어를 다시 보여줄 수 있다.
const STOCK_DETAIL_TOUR_STORAGE_KEY = "stockDetailTourSeen_v1";

// 차트 → 차트 조작 → 매수/매도 체험 순서로, 화면을 위에서 아래로 훑으면서 실제
// 모의 매수/매도 체결까지 눌러보게 이어지는 흐름이다(마지막 단계가 자연스러운 클라이맥스).
const STOCK_DETAIL_TOUR_STEPS: TourStep[] = [
  {
    target: '[data-tour="chart"]',
    title: "캔들 차트 읽는 법",
    description:
      "빨간 캔들은 시작가보다 오른 날,\n파란 캔들은 내린 날이에요.\n위아래로 튀어나온 얇은 선(꼬리)은\n그날의 최고가·최저가를 보여줘요.",
  },
  {
    target: '[data-tour="candle-toggle"]',
    title: "기간 바꿔보기",
    description:
      "일봉은 하루 단위, 1분봉은 1분 단위로\n가격 흐름을 보여줘요.\n1개월·6개월·1년 버튼으로 더 긴 흐름도 볼 수 있어요.\n눌러서 바꿔볼까요?",
  },
  {
    target: '[data-tour="chart-expand"]',
    title: "차트 크게 보기",
    description:
      "이 버튼을 누르면 차트를 더 크게 확대해서 볼 수\n있어요. 작은 캔들 하나하나의 움직임이나 특정\n구간의 흐름을 더 꼼꼼히 살펴보기 좋아요.\n확대 화면에서도 일봉·1분봉, 기간은 그대로\n바꿀 수 있어요. 한번 눌러보세요.",
  },
  {
    target: '[data-tour="side-toggle"]',
    title: "매수 · 매도 선택",
    description: "이 종목을 살지(매수) 팔지(매도) 고르는 곳이에요.\n지금은 매수가 선택되어 있어요.\n한번 눌러서 바꿔보세요.",
  },
  {
    target: '[data-tour="quantity"]',
    title: "주문 수량 입력",
    description: "몇 주를 사고팔지 정수로 입력해요.\n클릭해서 원하는 수량을 넣어보세요.",
  },
  {
    target: '[data-tour="order-summary"]',
    title: "예상 체결 내역",
    description: "수수료와 세금까지 반영한\n실제 차감·입금 예상 금액이에요.\n매수엔 세금이 없고, 매도할 때만 세금이 붙어요.",
  },
  {
    target: '[data-tour="submit"]',
    title: "매수 · 매도 체험하기",
    description:
      "이 버튼을 누르면 실제로 모의 주문이 즉시 체결돼요.\n가상의 돈으로 하는 연습이니 걱정 말고\n눌러서 체험해보세요!\n다음에 또 안내가 필요하시면 상단의 '화면 가이드'\n버튼을 눌러주세요.",
  },
];

const CATEGORY_GUIDE: Record<string, string> = {
  개별주:
    "개별주는 특정 기업 한 곳의 지분을 사는 거예요. 그 회사가 잘되면 오르고 어려워지면 내려요. 여러 기업에 나눠 담는 ETF보다 변동이 크기 때문에, 한 종목에 자산을 몰아넣지 않는 게 중요해요.",
  배당주:
    "배당주는 이익의 일부를 주주에게 정기적으로 나눠주는 기업이에요. 주가 상승이 크지 않아도 배당으로 수익이 발생할 수 있어요.",
  ETF: "ETF는 여러 기업에 나눠 투자하는 상품이에요. 한 기업이 흔들려도 전체 영향은 희석돼서, 개별주보다 변동이 작아요.",
};

/** 화면에 넣을 캔들 구간 옵션 → 백엔드 interval/range 쌍. 1분봉은 반드시 range=1D (CandleQueryPolicy). */
function toCandleQuery(candleUnit: "일봉" | "1분봉", period: "1개월" | "6개월" | "1년"): { interval: CandleInterval; range: CandleRange } {
  if (candleUnit === "1분봉") return { interval: "1m", range: "1D" };
  const range: CandleRange = period === "1개월" ? "1M" : period === "6개월" ? "6M" : "1Y";
  return { interval: "1d", range };
}

export function StockDetailClient({ detail }: { detail: StockDetail }) {
  const { isLoggedIn, user } = useAuth();
  const { rate: usdKrwRate, updatedAt: exchangeRateUpdatedAt } = useExchangeRate();
  const { theme } = useTheme();
  const [account, setAccount] = useState<AccountSummary | null>(null);
  const [holdings, setHoldings] = useState<HoldingItem[]>([]);
  const [candleUnit, setCandleUnit] = useState<"일봉" | "1분봉">("일봉");
  const [period, setPeriod] = useState<"1개월" | "6개월" | "1년">("6개월");
  const [candleItems, setCandleItems] = useState<Candle[]>([]);
  const [candleLoading, setCandleLoading] = useState(true);
  const [chartExpanded, setChartExpanded] = useState(false);
  const [tourActive, setTourActive] = useState(false);
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

  const categoryLabelValue = categoryLabel(detail.category, detail.isDividend);
  const changeDecimal = toDecimal(detail.price.changeAmount);
  const isUp = !changeDecimal || changeDecimal.greaterThanOrEqualTo(0);
  const warningLabel = detail.warnings.find((w) => w.type === "INVESTMENT_WARNING")?.label ?? null;

  // 투자경고 배너("이 종목은 ... 매수 전 확인하세요")가 있으면 왼쪽 컬럼은
  // breadcrumb → 배너 → 종목명 순으로 내려가는데, 오른쪽 거래하기 패널이 왼쪽
  // 컬럼과 같은 높이(맨 위, breadcrumb 높이)에서 시작하면 배너보다 훨씬 위에
  // 떠서 "나란히"가 아니라 배너보다 위에 있는 것처럼 보인다. 배너와 나란히
  // 시작하게 하려면, 배너 앞에 있는 breadcrumb의 실제 렌더 높이(+ 아래 여백)만큼만
  // 거래하기 패널을 내리면 된다 — 배너 자체의 높이가 아니라 배너 "앞"의 높이다.
  const breadcrumbRef = useRef<HTMLDivElement>(null);
  const [measuredBreadcrumbHeight, setMeasuredBreadcrumbHeight] = useState(0);
  // 경고가 없는 종목은 애초에 거래하기 패널이 breadcrumb과 같은 높이에서 시작해도
  // 문제없으므로(맞춰야 할 배너 자체가 없다), 이 경우엔 오프셋을 적용하지 않는다.
  const tradePanelOffset = warningLabel ? measuredBreadcrumbHeight : 0;

  function measureBreadcrumbHeight() {
    const el = breadcrumbRef.current;
    if (el) setMeasuredBreadcrumbHeight(el.offsetHeight + 14); // mb-3.5(14px) = breadcrumb과 배너 사이 여백
  }

  useLayoutEffect(measureBreadcrumbHeight, [warningLabel]);

  useEffect(() => {
    const el = breadcrumbRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measureBreadcrumbHeight);
    observer.observe(el);
    return () => observer.disconnect();
  }, [warningLabel]);

  // 처음 방문하는 사용자만 자동으로 안내 투어를 띄운다 — 완료/건너뛰기 기록이
  // 없을 때만 시작한다("거래하기" 옆 안내 버튼으로 언제든 다시 볼 수 있다).
  useEffect(() => {
    try {
      if (!localStorage.getItem(STOCK_DETAIL_TOUR_STORAGE_KEY)) {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setTourActive(true);
      }
    } catch {
      // localStorage를 못 쓰는 환경이면 그냥 투어를 띄우지 않는다.
    }
  }, []);

  useEffect(() => {
    if (!isLoggedIn || !user) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setAccount(null);
      setHoldings([]);
      return;
    }
    let cancelled = false;
    getAccountSummary(user.userId)
      .then((acc) => {
        if (!cancelled) setAccount(acc);
      })
      .catch(() => {
        if (!cancelled) setAccount(null);
      });
    getHoldings(user.userId)
      .then((res) => {
        if (!cancelled) setHoldings(res.items);
      })
      .catch(() => {
        if (!cancelled) setHoldings([]);
      });
    return () => {
      cancelled = true;
    };
  }, [isLoggedIn, user]);

  // 캔들 차트 — 세그먼트(일봉/1분봉, 기간)가 바뀔 때마다 다시 조회한다.
  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCandleLoading(true);
    const { interval, range } = toCandleQuery(candleUnit, period);
    getCandles(detail.symbol, detail.marketCountry, interval, range)
      .then((data) => {
        if (!cancelled) setCandleItems(data.items);
      })
      .catch(() => {
        if (!cancelled) setCandleItems([]);
      })
      .finally(() => {
        if (!cancelled) setCandleLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [detail.symbol, detail.marketCountry, candleUnit, period]);

  const quantity = Math.max(0, Math.floor(Number(quantityInput) || 0));
  const holding = holdings.find((h) => h.symbol === detail.symbol);
  const availableQuantity = holding ? Number(holding.quantity) : 0;
  const availableCash = account ? Number(account.cashBalance) : INITIAL_CASH;

  const lastCandleAt = candleItems.length > 0 ? candleItems[candleItems.length - 1].at : null;

  const amount = calculateOrderAmount({
    side,
    quantity,
    price: detail.price.lastPrice ?? 0,
    currency: detail.currency === "USD" ? "USD" : "KRW",
    usdKrwRate,
  });

  // 정책상 거래는 원화로만 이뤄지므로(rankings/my 화면과 동일한 원칙), 미국 종목도
  // 원화 환산액을 먼저 크게 보여주고 원래 달러 값은 보조 텍스트로 뒤에 붙인다.
  const isUsdStock = detail.currency === "USD";
  const lastPriceKrw = isUsdStock ? toDecimal(detail.price.lastPrice)?.times(usdKrwRate) ?? null : toDecimal(detail.price.lastPrice);
  const changeKrw = isUsdStock ? changeDecimal?.times(usdKrwRate) ?? null : changeDecimal;
  const priceLabel = formatNumber(lastPriceKrw);

  let blockReason: string | null = null;
  if (!detail.tradable) {
    blockReason = detail.tradableReason ? TRADABLE_REASON_LABEL[detail.tradableReason] ?? "지금은 거래할 수 없어요" : "지금은 거래할 수 없어요";
  } else if (quantity <= 0) {
    blockReason = "수량은 1주 이상의 정수로 입력해주세요";
  } else if (side === "매도" && quantity > availableQuantity) {
    blockReason = "보유 수량이 부족해요";
  } else if (side === "매수" && amount.netAmount > availableCash) {
    blockReason = "주문가능금액이 부족해요";
  }

  // 매수/매도 필박스 색 — 예전에는 --up/--down 토큰을 그대로 썼는데, 그 토큰을
  // 차트용으로 더 선명하게 조정한 뒤(globals.css) 이 버튼만은 예전 색이 더 낫다는
  // 피드백을 받아 여기서만 원래 값을 그대로 고정한다(차트·랭킹 등락 배지 등
  // --up/--down을 공유하는 나머지 화면은 그대로 새 색을 쓴다).
  const txPillColor =
    side === "매도"
      ? theme === "dark"
        ? "oklch(70% 0.13 232)"
        : "oklch(56% 0.16 236)"
      : theme === "dark"
        ? "oklch(56% 0.17 20)"
        : "oklch(58% 0.2 25)";

  async function handleSubmit() {
    if (blockReason || submitting) return;
    if (!isLoggedIn || !user) {
      setModalOpen(true);
      return;
    }

    setSubmitting(true);
    setOrderError(null);

    // 계좌 정보가 아직 로드되지 않은 경우 최신 정보를 조회한다.
    let currentAccount = account;
    if (!currentAccount) {
      try {
        currentAccount = await getAccountSummary(user.userId);
        setAccount(currentAccount);
      } catch {
        setOrderError("계좌 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요.");
        setSubmitting(false);
        return;
      }
    }

    // 이전 시도의 clientOrderId가 남아있으면(SAME_CLIENT_ORDER_ID 재시도) 그대로 쓰고,
    // 없으면(첫 시도이거나 직전에 NOT_RETRYABLE로 리셋됨) 새로 발급한다.
    const idToUse = clientOrderId ?? generateClientOrderId();

    try {
      const response = await placeOrder(user.userId, {
        accountId: currentAccount.accountId,
        clientOrderId: idToUse,
        symbol: detail.symbol,
        marketCountry: detail.marketCountry,
        side: side === "매수" ? "BUY" : "SELL",
        quantity: quantityInput,
      });
      setOrderResult(
        `${detail.name} ${response.quantity}주 시장가 ${side} 체결 (체결가 ${response.executedPrice}` +
          `${detail.currency === "USD" ? "$" : "원"} · 총 ${side === "매수" ? "차감" : "입금"}액 ` +
          `${formatNumber(response.netAmount)}원)`
      );
      setClientOrderId(null); // 성공했으니 다음 주문은 완전히 새로 시작한다.
      // 체결 후 잔여 예수금·보유 수량 즉시 반영
      setAccount((prev) =>
        prev ? { ...prev, cashBalance: response.account.cashBalanceAfter } : null
      );
      getHoldings(user.userId).then((res) => setHoldings(res.items)).catch(() => {});
    } catch (err) {
      if (err instanceof ApiError) {
        setOrderError(err.message);
        setClientOrderId(nextClientOrderId(err.retryPolicy, idToUse));
        // 포트폴리오 초기화로 회차가 변경된 경우 계좌 정보 자동 갱신
        if (err.code === "ACCOUNT_ROUND_CHANGED" || err.code === "ACCOUNT_NOT_FOUND") {
          getAccountSummary(user.userId).then(setAccount).catch(() => {});
        }
      } else {
        setOrderError("주문 처리 중 오류가 발생했어요.");
        setClientOrderId(idToUse); // 정책 정보가 없는 예상 밖 오류는 안전하게 같은 ID로 재시도 허용.
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex gap-6 max-md:flex-col">
      <div className="flex-[1.9]">
        <div ref={breadcrumbRef} className="mb-3.5 text-[14px]" style={{ color: "var(--mut2)" }}>
          <span className="cursor-pointer">주식 종목 랭킹</span> › {detail.name}
        </div>

        {warningLabel && (
          <div
            className="mb-3.5 rounded-[14px] px-4.5 py-3.5 text-[14.5px]"
            style={
              // 라이트 모드에서만 좀 더 세련된 레드 계열(와인빛이 도는 딥레드)로 바꿔달라는
              // 요청 — 다크 모드는 기존 warnBg/warnText/warnBorder 토큰을 그대로 쓴다.
              // 이 토큰들은 주문 실패 안내(orderError) 등 다른 곳에서도 공유해서 쓰기
              // 때문에, 전역 토큰이 아니라 이 배너에만 인라인으로 색을 지정했다.
              theme === "light"
                ? { background: "oklch(95% 0.035 16)", border: "1px solid oklch(83% 0.09 16)", color: "oklch(40% 0.17 14)" }
                : { background: "var(--warnBg)", border: "1px solid var(--warnBorder)", color: "var(--warnText)" }
            }
          >
            ⚠ 이 종목은 <b>{warningLabel}</b> 종목으로 지정되어 있습니다. 매수 전 확인하세요.
          </div>
        )}

        <div className="mb-1.5">
          <div className="text-[20px] font-bold" style={{ color: "var(--ink)" }}>
            {detail.name} <Tag weightClassName="font-bold">{detail.symbol}</Tag>{" "}
            <Tag weightClassName="font-bold">{detail.market}</Tag>{" "}
            <span
              className="inline-block rounded-md px-1.5 py-0.5 align-middle text-[11.5px] font-bold"
              style={CATEGORY_BADGE_STYLE[categoryLabelValue]}
            >
              {categoryLabelValue}
            </span>
          </div>
          <div className="mt-1.5 flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
            <span className="text-[30px] font-extrabold" style={{ color: "var(--ink)" }}>
              {priceLabel}
            </span>
            {isUsdStock && (
              <span className="text-[14px] font-semibold" style={{ color: "var(--mut2)" }}>
                {formatUsd(detail.price.lastPrice)}
              </span>
            )}
            <span className="text-[16px] font-semibold" style={{ color: isUp ? "var(--up)" : "var(--down)" }}>
              {isUp ? "▲" : "▼"} {formatSigned(changeKrw)} ({formatPercent(detail.price.changeRate)})
            </span>
          </div>
          <div className="mt-1 text-[12.5px]" style={{ color: "var(--mut2)" }}>
            {detail.price.quoteAt
              ? `${new Date(detail.price.quoteAt).toLocaleString("ko-KR")} 기준`
              : "시세 정보가 아직 없어요"}{" "}
            · 조회는 장 시간과 무관하게 항상 가능
          </div>
        </div>

        <CandleChartSection
          candleUnit={candleUnit}
          onCandleUnitChange={setCandleUnit}
          period={period}
          onPeriodChange={setPeriod}
          candleItems={candleItems}
          candleLoading={candleLoading}
          theme={theme}
          lastCandleAt={lastCandleAt}
          onExpand={() => setChartExpanded(true)}
          tourIds={{ toggle: "candle-toggle", chart: "chart", expandButton: "chart-expand" }}
        />

        {chartExpanded && (
          <ChartExpandModal
            name={detail.name}
            symbol={detail.symbol}
            market={detail.market}
            candleUnit={candleUnit}
            onCandleUnitChange={setCandleUnit}
            period={period}
            onPeriodChange={setPeriod}
            candleItems={candleItems}
            candleLoading={candleLoading}
            theme={theme}
            lastCandleAt={lastCandleAt}
            onClose={() => setChartExpanded(false)}
          />
        )}

        <div className="mb-3.5 rounded-[20px] p-5.5" style={{ background: "var(--card)" }}>
          <h4 className="mb-2.5 text-[16px] font-bold" style={{ color: "var(--ink)" }}>
            이 종목은 어떤 주식인가요?{" "}
            <span
              className="inline-block rounded-md px-1.5 py-0.5 align-middle text-[11.5px] font-medium"
              style={CATEGORY_BADGE_STYLE[categoryLabelValue]}
            >
              {categoryLabelValue}
            </span>
          </h4>
          <p className="text-[14.5px] leading-relaxed" style={{ color: "var(--body)" }}>
            {CATEGORY_GUIDE[categoryLabelValue]}
          </p>
          {categoryLabelValue === "ETF" ? (
            <div className="mt-2.5 text-[13.5px]" style={{ color: "var(--mut2)" }}>
              구성 종목 비중 정보는 준비 중이에요
            </div>
          ) : (
            <div className="mt-2.5 text-[13.5px]" style={{ color: "var(--mut2)" }}>
              ETF 종목이라면 이 자리에 <b>구성 종목 비중</b>이 표시돼요
            </div>
          )}
        </div>

        <div className="rounded-[20px] p-5.5" style={{ background: "var(--card)" }}>
          <h4 className="mb-2 text-[16px] font-bold" style={{ color: "var(--ink)" }}>
            종목 정보
          </h4>
          <table className="w-full text-[14.5px]">
            <tbody>
              <tr>
                <td className="w-1/4 py-1" style={{ color: "var(--mut)" }}>상한가</td>
                <td className="py-1 font-bold" style={{ color: "var(--up)" }}>{formatNumber(detail.price.upperLimit, "—")}</td>
                <td className="w-1/4 py-1" style={{ color: "var(--mut)" }}>하한가</td>
                <td className="py-1 font-bold" style={{ color: "var(--down)" }}>{formatNumber(detail.price.lowerLimit, "—")}</td>
              </tr>
              <tr>
                <td className="py-1" style={{ color: "var(--mut)" }}>시가총액</td>
                <td className="py-1" style={{ color: "var(--ink)" }}>
                  {formatKoreanAmount(detail.info.marketCap, "—")}
                  {detail.currency === "USD" && detail.info.marketCap ? " 달러" : ""}
                </td>
                <td className="py-1" style={{ color: "var(--mut)" }}>상장주식수</td>
                <td className="py-1" style={{ color: "var(--ink)" }}>{formatNumber(detail.info.sharesOutstanding, "—")}</td>
              </tr>
              <tr>
                <td className="py-1" style={{ color: "var(--mut)" }}>거래 상태</td>
                <td className="py-1" style={{ color: "var(--ink)" }}>{detail.tradable ? "정상" : "제한"}</td>
                <td className="py-1" style={{ color: "var(--mut)" }}>통화</td>
                <td className="py-1" style={{ color: "var(--ink)" }}>{detail.currency}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      {/* 거래 패널 */}
      <div
        className="min-w-[300px] flex-1 self-start md:sticky md:top-[70px]"
        style={{ marginTop: tradePanelOffset }}
      >
        <div className="rounded-[24px] p-6" style={{ background: "var(--card)" }}>
          <div className="mb-1 flex items-center justify-between">
            <span className="text-[16px] font-bold" style={{ color: "var(--ink)" }}>거래하기</span>
            <button
              type="button"
              onClick={() => setTourActive(true)}
              className="tour-replay-btn flex cursor-pointer items-center gap-1 text-[12px] font-bold"
            >
              {/* 이모지(❔)는 폰트가 자체 색을 입혀서 라이트 모드에서 흐리게 보였다 —
                  currentColor를 쓰는 SVG로 바꿔서 버튼 글자색(라이트/다크 각각의
                  --mut2/hover 색)을 그대로 따라가게 한다. */}
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="10" />
                <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
                <line x1="12" y1="17" x2="12.01" y2="17" />
              </svg>
              화면 가이드
            </button>
          </div>
          <div className="mb-3.5 text-[13.5px] font-bold" style={{ color: "var(--mut2)" }}>시장가 주문 · 즉시 체결</div>

          <div data-tour="side-toggle">
            <PillTabs
              options={[
                { value: "매수", label: "매수" },
                { value: "매도", label: "매도" },
              ]}
              value={side}
              onChange={(v) => {
                setSide(v as "매수" | "매도");
                // 주문 내용이 바뀌면 이전 clientOrderId를 그대로 재사용하면 안 된다 —
                // "같은 ID인데 다른 내용"은 NOT_RETRYABLE(DUPLICATE_ORDER)로 거절된다.
                setClientOrderId(null);
                setOrderError(null);
              }}
              trackClassName="mb-4 w-full rounded-xl p-1"
              trackStyle={{ background: "var(--fill)" }}
              pillColor={txPillColor}
              pillRadius="9px"
              buttonClassName="rounded-[9px] py-2 text-[15px] font-bold"
              inactiveTextStyle={{ color: "var(--mut2)" }}
            />
          </div>

          <div data-tour="quantity">
            <label className="text-[13.5px] font-bold" style={{ color: "var(--mut2)" }}>주문 수량</label>
            <div className="mt-1 mb-3">
              <input
                className="w-full min-w-0 rounded-xl px-3.5 py-2.5 text-[14.5px] font-bold outline-none"
                style={{ background: "var(--fill)", color: "var(--ink)" }}
                inputMode="numeric"
                value={quantityInput}
                onChange={(e) => {
                  setQuantityInput(e.target.value.replace(/[^0-9]/g, ""));
                  setClientOrderId(null);
                  setOrderError(null);
                }}
              />
            </div>
          </div>
          {side === "매도" && (
            <div className="mb-3.5 text-[12.5px]" style={{ color: "var(--mut2)" }}>
              보유 {availableQuantity}주
            </div>
          )}

          <div className="mb-3.5 flex justify-between text-[13.5px] font-bold" style={{ color: "var(--mut)" }}>
            <span>체결 예상 단가</span>
            <span style={{ color: "var(--ink)" }}>
              {priceLabel}{isUsdStock ? ` (${formatUsd(detail.price.lastPrice)})` : ""} (현재가)
            </span>
          </div>

          <div className="mb-3.5 rounded-xl p-4" style={{ background: "var(--fill)" }} data-tour="order-summary">
            {isUsdStock && (
              <div className="mb-2 text-[11.5px]" style={{ color: "var(--mut2)" }}>
                적용 환율 {formatNumber(usdKrwRate)}원{" "}
                ({exchangeRateUpdatedAt.toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })} 기준)
              </div>
            )}
            <div className="mb-1 flex justify-between text-[13.5px]">
              <span style={{ color: "var(--mut)" }}>주문 금액</span>
              <b style={{ color: "var(--ink)" }}>{formatNumber(amount.grossAmount)}</b>
            </div>
            <div className="mb-1 flex justify-between text-[13.5px]">
              <span style={{ color: "var(--mut)" }}>수수료 0.01%</span>
              <span style={{ color: "var(--ink)" }}>{formatNumber(amount.fee)}</span>
            </div>
            <div className="mb-1.5 flex justify-between text-[13.5px]">
              <span style={{ color: "var(--mut)" }}>
                세금{" "}
                <span className="text-[12px]">
                  ({side === "매수" ? "매수는 없음" : detail.marketCountry === "KR" ? "증권거래세 0.2%" : "SEC Fee"})
                </span>
              </span>
              <span style={{ color: "var(--ink)" }}>{formatNumber(amount.tax)}</span>
            </div>
            <div className="flex justify-between pt-1.5 text-[14px]" style={{ borderTop: "1px solid var(--line)" }}>
              <b style={{ color: "var(--ink)" }}>{side === "매수" ? "총 차감액" : "총 입금액"}</b>
              <b style={{ color: "var(--ink)" }}>{formatNumber(amount.netAmount)}</b>
            </div>
          </div>

          <div className="mb-3 flex justify-between text-[13.5px] font-bold" style={{ color: "var(--mut)" }}>
            <span>주문가능금액</span>
            <span style={{ color: "var(--ink)" }}>{formatNumber(availableCash)}원</span>
          </div>

          {blockReason ? (
            <button
              disabled
              className="w-full cursor-not-allowed rounded-xl py-3 text-[15px] font-bold"
              style={{ background: "var(--fill)", color: "var(--disabledText)" }}
              data-tour="submit"
            >
              {blockReason}
            </button>
          ) : (
            <button
              onClick={handleSubmit}
              disabled={submitting}
              className="w-full rounded-xl py-3 text-[15px] font-bold text-white transition-[background] duration-200 disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--accent)" }}
              onMouseEnter={(e) => {
                if (!submitting) e.currentTarget.style.background = txPillColor;
              }}
              onMouseLeave={(e) => (e.currentTarget.style.background = "var(--accent)")}
              data-tour="submit"
            >
              {submitting ? "처리 중…" : side === "매수" ? "매수하기" : "매도하기"}
            </button>
          )}
          {!isLoggedIn && (
            <div className="mt-1.5 text-center text-[12.5px]" style={{ color: "var(--mut2)" }}>
              비로그인 상태에서 누르면 회원가입으로 안내돼요
            </div>
          )}

          <div className="my-4" style={{ borderTop: "1px dashed var(--line)" }} />
          <div className="mb-2.5 text-[13px] font-bold" style={{ color: "var(--mut)" }}>
            참고 — 주문 불가 상태 예시
          </div>
          <div className="flex flex-col gap-2.5">
            {["장 마감 · 09:00~15:30 거래 가능", "거래정지 종목", "주문가능금액 부족"].map((example) => (
              <div
                key={example}
                className="w-full rounded-xl py-3 text-center text-[14px] font-bold"
                style={{ background: "var(--fill)", color: "var(--disabledText)" }}
              >
                {example}
              </div>
            ))}
          </div>

          {orderError && (
            <div
              className="mt-3 rounded-xl px-3.5 py-3 text-[13.5px]"
              style={{ background: "var(--warnBg)", border: "1px solid var(--warnBorder)", color: "var(--warnText)" }}
            >
              {orderError}
              {clientOrderId && (
                <div className="mt-1 text-[12px]" style={{ color: "var(--mut2)" }}>
                  같은 주문으로 다시 시도하시려면 버튼을 다시 눌러주세요.
                </div>
              )}
              {!clientOrderId && (
                <div className="mt-1 text-[12px]" style={{ color: "var(--mut2)" }}>
                  주문 내용을 확인한 뒤 다시 시도해주세요.
                </div>
              )}
            </div>
          )}
          {orderResult && (
            <div
              className="mt-3 rounded-xl px-3.5 py-3 text-[13.5px]"
              style={{ background: "var(--accentSoft)", color: "var(--onAccentSoftText)" }}
            >
              {orderResult}
            </div>
          )}
        </div>
      </div>

      <SignupModal open={modalOpen} onClose={() => setModalOpen(false)} />

      <TourGuide
        steps={STOCK_DETAIL_TOUR_STEPS}
        storageKey={STOCK_DETAIL_TOUR_STORAGE_KEY}
        active={tourActive}
        onFinish={() => setTourActive(false)}
      />
    </div>
  );
}
