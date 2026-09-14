"use client";

import { useState } from "react";
import { PillTabs } from "@/components/PillTabs";
import { Reveal } from "@/components/Reveal";
import { RevealText } from "@/components/RevealText";
import { WikiPanel } from "@/components/WikiPanel";
import { useTheme } from "@/components/ThemeProvider";

const SECTIONS = [
  {
    title: "1. 모의 투자금 받기",
    body: (
      <>
        회원가입을 하면 <b>모의 투자금 5,000만원</b>이 자동으로 지급돼요. 실제 돈이 아니므로 잃어도 아무
        손해가 없어요. 자금을 다 소진했거나 처음부터 다시 해보고 싶다면 마이페이지에서{" "}
        <b>포트폴리오 초기화</b>를 누르면 5,000만원으로 되돌아가요.
      </>
    ),
  },
  {
    title: "2. 종목 고르기",
    body: (
      <>
        주식 종목 랭킹에서 <b>거래대금 상위 100개</b> 종목을 국내·해외로 나눠 보여드려요. 거래대금은 그
        종목에 실제로 오간 돈의 규모로, 시장의 관심이 어디에 쏠려 있는지 보여주는 지표예요. 종목명이나
        티커로 직접 검색할 수도 있어요.
      </>
    ),
  },
  {
    title: "3. 매수하기",
    body: (
      <>
        종목 상세 페이지에서 수량을 입력하고 매수 버튼을 누르면 <b>현재가로 즉시 체결</b>돼요. 이때
        예수금에서 주문 금액과 수수료가 함께 빠져나가고, 보유 종목에 그 수량이 더해져요. 주문 버튼을
        누르기 전에 총 차감액을 미리 확인할 수 있어요.
      </>
    ),
  },
  {
    title: "4. 매도하기",
    body: (
      <>
        보유한 종목을 팔면 매도 금액에서 <b>수수료와 증권거래세</b>가 빠진 금액이 예수금으로 들어와요.
        여기서 많은 초보자가 놀라는 지점이 있어요 — <b>산 가격 그대로 팔면 본전이 아니라 손해</b>예요.
        사고팔 때마다 비용이 발생하기 때문이에요.
      </>
    ),
  },
  {
    title: "5. 거래 가능 시간",
    body: (
      <>
        실제 주식시장과 동일하게 운영돼요. 국내 주식은 <b>평일 09:00~15:30</b>, 미국 주식은{" "}
        <b>한국 시간 기준 밤~새벽</b>에만 거래할 수 있어요. 주말과 공휴일에는 거래가 불가능해요.
        <br />
        다만 <b>시세 조회와 차트는 언제든 볼 수 있어요.</b>
      </>
    ),
  },
  {
    title: "6. 거래할 수 없는 경우",
    body: (
      <>
        거래정지·정리매매 종목이거나, 주문가능금액이 부족하거나, 보유 수량보다 많이 팔려고 하면 주문이
        거절돼요. 실제 시장의 규칙을 그대로 적용하며, 거절될 때는 이유를 함께 안내해 드려요.
      </>
    ),
  },
];

export default function GuidePage() {
  const [tab, setTab] = useState<"guide" | "wiki">("guide");
  const { theme } = useTheme();

  return (
    <div>
      <Reveal delay={0}>
        <PillTabs
          options={[
            { value: "guide", label: "이용가이드" },
            { value: "wiki", label: "금융 용어 위키" },
          ]}
          value={tab}
          onChange={(v) => setTab(v as "guide" | "wiki")}
          trackClassName="mb-4.5 w-[200px] gap-0.5 rounded-full p-[3px]"
          // 라이트/다크 토글 뒤 트랙과 동일한 스타일로 맞춰달라는 요청 —
          // 기존 alpha 값을 절반으로 낮췄다.
          trackStyle={{
            background: theme === "dark" ? "rgba(255,255,255,.015)" : "rgba(15,56,104,.03)",
            border: theme === "dark" ? "1px solid rgba(255,255,255,.03)" : "1px solid rgba(15,56,104,.06)",
          }}
          buttonClassName="rounded-full px-0 py-2 text-[13px] font-bold"
          inactiveTextStyle={{ color: "var(--mut)" }}
        />
      </Reveal>

      {tab === "wiki" ? (
        <WikiPanel />
      ) : (
        <>
          {/* 랭킹 화면 문구에 이미 적용한 토스인슈어런스(pd-recruit.tossinsu.com)
              스타일 Line Reveal(아래→위 마스크 등장)을 이용가이드 탭의 문구·
              컴포넌트에도 적용해달라는 요청 — 제목/부제, 카드 6개의 제목·본문,
              하단 안내 문구까지 모두 RevealText로 바꿨다. 각 요소를 감싸던
              Reveal(카드 전체가 살짝 떠오르는 기존 애니메이션)은 그대로 두고,
              그 안의 텍스트만 RevealText로 한 줄씩 마스크 안에서 올라오게
              했다 — RevealText는 자체 IntersectionObserver로 각 카드가 실제로
              스크롤에 걸릴 때 개별적으로 반응한다. */}
          <Reveal delay={0.02}>
            <RevealText
              as="h2"
              className="text-[26px] font-extrabold"
              style={{ color: "var(--ink)" }}
              lines={["이용가이드"]}
            />
          </Reveal>
          <Reveal delay={0.08} className="mt-2.5 mb-6">
            <RevealText
              as="p"
              className="text-[15px]"
              style={{ color: "var(--mut)" }}
              lines={["이 서비스에서 거래가 어떻게 이루어지는지 안내해 드려요"]}
            />
          </Reveal>

          {/* 왼쪽 열(1·2·3)과 오른쪽 열(4·5·6)이 같은 줄끼리 카드 높이를 맞춰야 해서
              (예: "3. 매수하기"와 "6. 거래할 수 없는 경우"), 두 개의 독립된 flex 컬럼
              대신 하나의 grid로 1,4 / 2,5 / 3,6 순서로 배치한다 — grid는 같은 행의
              칸들이 자동으로 높이를 맞춰준다(flex 컬럼 두 개로는 그게 안 된다). */}
          <div className="grid grid-cols-2 gap-4 gap-y-3.5 max-md:grid-cols-1">
            {[SECTIONS[0], SECTIONS[3], SECTIONS[1], SECTIONS[4], SECTIONS[2], SECTIONS[5]].map((s, i) => (
              <Reveal key={s.title} delay={0.14 + i * 0.06}>
                <div
                  className="h-full"
                  style={{ background: "var(--card)", borderRadius: 20, padding: "22px 24px" }}
                >
                  <RevealText
                    as="h4"
                    className="mb-1.5 text-[15px] font-bold"
                    style={{ color: "var(--ink)" }}
                    lines={[s.title]}
                  />
                  <RevealText
                    as="p"
                    className="text-[13.5px] leading-relaxed"
                    style={{ color: "var(--body)" }}
                    baseDelayMs={45}
                    lines={[s.body]}
                  />
                </div>
              </Reveal>
            ))}
          </div>

          <Reveal delay={0.5} className="mt-4.5">
            {/* "참고" 안내 박스 배경을 여러 파란 계열(히어로 카드와 같은
                그라데이션 → 연한 버전 → rgb(150,196,255) 단색)로 시도해본
                끝에, 직접 지정한 #dceefa(라이트 모드 히어로 배경과 같은
                톤의 옅은 하늘색) 단색으로 정했다 — 라이트/다크 모드
                공통으로 이 고정 값을 쓴다. 기존 var(--onAccentSoftText)와
                var(--ink)는 둘 다 다크 모드에서 밝은 색(각각
                #acd5ef·#f2f2f2)으로 뒤집히는 테마 변수라, 다크 모드에서도
                항상 옅은 파란색으로 고정된 이 배경 위에서는 대비가
                나빠진다 — 그래서 배경과 마찬가지로 테마와 무관한 고정
                짙은 남색(#0f3868, 라이트 모드 accent와 같은 톤)을 직접
                썼다. */}
            <div className="rounded-2xl px-5 py-4" style={{ background: "#dceefa" }}>
              <RevealText
                as="p"
                className="text-[12.5px] leading-relaxed"
                style={{ color: "#0f3868" }}
                lines={[
                  <>
                    <b>참고</b> — 이 서비스의 시세는 실제 시장 데이터를 사용하지만 수 초의 지연이 있으며, 회원의
                    매수·매도는 실제 시장 가격에 영향을 주지 않아요. 모의 투자 결과가 실제 투자 성과를 보장하지
                    않아요.
                  </>,
                ]}
              />
            </div>
          </Reveal>
        </>
      )}
    </div>
  );
}
