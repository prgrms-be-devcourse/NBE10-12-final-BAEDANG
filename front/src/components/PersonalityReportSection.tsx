"use client";

import { useEffect, useState } from "react";
import {
  getLeaderboard,
  getLeaderboardTypes,
  getPersonalityReport,
  type Leaderboard,
  type LeaderboardEntry,
  type LeaderboardTypeEntry,
  type LeaderboardTypes,
  type PersonalityReport,
} from "@/lib/api";
import { formatNumber, formatPercent, formatUsd, toDecimal } from "@/lib/format";
import { PERSONALITY_AXES, PERSONALITY_TYPES } from "@/lib/personality-types";
import { Tag } from "./Tag";

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/** "1주 3일"처럼 표시한다. 정확히 주 단위면 "일"을 생략한다. */
function formatWeeksDays(ms: number): string {
  const totalDays = Math.max(0, Math.round(ms / MS_PER_DAY));
  const weeks = Math.floor(totalDays / 7);
  const days = totalDays % 7;
  if (weeks === 0) return `${days}일`;
  if (days === 0) return `${weeks}주`;
  return `${weeks}주 ${days}일`;
}

function formatDate(value: string | number): string {
  return new Date(value).toLocaleDateString("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit" });
}

/**
 * 첨부받은 디자인 시안("투자 성향 리포트 (standalone).html")을 마이페이지에 적용해달라는
 * 요청 — `GET /api/reports/me`·`GET /api/reports/leaderboard`(이미 백엔드에 구현돼 있던
 * API, `back/src/main/java/com/baedang/report/`)를 그대로 연결했다. 시안은 정적 목업
 * 데이터(코스피 대비 수익률, 보유 종목별 비중·수량 등)를 썼지만 실제 API 응답 모양과
 * 다른 부분은 실제로 내려오는 값에 맞춰 바꿨다 — 자세한 사정은 각 항목의 주석 참고.
 *
 * <p>세 가지 상태를 그린다: 아직 4주가 안 지난 "잠김", 지났지만 보유 종목이 2개
 * 미만이라 유형을 못 정하는 "미분류", 유형이 정해진 "공개". 백엔드 주석대로
 * 잠금 해제 이후에는 "한 번 굳는 스냅샷"이 아니라 열 때마다 다시 계산되므로,
 * 시안에 있던 고정된 "기간: 2026.08.10~2026.09.06" 같은 표현 대신 "asOf" 재계산
 * 시각을 보여준다.
 */
export function PersonalityReportSection() {
  const [report, setReport] = useState<PersonalityReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [boardOpen, setBoardOpen] = useState(false);
  const [typeBoardOpen, setTypeBoardOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getPersonalityReport()
      .then((res) => {
        if (!cancelled) setReport(res);
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
  }, []);

  if (loading) {
    return (
      <div className="rounded-[20px] py-16 text-center text-[13.5px]" style={{ background: "var(--card)", color: "var(--mut2)" }}>
        투자 성향 리포트를 불러오는 중…
      </div>
    );
  }
  if (loadError || !report) {
    return (
      <div className="rounded-[20px] py-16 text-center text-[13.5px]" style={{ background: "var(--card)", color: "var(--mut2)" }}>
        투자 성향 리포트를 불러오지 못했어요. 잠시 후 다시 시도해주세요.
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {report.locked ? (
        <LockedCard report={report} onHelp={() => setHelpOpen(true)} />
      ) : (
        <OpenCard
          report={report}
          onHelp={() => setHelpOpen(true)}
          onOpenBoard={() => setBoardOpen(true)}
          onOpenTypeBoard={() => setTypeBoardOpen(true)}
        />
      )}

      {helpOpen && <HelpModal onClose={() => setHelpOpen(false)} />}
      {boardOpen && <LeaderboardModal onClose={() => setBoardOpen(false)} />}
      {typeBoardOpen && (
        <TypeComparisonModal myTypeCode={report.typeCode} onClose={() => setTypeBoardOpen(false)} />
      )}
    </div>
  );
}

function CardHeader({
  title,
  subtitle,
  badge,
  onHelp,
}: {
  title: string;
  subtitle: string;
  badge: React.ReactNode;
  onHelp: () => void;
}) {
  return (
    <div
      className="flex items-center justify-between gap-4 px-6 py-5"
      style={{ borderBottom: "1px solid var(--line2)" }}
    >
      <div className="flex items-center gap-2">
        <div>
          <div className="text-[17px] font-bold" style={{ color: "var(--ink)" }}>{title}</div>
          <div className="mt-1 text-[13px]" style={{ color: "var(--mut2)" }}>{subtitle}</div>
        </div>
        <button
          type="button"
          onClick={onHelp}
          className="report-help-btn flex h-[22px] w-[22px] cursor-pointer items-center justify-center rounded-full text-[12px] font-bold"
          style={{ background: "var(--card)" }}
          aria-label="투자 성향 판정 기준"
        >
          ?
        </button>
      </div>
      <div className="flex items-center gap-2.5">
        <button
          type="button"
          onClick={onHelp}
          className="cursor-pointer rounded-md px-0.5 py-1 text-[12.5px] font-bold"
          style={{ color: "var(--mut)" }}
        >
          성향 판정 기준
        </button>
        {badge}
      </div>
    </div>
  );
}

function LockedCard({ report, onHelp }: { report: PersonalityReport; onHelp: () => void }) {
  // 잠김 응답엔 계좌 개설일(openedAt)이 직접 없어서, unlockAt(=개설일 + holdingPeriodWeeks)에서
  // 거꾸로 계산한다 — 두 설정값(unlock-weeks·holding-period-weeks) 모두 기본 4주로 같아서
  // holdingPeriodWeeks를 "N주 게이트"의 N으로 그대로 쓴다.
  const totalMs = report.holdingPeriodWeeks * 7 * MS_PER_DAY;
  const unlockAtMs = new Date(report.unlockAt).getTime();
  const nowMs = new Date(report.asOf).getTime();
  const remainMs = Math.max(0, unlockAtMs - nowMs);
  const elapsedMs = Math.max(0, Math.min(totalMs, totalMs - remainMs));
  const percent = totalMs > 0 ? Math.round((elapsedMs / totalMs) * 100) : 0;
  const startMs = unlockAtMs - totalMs;

  return (
    <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
      <CardHeader
        title="투자 성향 리포트"
        subtitle={`${report.holdingPeriodWeeks}주 단위로 발급돼요`}
        onHelp={onHelp}
        badge={
          <span
            className="rounded-full px-3 py-1.5 text-[12px] font-bold"
            style={{ background: "var(--fill)", color: "var(--mut2)" }}
          >
            잠김
          </span>
        }
      />
      {/* 반응형 웹 적용 — 인라인 style로 준 gridTemplateColumns는 셀렉터
          우선순위와 무관하게 항상 클래스 기반 스타일보다 이긴다(위키
          패널 호버 그라데이션에서 겪은 것과 같은 원인). 그래서
          "max-md:grid-cols-1" 클래스가 있어도 모바일에서 전혀 적용되지
          않고 260px 고정 칸이 그대로 남아, 남은 폭(minmax(0,1fr))이
          0으로 짓눌려 오른쪽 절반(설명 문구·진행률·버튼)이 카드의
          overflow-hidden에 가려 통째로 안 보이는 문제가 있었다(제보
          없이 반응형 전수 점검 중 발견). 열 너비 자체를 Tailwind
          임의값(grid-cols-[...])으로 옮겨 md: 접두사가 실제로 먹히게
          고쳤다 — md 미만은 1칸(세로로 쌓임), md 이상만 260px+나머지. */}
      <div className="grid grid-cols-1 gap-7 px-6 py-7 md:grid-cols-[260px_minmax(0,1fr)]">
        <div
          className="flex aspect-square flex-col items-center justify-center gap-3 rounded-[20px]"
          style={{ background: "var(--fill)", border: "1px dashed var(--line)" }}
        >
          <svg width="34" height="34" viewBox="0 0 24 24" fill="none">
            <rect x="4" y="10.5" width="16" height="10" rx="3" stroke="var(--mut2)" strokeWidth="1.7" />
            <path d="M8 10.5V7.5a4 4 0 0 1 8 0v3" stroke="var(--mut2)" strokeWidth="1.7" />
          </svg>
          <span className="text-[13px] font-bold" style={{ color: "var(--mut2)" }}>아직 공개 전이에요</span>
        </div>

        <div className="flex flex-col justify-center">
          <h3 className="text-[24px] font-extrabold tracking-[-0.02em]" style={{ color: "var(--ink)" }}>
            투자 성향 리포트는 {report.holdingPeriodWeeks}주 뒤에 열려요
          </h3>
          <p className="mt-2.5 text-[14.5px] leading-[1.7]" style={{ color: "var(--body)" }}>
            계좌를 {report.holdingPeriodWeeks}주 동안 굴려야 종목 비중·변동성이 성향으로 굳어져요. 지금 계좌는{" "}
            <b style={{ color: "var(--ink)" }}>{formatWeeksDays(elapsedMs)}</b> 지났고,{" "}
            <b style={{ color: "var(--accent)" }}>{formatWeeksDays(remainMs)}</b> 남았어요.
          </p>

          <div className="mt-5">
            <div className="mb-2 flex justify-between text-[12px] font-bold" style={{ color: "var(--mut2)" }}>
              <span>{formatDate(startMs)} 시작</span>
              <span>{percent}%</span>
            </div>
            <div className="h-2.5 overflow-hidden rounded-full" style={{ background: "var(--fill)" }}>
              <div
                className="h-full rounded-full transition-[width] duration-500 ease-out"
                style={{ background: "var(--accent)", width: `${percent}%` }}
              />
            </div>
            <div className="mt-2 text-[12px]" style={{ color: "var(--mut2)" }}>
              {formatDate(report.unlockAt)} 공개 예정
            </div>
          </div>

          <button
            type="button"
            disabled
            className="mt-5.5 w-fit cursor-not-allowed rounded-xl px-5.5 py-3 text-[14px] font-bold"
            style={{ background: "var(--fill)", color: "var(--disabledText)" }}
          >
            지금은 볼 수 없어요
          </button>
        </div>
      </div>
    </div>
  );
}

function OpenCard({
  report,
  onHelp,
  onOpenBoard,
  onOpenTypeBoard,
}: {
  report: PersonalityReport;
  onHelp: () => void;
  onOpenBoard: () => void;
  onOpenTypeBoard: () => void;
}) {
  const shares = report.shares;
  const returnUp = (toDecimal(report.returnRate)?.greaterThanOrEqualTo(0)) ?? true;
  const personaType = report.typeCode ? PERSONALITY_TYPES[report.typeCode] : undefined;

  return (
    <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
      <CardHeader
        title="투자 성향 리포트"
        subtitle={`${report.roundNo}회차 · ${formatDate(report.asOf)} 기준으로 다시 계산했어요`}
        onHelp={onHelp}
        badge={
          <span
            className="rounded-full px-3 py-1.5 text-[12px] font-bold"
            style={{ background: "var(--accentSoft)", color: "var(--onAccentSoftText)" }}
          >
            {report.classified ? "공개" : "미분류"}
          </span>
        }
      />

      {report.classified && report.typeCode && shares ? (
        // 반응형 웹 적용 — LockedCard와 같은 원인(인라인 gridTemplateColumns가
        // max-md:grid-cols-1을 항상 이김)이라 같은 방식으로 고쳤다.
        <div className="grid grid-cols-1 gap-7.5 px-6 py-7 md:grid-cols-[300px_minmax(0,1fr)]">
          <div className="flex flex-col gap-3.5">
            {/* 시안은 유형별 AI 생성 이미지를 넣었었는데, 실제 이미지를 등록해달라는
                요청으로 유형별 실제 이미지(public/personality-types/, personaType.image)를
                연결했다. 이미지가 없는(등록 안 된) 유형이 생기더라도 깨지지 않도록,
                이미지가 없으면 기존 코드·별명 텍스트 카드로 그대로 폴백한다. 헤더 로고
                (Nav.tsx)와 같은 이유로 next/image 대신 일반 img를 썼다 — 유형에 따라
                16장 중 하나만 조건부로 그려서 next/image 최적화 이점이 크지 않다. */}
            {personaType?.image ? (
              // eslint-disable-next-line @next/next/no-img-element -- 유형별 16장 중 하나만 조건부로 보여주는 이미지라 next/image 최적화 이점이 없다.
              <img
                src={personaType.image}
                alt={`${personaType.nickname} 이미지`}
                className="aspect-square w-full rounded-[20px] object-cover"
              />
            ) : (
              <div
                className="flex aspect-square flex-col items-center justify-center gap-2 rounded-[20px] px-4 text-center"
                style={{ background: "var(--accentSoft)" }}
              >
                <span className="font-mono text-[26px] font-extrabold tracking-[.08em]" style={{ color: "var(--onAccentSoftText)" }}>
                  {report.typeCode}
                </span>
                <span className="text-[13px] font-bold" style={{ color: "var(--onAccentSoftText)" }}>
                  {personaType?.nickname ?? report.typeLabel}
                </span>
              </div>
            )}
            <div className="flex items-center gap-2.5 rounded-2xl px-4 py-3.5" style={{ background: "var(--accent)" }}>
              <span className="font-mono text-[20px] font-extrabold tracking-[.06em] text-white">{report.typeCode}</span>
              <span className="ml-auto text-[12px] font-bold" style={{ color: "var(--accentText)" }}>{report.typeLabel}</span>
            </div>
          </div>

          <div className="min-w-0">
            <div className="text-[12.5px] font-bold" style={{ color: "var(--mut2)" }}>이번 회차 투자 성향</div>
            <h3 className="mt-1.5 text-[30px] font-extrabold tracking-[-0.02em]" style={{ color: "var(--ink)" }}>
              {personaType?.nickname ?? report.typeLabel}
            </h3>
            {personaType && (
              <p className="mt-2.5 text-[15px] leading-[1.7]" style={{ color: "var(--body)" }}>{personaType.description}</p>
            )}

            <div className="mt-5.5 flex flex-col gap-3.5">
              {PERSONALITY_AXES.map((axis, i) => {
                const share = Number(shares[axis.key]) || 0;
                const letter = report.typeCode?.[i] ?? null;
                const isHigh = letter ? letter === axis.highLetter : share >= 0.5;
                const fillPercent = Math.max(0, Math.min(100, (1 - share) * 100));
                const valueLetter = isHigh ? axis.highLetter : axis.lowLetter;
                const sharePercent = Math.round(share * 100);
                const valuePercent = isHigh ? sharePercent : 100 - sharePercent;
                return (
                  <div key={axis.key}>
                    <div className="mb-1.5 flex items-baseline gap-2 text-[12px] font-bold" style={{ color: "var(--mut2)" }}>
                      <span className="min-w-[44px]" style={{ color: "var(--accent)" }}>{axis.title}</span>
                      <span>{axis.highName} {axis.highLetter}</span>
                      <span className="ml-auto">{axis.lowName} {axis.lowLetter}</span>
                    </div>
                    <div className="relative h-[26px] overflow-hidden rounded-full" style={{ background: "var(--fill)" }}>
                      <div
                        className="absolute inset-y-0 left-0 rounded-full transition-[width] duration-500 ease-out"
                        style={{ background: "var(--accentSoft)", width: `${fillPercent}%` }}
                      />
                      <div
                        className="absolute inset-y-0 w-1 rounded-sm transition-[left] duration-500 ease-out"
                        style={{ background: "var(--accent)", left: `${fillPercent}%` }}
                      />
                      <div className="absolute inset-0 flex items-center justify-end px-3.5 text-[12px] font-bold" style={{ color: "var(--accent)" }}>
                        {valueLetter} {valuePercent}%
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      ) : (
        <div className="px-6 py-7 text-center">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full" style={{ background: "var(--fill)" }}>
            <span className="text-[22px]">🌱</span>
          </div>
          <h3 className="mt-3.5 text-[19px] font-bold" style={{ color: "var(--ink)" }}>아직 성향을 정하기엔 종목이 부족해요</h3>
          <p className="mt-1.5 text-[14px] leading-[1.6]" style={{ color: "var(--mut2)" }}>
            보유 종목이 {report.holdingCount}개예요 — 2개 이상 보유하면 다음번에 유형이 나와요.
          </p>
        </div>
      )}

      <div className="flex flex-wrap gap-3 px-6 pb-6.5">
        <ReportStat
          label="회차 수익률"
          value={formatPercent(report.returnRate)}
          sub="초기자본 대비 총손익 기준"
          color={returnUp ? "var(--up)" : "var(--down)"}
        />
        <ReportStat
          label="총 자산"
          value={`${formatNumber(report.totalAsset)}원`}
          sub={`시작 ${formatNumber(report.initialCash)}원`}
        />
        <ReportStat
          label="예수금"
          value={`${formatNumber(report.cashBalance)}원`}
          sub={`주식 평가금액 ${formatNumber(report.stockValue)}원`}
        />
      </div>

      {report.longHeldStocks.length > 0 && (
        <div className="px-6 pb-7">
          <div className="mb-2.5 flex items-baseline gap-2">
            <span className="text-[15px] font-bold" style={{ color: "var(--ink)" }}>
              {report.holdingPeriodWeeks}주 이상 보유한 종목
            </span>
            <span className="text-[12px]" style={{ color: "var(--mut2)" }}>가장 오래 보유한 순</span>
          </div>
          <div className="overflow-hidden rounded-2xl" style={{ border: "1px solid var(--line2)" }}>
            {/* 반응형 웹 적용 — 랭킹/마이페이지 테이블과 같은 문제(5칸 그리드가
                모바일 폭에서는 남는 공간이 없어 뒤쪽 칸들이 읽기 힘들 만큼
                짓눌림)라 같은 방식으로 고쳤다. md 미만에서는 헤더를 숨기고
                행을 카드형으로 쌓는다. */}
            <div
              className="hidden px-4.5 py-2.5 text-[12px] font-bold md:grid"
              style={{ gridTemplateColumns: "1.6fr 1fr 1fr 1fr 1.1fr", background: "var(--fill)", color: "var(--mut2)" }}
            >
              <span>종목</span>
              <span className="text-right">평균단가</span>
              <span className="text-right">현재가</span>
              <span className="text-right">보유 수익률</span>
              <span className="text-right">보유 시작일</span>
            </div>
            {report.longHeldStocks.map((h) => {
              const isUsd = h.currency === "USD";
              const rate = toDecimal(h.returnRate);
              const up = !rate || rate.greaterThanOrEqualTo(0);
              const avgBuyText = isUsd ? formatUsd(h.avgBuyPrice) : `${formatNumber(h.avgBuyPrice)}원`;
              const lastPriceText = isUsd ? formatUsd(h.lastPrice) : `${formatNumber(h.lastPrice)}원`;
              const returnNode = (
                <span className="tabular-nums font-semibold" style={{ color: up ? "var(--up)" : "var(--down)" }}>
                  {formatPercent(h.returnRate)}
                </span>
              );
              return (
                <div key={h.symbol} style={{ borderTop: "1px solid var(--line2)" }}>
                  {/* 데스크톱(md 이상) — 기존 5칸 그리드 그대로. */}
                  <div className="hidden items-center gap-3 px-4.5 py-3 text-[14.5px] md:grid" style={{ gridTemplateColumns: "1.6fr 1fr 1fr 1fr 1.1fr" }}>
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span className="overflow-hidden font-bold text-ellipsis whitespace-nowrap" style={{ color: "var(--ink)" }}>{h.name}</span>
                      <Tag>{h.symbol}</Tag>
                    </span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{avgBuyText}</span>
                    <span className="text-right tabular-nums" style={{ color: "var(--ink)" }}>{lastPriceText}</span>
                    <span className="text-right">{returnNode}</span>
                    <span className="text-right text-[12.5px]" style={{ color: "var(--mut2)" }}>{formatDate(h.heldSince)}</span>
                  </div>

                  {/* 모바일(md 미만) — 카드형. */}
                  <div className="flex flex-col gap-1.5 px-4.5 py-3 text-[14.5px] md:hidden">
                    <div className="flex items-center justify-between gap-2">
                      <span className="flex min-w-0 items-center gap-1.5">
                        <span className="overflow-hidden font-bold text-ellipsis whitespace-nowrap" style={{ color: "var(--ink)" }}>{h.name}</span>
                        <Tag>{h.symbol}</Tag>
                      </span>
                      {returnNode}
                    </div>
                    <div className="flex items-center justify-between text-[12.5px]" style={{ color: "var(--mut2)" }}>
                      <span>평균단가 {avgBuyText}</span>
                      <span>현재가 {lastPriceText}</span>
                    </div>
                    <div className="text-right text-[12.5px]" style={{ color: "var(--mut2)" }}>{formatDate(h.heldSince)} 보유 시작</div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      <div
        className="flex flex-wrap items-center gap-5 px-6 py-5"
        style={{ borderTop: "1px solid var(--line2)", background: "var(--bg)" }}
      >
        <div>
          <div className="text-[15px] font-bold" style={{ color: "var(--ink)" }}>수익률 리더보드</div>
          <div className="mt-1 text-[13px]" style={{ color: "var(--mut2)" }}>
            내 순위와 상위 백분율을 함께 보여드려요 · 절대 금액·닉네임 전체는 공개되지 않아요
          </div>
        </div>
        <div className="ml-auto flex gap-2.5">
          <button
            type="button"
            onClick={onOpenTypeBoard}
            className="cursor-pointer rounded-xl px-5 py-3 text-[13.5px] font-bold"
            style={{ background: "var(--fill)", color: "var(--ink)" }}
          >
            유형별 비교 보기
          </button>
          <button
            type="button"
            onClick={onOpenBoard}
            className="cursor-pointer rounded-xl px-5 py-3 text-[13.5px] font-bold text-white"
            style={{ background: "var(--accent)" }}
          >
            전체 랭킹 보기
          </button>
        </div>
      </div>
    </div>
  );
}

function ReportStat({ label, value, sub, color }: { label: string; value: string; sub: string; color?: string }) {
  return (
    <div className="flex-1 min-w-[180px] rounded-2xl px-5 py-4.5" style={{ background: "var(--bg)" }}>
      <div className="text-[12.5px]" style={{ color: "var(--mut)" }}>{label}</div>
      <div className="mt-1.5 text-[21px] font-extrabold tracking-[-0.01em]" style={{ color: color ?? "var(--ink)" }}>{value}</div>
      <div className="mt-1 text-[12px]" style={{ color: "var(--mut2)" }}>{sub}</div>
    </div>
  );
}

const HELP_ITEMS = [
  // "가장 큰 종목의 평가비중으로 판단해요." 뒤와 "...안정(B)이에요." 뒤에서
  // 줄바꿈해달라는 요청 — desc를 렌더링하는 div에 whiteSpace: "pre-line"을
  // 줘서 이 \n이 실제 줄바꿈으로 보이게 했다.
  { title: "분산 · 집중(C) ↔ 분산(D)", desc: "가장 큰 종목의 평가비중으로 판단해요.\n한 종목이 50% 이상이면 집중(C), 아니면 분산(D)이에요." },
  { title: "시장 · 국내(K) ↔ 해외(G)", desc: "국내 종목 평가비중이 50% 이상이면 국내(K), 아니면 해외(G)예요." },
  { title: "유형 · 개별주(S) ↔ ETF(E)", desc: "개별주(개별주·우선주) 평가비중이 50% 이상이면 개별주(S), 아니면 ETF(E)예요." },
  // 백엔드는 아직 변동성을 반영하지 않는 Phase 1 프록시라(레버리지·인버스 비중만 봄),
  // 시안의 "일간 변동성을 합쳐서 본다"는 문구는 실제와 달라 정확하게 고쳤다.
  { title: "공격성 · 공격(A) ↔ 안정(B)", desc: "레버리지·인버스 상품 평가비중이 20% 이상이면 공격(A), 아니면 안정(B)이에요.\n(변동성 반영은 추후 예정)" },
];

function HelpModal({ onClose }: { onClose: () => void }) {
  return (
    <div
      className="fixed inset-0 z-[150] flex items-center justify-center px-4"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
      onClick={onClose}
    >
      <div
        className="max-h-[86vh] w-full max-w-[560px] overflow-y-auto rounded-[24px] px-7.5 pt-7 pb-6"
        style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <h3 className="text-[21px] font-extrabold tracking-[-0.02em]" style={{ color: "var(--ink)" }}>
              투자 성향은 이렇게 정해져요
            </h3>
            <p className="mt-1.5 text-[13px]" style={{ color: "var(--mut2)" }}>4개 축을 조합해 16가지 유형이 나와요</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="report-modal-close-btn cursor-pointer rounded-full px-3 py-1.5 text-[13px] font-semibold"
            style={{ color: "var(--mut)" }}
          >
            닫기
          </button>
        </div>
        <div className="mt-4.5 flex flex-col gap-2.5">
          {HELP_ITEMS.map((item) => (
            <div key={item.title} className="rounded-2xl px-4 py-3.5" style={{ background: "var(--bg)" }}>
              <div className="text-[13px] font-bold" style={{ color: "var(--accent)" }}>{item.title}</div>
              <div className="mt-1.5 text-[13px] leading-[1.65]" style={{ color: "var(--body)", whiteSpace: "pre-line" }}>{item.desc}</div>
            </div>
          ))}
        </div>
        <p className="mt-4 text-[12.5px] leading-[1.6]" style={{ color: "var(--mut2)" }}>
          4주 창의 원가 구성을 시점별로 뽑아 평균낸 값으로 판정하고, 잠금이 풀린 뒤에는 열 때마다
          다시 계산돼요(한 번 굳어서 고정되지 않아요). 계좌를 초기화하면 새 계좌 기준으로 다시 4주를
          채워야 해요.
        </p>
        {/* "닫기" 버튼과 같은 호버 효과(배경이 var(--fill) → var(--line)로
            바뀜)를 적용해달라는 요청 — 같은 CSS 클래스(report-modal-close-btn)를
            재사용했다. 이 버튼도 onClick={onClose}로 같은 동작을 하니 이름과도
            어긋나지 않는다.
            처음엔 기존 인라인 background: "var(--fill)"을 그대로 둔 채
            클래스만 추가했는데, 인라인 style은 :hover를 포함한 어떤
            외부 스타일시트 규칙보다도 우선이라 호버 자체가 아예 안
            먹혔다("닫기" 버튼들은 애초에 배경을 인라인으로 주지 않아서
            클래스가 base·hover 배경을 전부 제어한다) — 그래서 인라인
            background를 지우고 클래스에게 완전히 맡겼다. */}
        <button
          type="button"
          onClick={onClose}
          className="report-modal-close-btn mt-4.5 w-full cursor-pointer rounded-xl py-3 text-[13.5px] font-bold"
          style={{ color: "var(--ink)" }}
        >
          확인했어요
        </button>
      </div>
    </div>
  );
}

function LeaderboardModal({ onClose }: { onClose: () => void }) {
  const [board, setBoard] = useState<Leaderboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getLeaderboard()
      .then((res) => {
        if (!cancelled) setBoard(res);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // top(상위 N)과 me.neighbors(내 순위 주변)는 순위가 겹칠 수 있다(예: topSize=10,
  // neighborRadius=2, 내 순위 11위면 이웃이 9~13위라 9·10위가 top과 겹친다) — rank
  // 기준으로 중복을 제거하고, 겹치지 않는 두 그룹 사이에는 점선으로 구간이 끊겼음을 보여준다.
  const topRanks = new Set((board?.top ?? []).map((e) => e.rank));
  const neighborRows = (board?.me?.neighbors ?? []).filter((e) => !topRanks.has(e.rank));
  const hasGap = neighborRows.length > 0 && (board?.top.length ?? 0) > 0
    && neighborRows[0].rank - (board?.top.at(-1)?.rank ?? 0) > 1;

  return (
    <div
      className="fixed inset-0 z-[150] flex items-center justify-center px-4"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
      onClick={onClose}
    >
      <div
        className="flex max-h-[86vh] w-full max-w-[620px] flex-col overflow-hidden rounded-[24px]"
        style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-4 px-7 pt-6.5 pb-4.5">
          <div>
            <h3 className="text-[22px] font-extrabold tracking-[-0.02em]" style={{ color: "var(--ink)" }}>리더보드</h3>
            <p className="mt-1.5 text-[13px]" style={{ color: "var(--mut2)" }}>
              {board?.asOf ? `${formatDate(board.asOf)} 기준 · 수익률 순` : "수익률 순"}
              {board && board.participants > 0 && ` · 참가자 ${formatNumber(board.participants)}명`}
              {" · 상위 10위 + 내 순위 주변만 표시"}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="report-modal-close-btn cursor-pointer rounded-full px-3 py-1.5 text-[13px] font-semibold"
            style={{ color: "var(--mut)" }}
          >
            닫기
          </button>
        </div>

        {/* 반응형 웹 적용 — 이 3칸 그리드는 고정폭 칸이 60px+96px로 크지
            않아 완전히 짓눌리진 않지만, 모바일 폭에서는 여전히 빠듯하다
            — md 미만에서는 헤더를 숨기고(LeaderboardRow도 같은 폭에서
            카드형 한 줄로 바뀐다) md 이상에서만 보여준다. */}
        <div
          className="hidden px-7 py-2.5 text-[12px] font-bold md:grid"
          style={{ gridTemplateColumns: "60px minmax(0,1fr) 96px", background: "var(--bg)", color: "var(--mut2)" }}
        >
          <span>등수</span>
          <span>닉네임</span>
          <span className="text-right">수익률</span>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto">
          {loading ? (
            <div className="py-14 text-center text-[13px]" style={{ color: "var(--mut2)" }}>불러오는 중…</div>
          ) : error ? (
            <div className="py-14 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
              리더보드를 불러오지 못했어요.
            </div>
          ) : !board || board.participants === 0 ? (
            <div className="py-14 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
              아직 집계된 순위가 없어요. 다음 배치를 기다려주세요.
            </div>
          ) : (
            <>
              {board.top.map((row) => (
                <LeaderboardRow key={row.rank} row={row} isMe={board.me?.rank === row.rank} />
              ))}
              {hasGap && (
                <div className="px-7 py-1" style={{ borderTop: "1px dashed var(--line)" }} />
              )}
              {neighborRows.map((row) => (
                <LeaderboardRow key={row.rank} row={row} isMe={board.me?.rank === row.rank} />
              ))}
            </>
          )}
        </div>

        {board?.me && (
          <div
            className="flex flex-wrap items-center gap-2 px-7 py-3.5 text-[12.5px]"
            style={{ borderTop: "1px solid var(--line2)", color: "var(--mut2)" }}
          >
            <span>
              내 순위 {board.me.rank}위
              {board.me.topPercent != null && ` · 상위 ${board.me.topPercent}%`}
            </span>
            {/* 유형별 순위(#153 Phase 3) — 미분류면 다섯 필드가 전부 null이라 그때는 보여줄 게 없다. */}
            {board.me.typeRank != null && (
              <span>
                · <b style={{ color: "var(--accent)" }}>{board.me.typeLabel}</b>({board.me.typeCode}) 유형 안에서{" "}
                {board.me.typeRank}위/{formatNumber(board.me.typeParticipants)}명
                {board.me.typePercent != null && ` · 상위 ${board.me.typePercent}%`}
              </span>
            )}
            <span className="ml-auto">닉네임은 가운데 글자가 가려진 채로 공개돼요</span>
          </div>
        )}
      </div>
    </div>
  );
}

function LeaderboardRow({ row, isMe }: { row: LeaderboardEntry; isMe?: boolean }) {
  const rate = toDecimal(row.returnRate);
  const up = !rate || rate.greaterThanOrEqualTo(0);
  const rowBg = isMe ? "var(--bg)" : "transparent";
  return (
    <>
      {/* 데스크톱(md 이상) — 기존 3칸 그리드 그대로. */}
      <div
        className="hidden items-center gap-3 px-7 py-3 md:grid"
        style={{ gridTemplateColumns: "60px minmax(0,1fr) 96px", background: rowBg, borderTop: "1px solid var(--line2)" }}
      >
        <span className="tabular-nums text-[15px] font-extrabold" style={{ color: row.rank <= 3 ? "var(--accent)" : "var(--mut2)" }}>
          {row.rank}위
        </span>
        <span className="flex min-w-0 items-center gap-1.5">
          <span className="overflow-hidden text-[15px] font-bold text-ellipsis whitespace-nowrap" style={{ color: "var(--ink)" }}>
            {row.nickname}
          </span>
          {isMe && (
            <span className="flex-none rounded-full px-2 py-0.5 text-[11px] font-bold text-white" style={{ background: "var(--accent)" }}>
              나
            </span>
          )}
        </span>
        <span className="text-right tabular-nums text-[15px] font-bold" style={{ color: up ? "var(--up)" : "var(--down)" }}>
          {formatPercent(row.returnRate)}
        </span>
      </div>

      {/* 모바일(md 미만) — 3칸 그리드 대신 한 줄짜리 flex(순위·닉네임을
          왼쪽에서 줄이고, 수익률은 오른쪽에 고정폭 없이 배치)로 바꿔
          좁은 화면에서도 닉네임이 억지로 짓눌리지 않게 한다. */}
      <div
        className="flex items-center gap-2.5 px-7 py-3 md:hidden"
        style={{ background: rowBg, borderTop: "1px solid var(--line2)" }}
      >
        <span className="shrink-0 tabular-nums text-[14px] font-extrabold" style={{ color: row.rank <= 3 ? "var(--accent)" : "var(--mut2)" }}>
          {row.rank}위
        </span>
        <span className="flex min-w-0 flex-1 items-center gap-1.5">
          <span className="overflow-hidden text-[14px] font-bold text-ellipsis whitespace-nowrap" style={{ color: "var(--ink)" }}>
            {row.nickname}
          </span>
          {isMe && (
            <span className="flex-none rounded-full px-2 py-0.5 text-[11px] font-bold text-white" style={{ background: "var(--accent)" }}>
              나
            </span>
          )}
        </span>
        <span className="shrink-0 tabular-nums text-[14px] font-bold" style={{ color: up ? "var(--up)" : "var(--down)" }}>
          {formatPercent(row.returnRate)}
        </span>
      </div>
    </>
  );
}

/**
 * 유형별 성과 비교 모달(#153 Phase 3, `GET /api/reports/leaderboard/types`) — 16개 투자
 * 유형의 평균 수익률을 한눈에 비교한다. 절대 금액이 아니라 수익률%만 노출하는 리더보드와
 * 같은 사행성 배제 원칙을 그대로 따른다(백엔드 주석 참고). 미분류(유형 없음)는 백엔드가
 * 애초에 집계에서 뺀다.
 */
function TypeComparisonModal({ myTypeCode, onClose }: { myTypeCode: string | null; onClose: () => void }) {
  const [data, setData] = useState<LeaderboardTypes | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getLeaderboardTypes()
      .then((res) => {
        if (!cancelled) setData(res);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 평균 수익률 높은 순으로 보여준다 — 응답 자체는 정렬을 보장하지 않는다.
  const sorted = [...(data?.types ?? [])].sort(
    (a, b) => Number(b.avgReturnRate) - Number(a.avgReturnRate)
  );

  return (
    <div
      className="fixed inset-0 z-[150] flex items-center justify-center px-4"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
      onClick={onClose}
    >
      <div
        className="flex max-h-[86vh] w-full max-w-[560px] flex-col overflow-hidden rounded-[24px]"
        style={{ background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-4 px-7 pt-6.5 pb-4.5">
          <div>
            <h3 className="text-[22px] font-extrabold tracking-[-0.02em]" style={{ color: "var(--ink)" }}>유형별 비교</h3>
            <p className="mt-1.5 text-[13px]" style={{ color: "var(--mut2)" }}>
              {data?.asOf ? `${formatDate(data.asOf)} 기준 · 유형별 평균 수익률` : "유형별 평균 수익률"}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="report-modal-close-btn cursor-pointer rounded-full px-3 py-1.5 text-[13px] font-semibold"
            style={{ color: "var(--mut)" }}
          >
            닫기
          </button>
        </div>

        {/* 반응형 웹 적용 — 위 리더보드와 같은 이유로 md 미만에서는 헤더를
            숨긴다(TypeComparisonRow도 같은 폭에서 카드형으로 바뀐다). */}
        <div
          className="hidden px-7 py-2.5 text-[12px] font-bold md:grid"
          style={{ gridTemplateColumns: "36px minmax(0,1fr) 70px 96px", background: "var(--bg)", color: "var(--mut2)" }}
        >
          <span>순위</span>
          <span>유형</span>
          <span className="text-right">인원</span>
          <span className="text-right">평균 수익률</span>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto">
          {loading ? (
            <div className="py-14 text-center text-[13px]" style={{ color: "var(--mut2)" }}>불러오는 중…</div>
          ) : error ? (
            <div className="py-14 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
              유형별 비교를 불러오지 못했어요.
            </div>
          ) : sorted.length === 0 ? (
            <div className="py-14 text-center text-[13px]" style={{ color: "var(--mut2)" }}>
              아직 집계된 유형이 없어요. 다음 배치를 기다려주세요.
            </div>
          ) : (
            sorted.map((row, i) => (
              <TypeComparisonRow key={row.typeCode} rank={i + 1} row={row} isMyType={row.typeCode === myTypeCode} />
            ))
          )}
        </div>

        <div
          className="px-7 py-3.5 text-[12.5px]"
          style={{ borderTop: "1px solid var(--line2)", color: "var(--mut2)" }}
        >
          미분류(보유 종목 2개 미만) 계좌는 유형 자체가 없어 비교에서 빠져요
        </div>
      </div>
    </div>
  );
}

function TypeComparisonRow({ rank, row, isMyType }: { rank: number; row: LeaderboardTypeEntry; isMyType: boolean }) {
  const personaType = PERSONALITY_TYPES[row.typeCode];
  const rate = toDecimal(row.avgReturnRate);
  const up = !rate || rate.greaterThanOrEqualTo(0);
  const rowBg = isMyType ? "var(--bg)" : "transparent";
  const nameNode = (
    <span className="flex min-w-0 items-center gap-1.5">
      <span className="overflow-hidden text-[14.5px] font-bold text-ellipsis whitespace-nowrap" style={{ color: "var(--ink)" }}>
        {personaType?.nickname ?? row.typeLabel}
      </span>
      <span className="font-mono text-[11px]" style={{ color: "var(--mut2)" }}>{row.typeCode}</span>
      {isMyType && (
        <span className="flex-none rounded-full px-2 py-0.5 text-[11px] font-bold text-white" style={{ background: "var(--accent)" }}>
          내 유형
        </span>
      )}
    </span>
  );
  const returnNode = (
    <span className="tabular-nums text-[15px] font-bold" style={{ color: up ? "var(--up)" : "var(--down)" }}>
      {formatPercent(row.avgReturnRate)}
    </span>
  );
  return (
    <>
      {/* 데스크톱(md 이상) — 기존 4칸 그리드 그대로. */}
      <div
        className="hidden items-center gap-3 px-7 py-3 md:grid"
        style={{ gridTemplateColumns: "36px minmax(0,1fr) 70px 96px", background: rowBg, borderTop: "1px solid var(--line2)" }}
      >
        <span className="tabular-nums text-[15px] font-extrabold" style={{ color: rank <= 3 ? "var(--accent)" : "var(--mut2)" }}>
          {rank}
        </span>
        {nameNode}
        <span className="text-right tabular-nums text-[13px]" style={{ color: "var(--mut2)" }}>{formatNumber(row.count)}명</span>
        <span className="text-right">{returnNode}</span>
      </div>

      {/* 모바일(md 미만) — 4칸 그리드 대신 2줄 카드형. */}
      <div className="flex flex-col gap-1 px-7 py-3 md:hidden" style={{ background: rowBg, borderTop: "1px solid var(--line2)" }}>
        <div className="flex items-center gap-2.5">
          <span className="shrink-0 tabular-nums text-[14px] font-extrabold" style={{ color: rank <= 3 ? "var(--accent)" : "var(--mut2)" }}>
            {rank}
          </span>
          {nameNode}
        </div>
        <div className="flex items-center justify-between pl-[26px] text-[13px]" style={{ color: "var(--mut2)" }}>
          <span>{formatNumber(row.count)}명</span>
          {returnNode}
        </div>
      </div>
    </>
  );
}
