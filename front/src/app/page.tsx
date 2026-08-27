"use client";

import Link from "next/link";
import { HeroDots } from "@/components/HeroDots";
import { TiltCard } from "@/components/TiltCard";
import { Reveal } from "@/components/Reveal";

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
  return (
    <div>
      {/* 히어로 */}
      <Reveal delay={0}>
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
            <p className="my-3 max-w-[440px] text-[16px] leading-[1.6]" style={{ color: "var(--heroBody)" }}>
              실제 시장 시세로 국내·해외 주식을 사고팔며 투자 감각을 길러보세요.
              <br />
              모의 투자금 5,000만원이 가입 즉시 지급돼요.
            </p>
            <div className="mt-5 flex gap-2.5">
              <Link
                href="/rankings"
                className="rounded-xl px-6 py-3 text-[14px] font-bold"
                style={{ background: "var(--ctaBtn)", color: "var(--ctaBtnText)" }}
              >
                모의 투자금 받고 시작하기
              </Link>
              <Link
                href="/guide"
                className="rounded-xl px-5 py-3 text-[14px] font-semibold"
                style={{ background: "#ffffff", color: "#000000" }}
              >
                이용가이드 보기
              </Link>
            </div>
            <div className="mt-3.5 text-[12px]" style={{ color: "var(--heroSub)" }}>
              실제 돈이 오가지 않아요 · 언제든 포트폴리오를 초기화할 수 있어요
            </div>
          </div>
          <div className="flex flex-1 items-center justify-center">
            <HeroDots />
          </div>
        </div>
      </Reveal>

      {/* 3단계 */}
      <Reveal delay={0.08} className="mt-12 mb-10">
        <h2 className="text-center text-[28px] font-extrabold" style={{ color: "var(--ink)" }}>
          이렇게 사용해요
        </h2>
        <p className="mt-4 mb-6 text-center text-[15px]" style={{ color: "var(--mut)" }}>
          가입부터 첫 거래까지 3단계
        </p>
        <div className="flex gap-4 max-md:flex-col">
          {STEPS.map((s) => (
            <TiltCard
              key={s.step}
              className="flex-1 rounded-[20px] p-6.5 text-center"
              // 그림자는 넣지 않습니다 (design_handoff README)
              style={{ background: "var(--card)" }}
            >
              <span
                className="inline-block rounded-lg px-2.5 py-1 text-[12px] font-extrabold"
                style={{ background: "var(--highlightSoft)", color: "var(--onHighlightSoftText)" }}
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
      <Reveal delay={0.16} className="mb-10">
        <h2 className="text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>
          증권사 앱과 무엇이 다른가요?
        </h2>
        <p className="mb-4.5 text-[14px]" style={{ color: "var(--mut)" }}>
          증권사 앱은 거래를 체결시키는 도구고, 저희는 거래를 이해시키는 도구예요.
        </p>
        <div className="overflow-hidden rounded-[20px]" style={{ background: "var(--card)" }}>
          <div
            className="grid text-[13.5px] opacity-0"
            style={{
              gridTemplateColumns: "1fr 1.2fr 1.2fr",
              animation: "riseIn .6s cubic-bezier(.22,1,.36,1) .34s forwards",
            }}
          >
            <div className="px-6 py-4" style={{ borderBottom: "1px solid var(--line2)" }} />
            <div className="px-6 py-4 font-medium" style={{ borderBottom: "1px solid var(--line2)", color: "var(--mut)" }}>
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
                animation: `riseIn .6s cubic-bezier(.22,1,.36,1) ${0.34 + (i + 1) * 0.11}s forwards`,
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
      <Reveal delay={0.24} className="mb-10">
        <div className="rounded-[24px] px-11 py-11 text-center" style={{ background: "var(--ctaBanner)" }}>
          <h2 className="text-[24px] font-extrabold text-white">첫 거래는 오늘, 첫 손실은 0원</h2>
          <p className="mx-auto my-2.5 max-w-[420px] text-[14px] text-white/80">
            모의 투자금 5,000만원으로 지금 시작해보세요
          </p>
          <Link
            href="/rankings"
            className="mt-2 inline-block rounded-full px-8 py-3 text-[14px] font-bold transition-[background] duration-[180ms]"
            style={{ background: "#fff", color: "var(--ctaBannerBtnText)" }}
            onMouseEnter={(e) => (e.currentTarget.style.background = "var(--ctaBannerBtnHover)")}
            onMouseLeave={(e) => (e.currentTarget.style.background = "#fff")}
          >
            시작하기
          </Link>
        </div>
      </Reveal>

      {/* 푸터 고지 */}
      <Reveal delay={0.32}>
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
