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
          {/* 히어로 문구 우측 사각형 컴포넌트의 배경에 토스임팩트
              (https://toss.im/impact) 사이트의 "impact for all / 모두의
              경험" 카드에 쓰인 radial-gradient(연한 하늘색,
              rgb(150,196,255)) 배경을 그대로 적용해달라는 요청 — 처음엔
              이 자리를 텍스트 카드로 통째로 바꿔봤지만, "그 디자인(배경)만
              가져오고 기존에 첨부했던 main_object 이미지(파도 그래픽,
              investup-hero-bg.png)는 그대로 복구해달라"는 후속 요청에
              따라 이미지는 원래 구현으로 되돌리고 이 radial-gradient는
              이미지를 담는 바깥 박스의 배경으로만 얹었다. 이미지 자체의
              가장자리 mask-fade(50% 미만 46%로 완전히 투명해지는 구간
              확보) 덕분에, 이미지가 옅어지는 가장자리 쪽에서 이 gradient
              배경이 은은하게 비쳐 보이는 효과가 난다. 라이트/다크 모드
              모두 적용했다(테마 적용 범위 기본값) — 라이트 모드는 히어로
              배경(--heroBg, 옅은 하늘색) 위에 살짝 더 짙은 파란
              빛무리로, 다크 모드는 히어로 배경(--heroBg, 짙은 남색)
              위에 은은한 하늘색 스포트라이트로 보인다. 바깥 박스에도
              카드 느낌을 살리려고 참고 사이트와 비슷한 rounded-[28px]를
              줬다. */}
          <div
            className="flex flex-1 items-center justify-center self-stretch overflow-hidden rounded-[28px]"
            style={{
              background: "radial-gradient(120% 100% at 42% 55%, rgba(150,196,255,0.55) 0%, rgba(150,196,255,0) 70%)",
            }}
          >
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
