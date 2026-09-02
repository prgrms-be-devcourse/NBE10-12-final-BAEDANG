"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { HeroDots } from "@/components/HeroDots";
import { TiltCard } from "@/components/TiltCard";
import { Reveal } from "@/components/Reveal";
import { useTheme } from "@/components/ThemeProvider";
import { getMarketStatus, type MarketStatus } from "@/lib/api";

const MARKET_LABEL: Record<string, string> = { KR: "국내장", US: "해외장" };

function formatMarketTime(iso: string): string {
  return new Date(iso).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

const STEPS = [
  {
    step: "STEP 1",
    title: "모의 투자금 5,000만원 받기",
    desc: (
      <>
        가입하면 자동 지급돼요.
        <br />
        다 쓰면 포트폴리오를 초기화해
        <br />
        다시 시작할 수 있어요.
      </>
    ),
  },
  {
    step: "STEP 2",
    title: "랭킹에서 종목 고르기",
    desc: (
      <>
        거래대금 상위 100개 종목을
        <br />
        국내·해외로 나눠 보여드려요.
      </>
    ),
  },
  {
    step: "STEP 3",
    title: "실제 시세로 매수·매도",
    desc: (
      <>
        장 운영 시간에 시장가로 즉시 체결돼요.
        <br />
        수수료와 세금도 그대로 반영돼요.
      </>
    ),
  },
];

const COMPARE_ROWS = [
  { label: "목적", other: "거래 체결", ours: "학습과 훈련" },
  { label: "실수했을 때", other: "실제 손실, 되돌릴 수 없음", ours: "손실 없음, 초기화하고 다시" },
  { label: "수수료·세금", other: "거래 후 결과에만 반영", ours: "주문 전 미리 보여줌" },
  { label: "사용법 안내", other: "없음", ours: "이용가이드 · 용어 위키 제공" },
];

export default function MainPage() {
  const { theme } = useTheme();
  const [marketStatus, setMarketStatus] = useState<MarketStatus | null>(null);

  // 장식용 배지라 실패해도 조용히 숨긴다 — 메인 화면이 이 정보 없이도 완전하기 때문이다.
  useEffect(() => {
    let cancelled = false;
    getMarketStatus()
      .then((status) => {
        if (!cancelled) setMarketStatus(status);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div>
      {/* 히어로 */}
      <Reveal delay={0} duration={1}>
        <div
          className="my-4 flex items-center gap-10 rounded-[28px] px-12 py-14 max-md:flex-col"
          style={{ background: "var(--heroBg)" }}
        >
          <div className="flex-[1.2]">
            <span
              className="inline-block rounded-full px-3.5 py-1.5 text-[13px] font-bold"
              style={{ background: "var(--highlight)", color: "var(--highlightText)" }}
            >
              투자 연습장
            </span>
            <h1
              className="mt-4.5 mb-3.5 text-[38px] leading-[1.35] font-extrabold tracking-[-0.02em]"
              style={{ color: "var(--heroText)" }}
            >
              잃어도 괜찮은 돈으로,
              <br />
              잃지 않는 법을 배워요
            </h1>
            <p className="my-3 max-w-[440px] text-[16px] leading-[1.6]" style={{ color: theme === "dark" ? "#ffffff" : "#000000" }}>
              실제 시장 시세로 국내·해외 주식을 사고팔며 투자 감각을 길러보세요.
              <br />
              <b className="font-bold">모의 투자금 5,000만원</b>이 가입 즉시 지급돼요.
            </p>
            <div className="mt-5 flex gap-2.5">
              <Link
                href="/rankings"
                className="rounded-[12px] px-6 py-3 text-[14px] font-bold"
                style={{
                  background: theme === "dark" ? "var(--accent)" : "var(--ctaBtn)",
                  color: theme === "dark" ? "#ffffff" : "var(--ctaBtnText)",
                }}
              >
                모의 투자금 받고 시작하기
              </Link>
              <Link
                href="/guide"
                className="rounded-[12px] px-5 py-3 text-[14px] font-semibold"
                style={{
                  background: theme === "dark" ? "#1a1c1f" : "#ffffff",
                  color: theme === "dark" ? "#ffffff" : "#000000",
                }}
              >
                이용가이드 보기
              </Link>
            </div>
            <div className="mt-3.5 text-[14px]" style={{ color: "var(--heroSub)" }}>
              실제 돈이 오가지 않아요 · 언제든 포트폴리오를 초기화할 수 있어요
            </div>
            {marketStatus && (
              <div className="mt-3 flex flex-wrap gap-2">
                {marketStatus.markets.map((m) => (
                  <span
                    key={m.marketCountry}
                    // 회원가입 버튼과 동일한 배경(var(--accent))을 쓴다. 라이트 모드는
                    // accent가 짙은 네이비라 흰 글자가 맞고, 다크 모드는 accent가 밝은
                    // 하늘색이라 요청대로 검정 글자가 대비가 더 좋다.
                    className="inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[12.5px] font-semibold"
                    style={{ background: "var(--accent)", color: theme === "dark" ? "#000000" : "#ffffff" }}
                  >
                    <span
                      className="h-1.5 w-1.5 rounded-full"
                      style={{
                        background: m.open ? "var(--up)" : theme === "dark" ? "rgba(0,0,0,.45)" : "rgba(255,255,255,.55)",
                      }}
                    />
                    {MARKET_LABEL[m.marketCountry] ?? m.marketCountry}{" "}
                    {m.open ? "개장중" : m.nextOpensAt ? `마감 · ${formatMarketTime(m.nextOpensAt)} 개장` : "마감"}
                  </span>
                ))}
              </div>
            )}
          </div>
          <div className="flex flex-1 items-center justify-center">
            <HeroDots />
          </div>
        </div>
      </Reveal>

      {/* 3단계 */}
      <Reveal delay={0.16} duration={1} className="mt-12 mb-10">
        <h2 className="text-center text-[28px] font-extrabold" style={{ color: "var(--ink)" }}>
          이렇게 사용해요
        </h2>
        <p className="mt-2 mb-6 text-center text-[15px]" style={{ color: "var(--mut)" }}>
          가입부터 첫 거래까지 3단계
        </p>
        <div className="flex gap-4 max-md:flex-col">
          {STEPS.map((s) => (
            <TiltCard
              key={s.step}
              className="flex-1 rounded-[20px] p-6.5 text-center"
              // 그림자는 넣지 않습니다 (design_handoff README). 배경은 카드 정중앙이
              // 가장 진하고 바깥으로 갈수록 옅어지는 radial gradient. 테두리는
              // 요청대로 흰색으로 마무리하되, 다크 모드에서 그대로 흰색을 쓰면
              // 카드가 어두운 배경 위에 하얗게 떠 보이므로 다크 모드의 "흰색 역할"인
              // 카드 배경색(var(--card), #1a1a1a)으로 마무리했다 — 라이트 모드는
              // var(--card)가 정확히 #ffffff라 요청한 흰색 그대로다.
              style={{
                // 다크 모드만 좀 더 어둡게 해달라는 요청 — 베이스 블루 자체를 한
                // 단계 낮췄다(라이트 모드 값은 그대로 유지). 이후 "더 진하게"
                // 요청을 두 차례 받아 불투명도를 올려봤는데, 결국 마음에 들지
                // 않는다는 피드백을 받아 이 원래 값으로 되돌렸다.
                background:
                  theme === "dark"
                    ? "radial-gradient(120% 120% at 50% 50%, rgba(45,95,155,0.65) 0%, rgba(45,95,155,0.28) 45%, var(--card) 100%)"
                    : "radial-gradient(120% 120% at 50% 50%, rgba(196,222,248,0.85) 0%, rgba(196,222,248,0.4) 45%, #ffffff 100%)",
              }}
            >
              <span
                className="inline-block text-[12px] font-extrabold"
                // 사각형 배지가 카드 위에서 어색해 보인다는 피드백을 받아 배경·
                // 패딩·라운드를 없애고 글자만 남겼다. 색은 그대로 accentSoft
                // 텍스트 톤을 써서 카드의 블루 그라데이션과 계속 어울리게 했다.
                style={{ color: "var(--onAccentSoftText)" }}
              >
                {s.step}
              </span>
              <h4 className="my-2 text-[16px] font-bold" style={{ color: "var(--ink)" }}>
                {s.title}
              </h4>
              <p className="text-[14px] leading-[1.6]" style={{ color: "var(--mut)" }}>
                {s.desc}
              </p>
            </TiltCard>
          ))}
        </div>
      </Reveal>

      {/* 비교표 */}
      <Reveal delay={0.32} duration={1} className="mb-10">
        <h2 className="text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>
          증권사 앱과 무엇이 다른가요?
        </h2>
        <p className="mt-3 mb-4.5 text-[14px]" style={{ color: theme === "dark" ? "#ffffff" : "#000000" }}>
          증권사 앱은 거래를 <b className="font-bold">체결</b>시키는 도구고, 저희는 거래를{" "}
          <b className="font-bold">이해</b>시키는 도구예요.
        </p>
        <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
          <div
            className="grid text-[13.5px] opacity-0"
            style={{
              gridTemplateColumns: "1fr 1.2fr 1.2fr",
              animation: "riseIn .9s cubic-bezier(.22,1,.36,1) .5s forwards",
            }}
          >
            <div className="px-6 py-4" style={{ borderBottom: "1px solid var(--line2)" }} />
            <div className="px-6 py-4 font-bold" style={{ borderBottom: "1px solid var(--line2)", color: "var(--mut)" }}>
              일반 증권사 앱
            </div>
            <div
              className="px-6 py-4 font-extrabold"
              style={{ borderBottom: "1px solid var(--line2)", color: "var(--accentText)" }}
            >
              모의주식 트레이딩
            </div>
          </div>
          {COMPARE_ROWS.map((row, i) => (
            <div
              key={row.label}
              className="grid text-[13.5px] opacity-0"
              style={{
                gridTemplateColumns: "1fr 1.2fr 1.2fr",
                animation: `riseIn .9s cubic-bezier(.22,1,.36,1) ${0.5 + (i + 1) * 0.17}s forwards`,
              }}
            >
              <div className="px-6 py-4" style={{ borderBottom: "1px solid var(--line2)", color: "var(--mut)" }}>
                {row.label}
              </div>
              <div className="px-6 py-4" style={{ borderBottom: "1px solid var(--line2)", color: "var(--body)" }}>
                {row.other}
              </div>
              <div className="px-6 py-4 font-bold" style={{ borderBottom: "1px solid var(--line2)", color: "var(--ink)" }}>
                {row.ours}
              </div>
            </div>
          ))}
        </div>
      </Reveal>

      {/* CTA 배너 */}
      <Reveal delay={0.48} duration={1} className="mb-10">
        <div className="rounded-[24px] px-11 py-11 text-center" style={{ background: "var(--ctaBanner)" }}>
          <h2 className="text-[24px] font-extrabold text-white">첫 거래는 오늘, 첫 손실은 0원</h2>
          <p className="mx-auto my-2.5 max-w-[420px] text-[14px] text-white/80">
            모의 투자금 5,000만원으로 지금 시작해보세요
          </p>
          <Link
            href="/rankings"
            className="mt-2 inline-block rounded-[12px] px-8 py-3 text-[14px] font-extrabold transition-[background] duration-[180ms]"
            style={{ background: "#fff", color: "var(--ctaBannerBtnText)" }}
            onMouseEnter={(e) => (e.currentTarget.style.background = "var(--ctaBannerBtnHover)")}
            onMouseLeave={(e) => (e.currentTarget.style.background = "#fff")}
          >
            시작하기
          </Link>
        </div>
      </Reveal>

      {/* 푸터 고지 */}
      <Reveal delay={0.64} duration={1}>
        <p className="text-center text-[12.5px] leading-[1.8]" style={{ color: "var(--mut2)" }}>
          본 서비스는 투자 교육을 목적으로 하는 모의 투자 서비스예요. 실제 매매가 이루어지지 않으며, 특정
          종목에 대한 투자 조언이나 매매 권유를 제공하지 않아요.
          <br />
          시세는 토스증권 Open API를 통해 제공되며 실시간과 수 초의 차이가 있을 수 있어요.
        </p>
      </Reveal>
    </div>
  );
}
