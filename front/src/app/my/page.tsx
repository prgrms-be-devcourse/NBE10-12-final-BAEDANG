"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Tag } from "@/components/Tag";
import { PillTabs } from "@/components/PillTabs";
import { Reveal } from "@/components/Reveal";
import { useAuth } from "@/components/AuthProvider";
import { useExchangeRate } from "@/components/ExchangeRateProvider";
import { useMarketStatus } from "@/components/MarketStatusProvider";
import { useTheme } from "@/components/ThemeProvider";
import {
  ApiError,
  getAccountSummary,
  getHoldings,
  getLedger,
  resetAccount,
  updateNickname,
  changeUserPassword,
  withdrawAccount,
  type AccountSummary,
  type HoldingItem,
  type LedgerItem,
  type MarketCountry,
} from "@/lib/api";
import { INITIAL_CASH } from "@/lib/mock-data";
import { formatNumber, formatPercent, formatSigned, formatUsd, toDecimal, toKrw } from "@/lib/format";
import { useVisiblePolling } from "@/lib/useVisiblePolling";

// 평가손익·평가금액은 quote_snapshot(현재가)에서 파생되는 값이고, 그 시세 자체가
// 5초 주기로 수집된다(docs/erd.md) — 그 주기에 맞춰 5초마다 다시 조회한다.
const VALUATION_POLL_INTERVAL_MS = 5000;

// toKrw는 @/lib/format 공용 함수를 쓴다. avgBuyPrice는 매수 시점 환율(avgExchangeRate)로,
// lastPrice는 최신 환율(rate)로 환산하는 게 맞다 — HoldingsResponse의 설계 의도 그대로다.

export default function MyPage() {
  const router = useRouter();
  const { isLoggedIn, user, setUser, logout } = useAuth();
  const { rate } = useExchangeRate();
  const { isOpen: isMarketOpen } = useMarketStatus();
  const { theme } = useTheme();
  const [tab, setTab] = useState<"holdings" | "ledger">("holdings");
  const [account, setAccount] = useState<AccountSummary | null>(null);
  const [holdings, setHoldings] = useState<HoldingItem[]>([]);
  const [ledger, setLedger] = useState<LedgerItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [resetModalOpen, setResetModalOpen] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [resetError, setResetError] = useState<string | null>(null);

  // ── 계정 설정(닉네임·비밀번호·탈퇴) ──────────────────────────────────────────
  const [nicknameInput, setNicknameInput] = useState(user?.nickname ?? "");
  const [nicknameSaving, setNicknameSaving] = useState(false);
  const [nicknameError, setNicknameError] = useState<string | null>(null);
  const [nicknameSaved, setNicknameSaved] = useState(false);

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newPasswordConfirm, setNewPasswordConfirm] = useState("");
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [passwordSaved, setPasswordSaved] = useState(false);

  const [withdrawModalOpen, setWithdrawModalOpen] = useState(false);
  const [withdrawPassword, setWithdrawPassword] = useState("");
  const [withdrawing, setWithdrawing] = useState(false);
  const [withdrawError, setWithdrawError] = useState<string | null>(null);

  // user는 로그인 직후엔 없다가 localStorage 복원(AuthProvider의 마운트 effect)
  // 후에야 채워질 수 있다 — 그때 닉네임 입력값의 초기값을 맞춰준다. 저장에 성공한
  // 뒤에도 user.nickname이 방금 입력한 값과 같아지므로 다시 덮어써도 문제없다.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (user) setNicknameInput(user.nickname);
  }, [user]);

  useEffect(() => {
    if (!isLoggedIn || !user) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setLoadError(false);
    Promise.all([getAccountSummary(), getHoldings(), getLedger()])
      .then(([acc, holdingsRes, ledgerRes]) => {
        if (cancelled) return;
        setAccount(acc);
        setHoldings(holdingsRes.items);
        setLedger(ledgerRes.items);
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
  }, [isLoggedIn, user]);

  // 5초마다 계좌 요약(평가손익 포함)과 보유 종목을 조용히 다시 조회해 갱신한다.
  // 체결 내역(ledger)은 실제 거래가 있을 때만 바뀌는 과거 기록이라 폴링 대상이
  // 아니다. 실패해도 화면을 에러로 덮지 않고 다음 주기에 재시도하며, 요청이
  // 겹치지 않도록 in-flight 가드를 둔다.
  // 실제로 보유한 종목의 통화만 보고 폴링 여부를 정한다(코드 리뷰, PR #124,
  // SOL4R1S님) — 국내 종목만 들고 있는데 해외 장중이라는 이유로(또는 그
  // 반대로) 5초마다 의미 없는 요청을 보내던 문제를 막는다. 둘 다 안 들고
  // 있으면(빈 포트폴리오) 애초에 갱신할 평가손익이 없어 폴링하지 않는다.
  const heldMarketCountries = new Set(holdings.map((h) => (h.currency === "USD" ? "US" : "KR")));
  const valuationPollInFlightRef = useRef(false);
  useVisiblePolling(
    () => {
      if (!user || valuationPollInFlightRef.current) return;
      valuationPollInFlightRef.current = true;
      Promise.all([getAccountSummary(), getHoldings()])
        .then(([acc, holdingsRes]) => {
          setAccount(acc);
          setHoldings(holdingsRes.items);
        })
        .catch(() => {})
        .finally(() => {
          valuationPollInFlightRef.current = false;
        });
    },
    VALUATION_POLL_INTERVAL_MS,
    isLoggedIn && !!user && [...heldMarketCountries].some((market) => isMarketOpen(market as MarketCountry))
  );

  async function handleReset() {
    if (!user || !account || resetting) return;
    setResetting(true);
    setResetError(null);
    try {
      await resetAccount(account.accountId);
      const [freshAccount, freshHoldings, freshLedger] = await Promise.all([
        getAccountSummary(),
        getHoldings(),
        getLedger(),
      ]);
      setAccount(freshAccount);
      setHoldings(freshHoldings.items);
      setLedger(freshLedger.items);
      setResetModalOpen(false);
    } catch {
      setResetError("초기화에 실패했어요. 잠시 후 다시 시도해주세요.");
    } finally {
      setResetting(false);
    }
  }

  async function handleChangeNickname(e: React.FormEvent) {
    e.preventDefault();
    if (!user || nicknameSaving) return;
    const nextNickname = nicknameInput.trim();
    setNicknameError(null);
    setNicknameSaved(false);
    if (nextNickname === user.nickname) return; // 바뀐 게 없으면 조용히 아무 것도 안 한다.
    setNicknameSaving(true);
    try {
      const profile = await updateNickname(nextNickname);
      // Nav 등 다른 화면도 user.nickname을 그대로 참조하니 여기서 같이 갱신한다.
      setUser({ ...user, nickname: profile.nickname });
      setNicknameSaved(true);
    } catch (err) {
      setNicknameError(err instanceof ApiError ? err.message : "닉네임 변경에 실패했어요.");
    } finally {
      setNicknameSaving(false);
    }
  }

  async function handleChangePassword(e: React.FormEvent) {
    e.preventDefault();
    if (passwordSaving) return;
    setPasswordError(null);
    setPasswordSaved(false);
    if (newPassword !== newPasswordConfirm) {
      setPasswordError("새 비밀번호가 서로 달라요.");
      return;
    }
    setPasswordSaving(true);
    try {
      await changeUserPassword(currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setNewPasswordConfirm("");
      setPasswordSaved(true);
    } catch (err) {
      setPasswordError(err instanceof ApiError ? err.message : "비밀번호 변경에 실패했어요.");
    } finally {
      setPasswordSaving(false);
    }
  }

  async function handleWithdraw() {
    if (withdrawing) return;
    setWithdrawError(null);
    setWithdrawing(true);
    try {
      await withdrawAccount(withdrawPassword);
      // 탈퇴는 서버 토큰을 무효화하지 않으므로(stateless JWT), 여기서 반드시
      // 로컬 로그인 상태를 지워야 한다 — 안 그러면 이미 없는 계정으로 계속
      // 요청을 보내다 에러만 반복해서 보게 된다.
      logout();
      router.push("/");
    } catch (err) {
      setWithdrawError(err instanceof ApiError ? err.message : "탈퇴 처리에 실패했어요.");
    } finally {
      setWithdrawing(false);
    }
  }

  if (!isLoggedIn || !user) {
    return (
      <Reveal delay={0} className="rounded-[20px] py-20 text-center" style={{ background: "var(--card)" }}>
        <div className="mb-2 text-[17px] font-bold" style={{ color: "var(--ink)" }}>
          로그인하고 내 계좌를 확인해보세요
        </div>
        <div className="mb-5 text-[14px]" style={{ color: "var(--mut2)" }}>
          보유 종목, 체결 내역, 모의 투자금은 로그인 후에 볼 수 있어요.
        </div>
        <div className="flex justify-center gap-2.5">
          <Link href="/login" className="rounded-xl px-5 py-2.5 text-[14px] font-bold" style={{ background: "var(--fill)", color: "var(--ink)" }}>
            로그인
          </Link>
          <Link href="/signup" className="rounded-xl px-5 py-2.5 text-[14px] font-bold text-white" style={{ background: "var(--accent)" }}>
            회원가입
          </Link>
        </div>
      </Reveal>
    );
  }

  if (loading) {
    return (
      <div className="rounded-[20px] py-20 text-center text-[13.5px]" style={{ background: "var(--card)", color: "var(--mut2)" }}>
        불러오는 중…
      </div>
    );
  }

  if (loadError || !account) {
    return (
      <div className="rounded-[20px] py-20 text-center text-[13.5px]" style={{ background: "var(--card)", color: "var(--mut2)" }}>
        계좌 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요.
      </div>
    );
  }

  return (
    <div>
      <Reveal delay={0}>
        <div className="mb-4.5 flex items-baseline gap-3">
          <h2 className="text-[28px] font-extrabold" style={{ color: "var(--ink)" }}>내 계좌</h2>
          <span className="text-[13px]" style={{ color: "var(--mut2)" }}>{account.roundNo}회차</span>
        </div>
      </Reveal>

      <Reveal delay={0.1} className="mb-6 flex gap-4 max-md:flex-col">
        <SummaryCard label="총 자산" value={formatNumber(account.totalAsset)} />
        <SummaryCard label="예수금" value={formatNumber(account.cashBalance)} />
        <SummaryCard label="주식 평가금액" value={formatNumber(account.stockValue)} />
        <SummaryCard
          label="평가손익"
          value={
            <>
              {formatSigned(account.unrealizedPnl)}{" "}
              <span className="text-[15px]">({formatPercent(account.unrealizedPnlRate)})</span>
            </>
          }
          tone={(toDecimal(account.unrealizedPnl)?.greaterThanOrEqualTo(0) ?? true) ? "up" : "down"}
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
          trackStyle={{
            background: theme === "dark" ? "rgba(255,255,255,.03)" : "rgba(15,56,104,.06)",
            border: theme === "dark" ? "1px solid rgba(255,255,255,.06)" : "1px solid rgba(15,56,104,.12)",
          }}
          buttonClassName="rounded-full px-0 py-2 text-[13px] font-bold"
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
                const avgBuyKrw = toKrw(h.avgBuyPrice, h.currency, h.avgExchangeRate);
                const lastPriceKrw = toKrw(h.lastPrice, h.currency, rate);
                const pnl = toDecimal(h.unrealizedPnl);
                const isPnlUp = !pnl || pnl.greaterThanOrEqualTo(0);
                return (
                  <div
                    key={h.symbol}
                    className="grid items-center px-5 py-3 text-[15px]"
                    style={{ gridTemplateColumns: "1.6fr 1fr 1fr 1fr 1.3fr 1.4fr 80px", borderBottom: "1px solid var(--line2)" }}
                  >
                    <span className="font-bold" style={{ color: "var(--ink)" }}>
                      {h.name} <Tag weightClassName="font-bold">{h.symbol}</Tag>
                    </span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{formatNumber(h.quantity)}</span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>
                      {formatNumber(avgBuyKrw)}
                      {isUsd && <div className="text-[10.5px]" style={{ color: "var(--mut2)" }}>{formatUsd(h.avgBuyPrice)}</div>}
                    </span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>
                      {h.lastPrice ? (
                        <>
                          {formatNumber(lastPriceKrw)}
                          {isUsd && <div className="text-[10.5px]" style={{ color: "var(--mut2)" }}>{formatUsd(h.lastPrice)}</div>}
                        </>
                      ) : (
                        "-"
                      )}
                    </span>
                    <span className="text-right tabular-nums font-bold" style={{ color: "var(--ink)" }}>{formatNumber(h.evaluationAmount)}</span>
                    <span
                      className="text-right tabular-nums font-semibold"
                      style={{ color: isPnlUp ? "var(--up)" : "var(--down)" }}
                    >
                      {formatSigned(h.unrealizedPnl)} <span className="text-[11.5px]">({formatPercent(h.unrealizedPnlRate)})</span>
                    </span>
                    <span className="text-right">
                      <Link
                        href={`/stocks/${h.symbol}?marketCountry=${isUsd ? "US" : "KR"}`}
                        className="rounded-md px-3.5 py-2 text-[13px] font-semibold"
                        style={{ background: "var(--fill)", color: "var(--ink)" }}
                      >
                        거래
                      </Link>
                    </span>
                  </div>
                );
              })}
            </div>
            <div className="mt-2.5 text-[13px]" style={{ color: "var(--mut2)" }}>
              해외 종목 평가금액은 적용 환율({formatNumber(rate)} KRW/USD)로 환산돼요
            </div>
          </>
        )
      ) : ledger.length === 0 ? (
        <div className="rounded-[20px] py-16 text-center text-[13.5px]" style={{ background: "var(--card)", color: "var(--mut2)" }}>
          체결 내역이 없어요
        </div>
      ) : (
        <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
          <div
            className="grid px-5 py-2.5 text-[12px] font-bold"
            style={{
              gridTemplateColumns: "80px 2.4fr 1fr 1fr 1.3fr",
              columnGap: "20px",
              borderBottom: "1px solid var(--line2)",
              color: "var(--mut2)",
            }}
          >
            <span>구분</span>
            <span>설명</span>
            <span className="text-right">증감액</span>
            <span className="text-right">잔액</span>
            <span>발생시각</span>
          </div>
          {ledger.map((entry) => {
            const amount = toDecimal(entry.amount);
            const isPositive = !amount || amount.greaterThanOrEqualTo(0);
            return (
              <div
                key={entry.entryId}
                className="grid items-center px-5 py-3 text-[15px]"
                style={{
                  gridTemplateColumns: "80px 2.4fr 1fr 1fr 1.3fr",
                  columnGap: "20px",
                  borderBottom: "1px solid var(--line2)",
                }}
              >
                <span>
                  <LedgerBadge type={entry.entryType} />
                </span>
                <span className="whitespace-nowrap" style={{ color: "var(--body)" }}>{entry.memo}</span>
                <span
                  className="text-right tabular-nums font-semibold"
                  style={{ color: isPositive ? "var(--up)" : "var(--down)" }}
                >
                  {formatSigned(entry.amount)}
                </span>
                <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{formatNumber(entry.balanceAfter)}</span>
                <span className="text-[11.5px] whitespace-nowrap" style={{ color: "var(--mut2)" }}>
                  {new Date(entry.occurredAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
                </span>
              </div>
            );
          })}
        </div>
      )}
      </Reveal>

      <Reveal delay={0.35} className="mt-7 rounded-[20px] p-6" style={{ background: "var(--card)" }}>
        <div className="mb-5 text-[17px] font-bold" style={{ color: "var(--ink)" }}>계정 설정</div>

        <form onSubmit={handleChangeNickname} className="mb-6">
          <label className="mb-1.5 block text-[13px] font-bold" style={{ color: "var(--mut2)" }}>닉네임</label>
          <div className="flex max-w-[360px] gap-2">
            <input
              type="text"
              required
              minLength={2}
              maxLength={20}
              value={nicknameInput}
              onChange={(e) => {
                setNicknameInput(e.target.value);
                setNicknameError(null);
                setNicknameSaved(false);
              }}
              className="w-full rounded-xl px-4 py-2.5 text-[13.5px] outline-none"
              style={{ background: "var(--fill)", color: "var(--ink)" }}
            />
            <button
              type="submit"
              disabled={nicknameSaving || nicknameInput.trim() === user.nickname}
              className="shrink-0 cursor-pointer rounded-xl px-4 py-2.5 text-[13px] font-bold text-white disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--accent)" }}
            >
              {nicknameSaving ? "변경 중…" : "변경"}
            </button>
          </div>
          {nicknameError && (
            <p className="mt-1.5 text-[12px]" style={{ color: "var(--dangerText)" }}>{nicknameError}</p>
          )}
          {nicknameSaved && (
            <p className="mt-1.5 text-[12px]" style={{ color: "var(--up)" }}>닉네임을 변경했어요.</p>
          )}
        </form>

        <div className="mb-5 h-px" style={{ background: "var(--line)" }} />

        <form onSubmit={handleChangePassword}>
          <label className="mb-1.5 block text-[13px] font-bold" style={{ color: "var(--mut2)" }}>비밀번호 변경</label>
          <div className="flex max-w-[320px] flex-col gap-2">
            <input
              type="password"
              required
              placeholder="현재 비밀번호"
              value={currentPassword}
              onChange={(e) => {
                setCurrentPassword(e.target.value);
                setPasswordError(null);
                setPasswordSaved(false);
              }}
              className="w-full rounded-xl px-4 py-2.5 text-[13.5px] outline-none"
              style={{ background: "var(--fill)", color: "var(--ink)" }}
            />
            <input
              type="password"
              required
              minLength={8}
              maxLength={64}
              placeholder="새 비밀번호 (8자 이상)"
              value={newPassword}
              onChange={(e) => {
                setNewPassword(e.target.value);
                setPasswordError(null);
                setPasswordSaved(false);
              }}
              className="w-full rounded-xl px-4 py-2.5 text-[13.5px] outline-none"
              style={{ background: "var(--fill)", color: "var(--ink)" }}
            />
            <input
              type="password"
              required
              placeholder="새 비밀번호 확인"
              value={newPasswordConfirm}
              onChange={(e) => {
                setNewPasswordConfirm(e.target.value);
                setPasswordError(null);
                setPasswordSaved(false);
              }}
              className="w-full rounded-xl px-4 py-2.5 text-[13.5px] outline-none"
              style={{ background: "var(--fill)", color: "var(--ink)" }}
            />
            <button
              type="submit"
              disabled={passwordSaving}
              className="cursor-pointer rounded-xl px-4 py-2.5 text-[13px] font-bold text-white disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--accent)" }}
            >
              {passwordSaving ? "변경 중…" : "비밀번호 변경"}
            </button>
          </div>
          {passwordError && (
            <p className="mt-1.5 text-[12px]" style={{ color: "var(--dangerText)" }}>{passwordError}</p>
          )}
          {passwordSaved && (
            <p className="mt-1.5 text-[12px]" style={{ color: "var(--up)" }}>비밀번호를 변경했어요.</p>
          )}
        </form>
      </Reveal>

      <Reveal delay={0.4} className="mt-4 rounded-[20px] px-6 py-5.5" style={{ background: "var(--dangerBg)" }}>
        <div className="flex flex-wrap items-center gap-5">
          <div>
            <div className="mb-1 text-[17px] font-bold" style={{ color: "var(--ink)" }}>포트폴리오 초기화</div>
            <div className="text-[15px] leading-relaxed" style={{ color: "var(--dangerTextSoft)" }}>
              보유 종목과 체결 내역이 모두 정리되고 모의 투자금이{" "}
              <b>{formatNumber(INITIAL_CASH)}원</b>으로 되돌아가요. 되돌릴 수 없어요.
            </div>
          </div>
          <button
            onClick={() => setResetModalOpen(true)}
            className="ml-auto cursor-pointer rounded-xl px-5 py-3 text-[14px] font-bold"
            style={{ background: "var(--card)", color: "var(--dangerText)" }}
          >
            포트폴리오 초기화
          </button>
        </div>
      </Reveal>

      <Reveal delay={0.45} className="mt-4 rounded-[20px] px-6 py-5.5" style={{ background: "var(--dangerBg)" }}>
        <div className="flex flex-wrap items-center gap-5">
          <div>
            <div className="mb-1 text-[17px] font-bold" style={{ color: "var(--ink)" }}>회원 탈퇴</div>
            <div className="text-[15px] leading-relaxed" style={{ color: "var(--dangerTextSoft)" }}>
              계정과 보유 종목·체결 내역이 모두 사라져요. 되돌릴 수 없어요.
            </div>
          </div>
          <button
            onClick={() => setWithdrawModalOpen(true)}
            className="ml-auto cursor-pointer rounded-xl px-5 py-3 text-[14px] font-bold"
            style={{ background: "var(--card)", color: "var(--dangerText)" }}
          >
            회원 탈퇴
          </button>
        </div>
      </Reveal>

      {withdrawModalOpen && (
        <div
          className="fixed inset-0 z-[150] flex items-center justify-center px-4"
          style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
          onClick={() => !withdrawing && setWithdrawModalOpen(false)}
        >
          <div
            className="w-full max-w-[420px] rounded-[24px] px-7.5 pt-8 pb-6.5 text-center"
            style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="mb-1.5 text-[18px] font-bold" style={{ color: "var(--ink)" }}>
              정말 탈퇴할까요?
            </h3>
            <p className="mb-4.5 text-[13.5px] leading-relaxed" style={{ color: "var(--mut)" }}>
              계정과 보유 종목·체결 내역이 모두 사라져요.
              <br />
              되돌릴 수 없어요.
            </p>
            <input
              type="password"
              required
              placeholder="현재 비밀번호"
              value={withdrawPassword}
              onChange={(e) => {
                setWithdrawPassword(e.target.value);
                setWithdrawError(null);
              }}
              className="mb-3 w-full rounded-xl px-4 py-3 text-[13.5px] outline-none"
              style={{ background: "var(--fill)", color: "var(--ink)" }}
            />
            {withdrawError && (
              <p className="mb-3 text-[12.5px]" style={{ color: "var(--dangerText)" }}>
                {withdrawError}
              </p>
            )}
            <button
              className="mb-2 w-full cursor-pointer rounded-xl px-4 py-3 text-[13.5px] font-bold text-white disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--dangerText)" }}
              onClick={handleWithdraw}
              disabled={withdrawing || !withdrawPassword}
            >
              {withdrawing ? "탈퇴 처리 중…" : "탈퇴할게요"}
            </button>
            <button
              className="w-full cursor-pointer rounded-xl px-4 py-3 text-[13.5px] font-bold disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--fill)", color: "var(--ink)" }}
              onClick={() => setWithdrawModalOpen(false)}
              disabled={withdrawing}
            >
              취소
            </button>
          </div>
        </div>
      )}

      {resetModalOpen && (
        <div
          className="fixed inset-0 z-[150] flex items-center justify-center px-4"
          style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
          onClick={() => !resetting && setResetModalOpen(false)}
        >
          <div
            className="w-full max-w-[420px] rounded-[24px] px-7.5 pt-8 pb-6.5 text-center"
            style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="mb-1.5 text-[18px] font-bold" style={{ color: "var(--ink)" }}>
              포트폴리오를 정말 초기화할까요?
            </h3>
            <p className="mb-4.5 text-[13.5px] leading-relaxed" style={{ color: "var(--mut)" }}>
              보유 종목과 체결 내역이 모두 정리되고 모의 투자금이{" "}
              <b style={{ color: "var(--ink)" }}>{formatNumber(INITIAL_CASH)}원</b>으로 되돌아가요.
              <br />
              되돌릴 수 없어요.
            </p>
            {resetError && (
              <p className="mb-3 text-[12.5px]" style={{ color: "var(--dangerText)" }}>
                {resetError}
              </p>
            )}
            <button
              className="mb-2 w-full rounded-xl px-4 py-3 text-[13.5px] font-bold text-white disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--dangerText)" }}
              onClick={handleReset}
              disabled={resetting}
            >
              {resetting ? "초기화하는 중…" : "초기화할게요"}
            </button>
            <button
              className="w-full rounded-xl px-4 py-3 text-[13.5px] font-bold disabled:cursor-not-allowed disabled:opacity-60"
              style={{ background: "var(--fill)", color: "var(--ink)" }}
              onClick={() => setResetModalOpen(false)}
              disabled={resetting}
            >
              취소
            </button>
          </div>
        </div>
      )}
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

function LedgerBadge({ type }: { type: LedgerItem["entryType"] }) {
  const style =
    type === "BUY"
      ? { background: "var(--downBg)", color: "var(--down)" }
      : type === "SELL"
        ? { background: "var(--upBg)", color: "var(--up)" }
        : { background: "var(--accentSoft)", color: "var(--onAccentSoftText)" };
  const label = type === "BUY" ? "매수" : type === "SELL" ? "매도" : "초기지급";
  return (
    <span className="w-fit rounded-md px-2.5 py-1 text-[12px] font-bold" style={style}>
      {label}
    </span>
  );
}
