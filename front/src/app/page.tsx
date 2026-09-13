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
          {/* 히어로 문구 우측의 픽셀 도트 패턴(HeroDots)을 제거하고, 그
              자리에 첨부받은 배경 이미지(파란 웨이브 그래픽)를 넣어달라는
              요청 — 인트로 화면(investup-hero-bg.png)에 쓴 것과 완전히
              같은 파일이라(md5 동일) 새로 추가하지 않고 그대로
              재사용했다. 이미지 바깥쪽으로 갈수록 옅어지는 그라데이션은
              인트로 화면에서 여러 차례 시행착오 끝에 정착한 값을 그대로
              가져왔다 — mask-image의 타원 크기를 50% 밑(46%)으로 둬서
              실제 이미지 가장자리에 닿기 전에 이미 완전히 투명해지게
              하고(50% 이상으로 주면 가장자리에 색이 남는 문제가 있었다),
              불투명 구간은 8%로 좁게 둬서 거의 전 구간이 서서히
              옅어지는 폭넓은 그라데이션이 되게 했다.
              크기를 키워달라는 요청을 여러 차례 거쳤는데(380px →
              640px → 900px), max-width만 올리는 방식은 한계가 있었다 —
              이 셀의 실제 너비 자체가 max-width보다 작았기 때문에(카드
              전체 너비를 문구 영역과 1.2:1로 나눠 쓰는 구조), 상한값을
              더 키워도 실제로는 커지지 않았다. 게다가 이미지는
              height: auto라 너비만큼만 커지고, 카드 높이(문구 쪽
              내용이 좌우하는)는 그대로라서 위아래로 빈 공간이 남아
              "칸을 거의 다 채우는" 느낌이 나지 않았다.
              그래서 "이 칸(사각형 영역) 자체를 거의 다 채워달라"는
              요청에 맞춰 방식을 바꿨다 — 이 div에 self-stretch를 줘서
              부모의 items-center를 오버라이드하고 문구 쪽과 같은 높이로
              늘어나게 한 뒤, 이미지 자체를 h-full w-full
              object-cover로 이 늘어난 칸을 가로·세로 모두 꽉 채우도록
              했다(원본 이미지 비율과 칸 비율이 달라 약간 잘려 보일 수
              있지만, 가장자리 그라데이션이 이미 경계를 부드럽게
              가려준다). */}
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
