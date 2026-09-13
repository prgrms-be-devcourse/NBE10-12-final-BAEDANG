"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Reveal } from "@/components/Reveal";
import { useTheme } from "@/components/ThemeProvider";
import { getMarketStatus, type MarketStatus } from "@/lib/api";

const MARKET_LABEL: Record<string, string> = { KR: "국내장", US: "해외장" };

function formatMarketTime(iso: string): string {
  return new Date(iso).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

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
          // 히어로 카드의 세로 길이를 좀 더 늘려달라는 요청 — 좌우 폭에
          // 영향을 주는 px-12는 그대로 두고, 세로 길이를 결정하는
          // 위아래 패딩만 py-14(56px) → py-20(80px)으로 키웠다.
          className="my-4 flex items-center gap-10 rounded-[28px] px-12 py-20 max-md:flex-col"
          // "실전처럼 경험하고, 나만의 투자 감각을 키워요" 문구가 놓인 이
          // 히어로 사각형 자체의 배경에 토스임팩트(https://toss.im/impact)
          // 사이트의 "impact for all / 모두의 경험" 카드에 쓰인
          // radial-gradient(연한 하늘색, rgb(150,196,255))를 적용해달라는
          // 요청 — 처음엔 이미지를 담는 우측의 작은 박스에 얹어봤지만,
          // 문구가 놓인 히어로 카드 전체의 배경에 적용해달라는 후속 요청에
          // 따라 자리를 옮겼다. 기존 배경색(var(--heroBg))을 완전히
          // 대체하지 않고 gradient를 그 위에 얹는 방식(background에 두
          // 레이어를 콤마로 나열 — 앞쪽이 위, 뒤쪽이 바탕)을 써서, gradient가
          // 옅어지는 가장자리에서는 기존 라이트=옅은 하늘색/다크=짙은
          // 남색 히어로 배경이 그대로 비쳐 보인다.
          style={{
            background:
              "radial-gradient(120% 100% at 42% 55%, rgba(150,196,255,0.55) 0%, rgba(150,196,255,0) 70%), var(--heroBg)",
          }}
        >
          <div className="flex-[1.2]">
            {/* "투자 연습장" 배지 문구를 제거해달라는 요청. */}
            <h1
              className="mt-4.5 mb-3.5 text-[38px] leading-[1.35] font-extrabold tracking-[-0.02em]"
              style={{ color: "var(--heroText)" }}
            >
              실전처럼 경험하고,
              <br />
              나만의 투자 감각을 키워요
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
                가이드 보기
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
          {/* 히어로 문구 우측의 픽셀 도트 패턴(HeroDots)을 제거하고, 그
              자리에 첨부받은 배경 이미지(파란 웨이브 그래픽,
              investup-hero-bg.png — 인트로 화면과 공유하는 자산)를 넣고,
              가장자리로 갈수록 옅어지는 mask-fade와 self-stretch +
              object-cover로 이 칸을 가득 채우던 기존 구현이다. 이
              박스 자체에는 별도 배경을 얹지 않는다 — 토스임팩트 카드
              스타일 radial-gradient는 이 작은 박스가 아니라, 문구가
              놓인 히어로 카드 전체의 배경(위쪽 div의 style 참고)에
              적용했다. */}
          <div className="flex flex-1 items-center justify-center self-stretch">
            {/* eslint-disable-next-line @next/next/no-img-element -- 장식용 이미지 하나뿐이라 next/image 최적화 이점이 없다 */}
            <img
              src="/investup-hero-bg.png"
              alt=""
              className="h-full w-full object-cover"
              style={{
                display: "block",
                WebkitMaskImage: "radial-gradient(ellipse 46% 46% at 50% 50%, #000 8%, transparent 100%)",
                maskImage: "radial-gradient(ellipse 46% 46% at 50% 50%, #000 8%, transparent 100%)",
              }}
            />
          </div>
        </div>
      </Reveal>

      {/* "이렇게 사용해요"(3단계 STEP 카드), "증권사 앱과 무엇이
          다른가요?"(비교표), "첫 거래는 오늘, 첫 손실은 0원"(CTA 배너)
          세 섹션을 제거해달라는 요청으로 통째로 지웠다 — 이 섹션들에만
          쓰이던 TiltCard/STEPS/COMPARE_ROWS도 다른 곳에서 쓰이지 않는
          것을 확인(grep)하고 함께 정리했다. */}

      {/* 푸터 고지 — 히어로 카드와의 간격을 많이 띄워달라는 요청. 3단계
          /비교표/CTA 배너 섹션이 사라진 뒤로 히어로의 my-4(16px) 여백만
          남아 있어 간격이 좁았던 것을, mt-28(112px)로 크게 벌렸다.
          이후 좀 더 좁혀달라는 요청으로 mt-20(80px)으로 줄였고,
          조금만 더 좁혀달라는 요청으로 mt-16(64px)으로, 다시 한 번
          조금만 더 좁혀달라는 요청으로 mt-12(48px)로 줄였다. */}
      <Reveal delay={0.64} duration={1} className="mt-12">
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
