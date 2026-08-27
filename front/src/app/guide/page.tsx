"use client";

import { useState } from "react";
import { PillTabs } from "@/components/PillTabs";
import { Reveal } from "@/components/Reveal";

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

  return (
    <div>
      <PillTabs
        options={[
          { value: "guide", label: "이용가이드" },
          { value: "wiki", label: "금융 용어 위키" },
        ]}
        value={tab}
        onChange={(v) => setTab(v as "guide" | "wiki")}
        trackClassName="mb-4.5 w-[280px] gap-0.5 rounded-full p-[3px]"
        trackStyle={{ background: "rgba(15,56,104,.06)", border: "0.1px solid rgba(15,56,104,.12)" }}
        buttonClassName="rounded-full px-0 py-2.5 text-[13.5px] font-bold"
        inactiveTextStyle={{ color: "var(--mut)" }}
      />

      {tab === "wiki" ? (
        <div
          className="flex flex-col items-center justify-center gap-1.5 rounded-[24px] px-10 py-18 text-center"
          style={{ background: "var(--card)" }}
        >
          <span
            className="mb-2 inline-block rounded-full px-3.5 py-1.5 text-[12.5px] font-bold"
            style={{ background: "var(--accentSoft)", color: "var(--onAccentSoftText)" }}
          >
            준비 중
          </span>
          <h2 className="text-[20px] font-extrabold" style={{ color: "var(--ink)" }}>
            금융 용어 위키는 준비 중이에요
          </h2>
          <p className="mt-1 text-[13.5px] leading-relaxed" style={{ color: "var(--mut)" }}>
            모르는 용어가 나올 때마다 바로 찾아볼 수 있는 사전을 준비하고 있어요.
            <br />
            그동안은 이용가이드로 거래 흐름을 먼저 익혀보세요.
          </p>
          <button
            onClick={() => setTab("guide")}
            className="mt-5 rounded-full px-5 py-2.5 text-[13px] font-semibold transition-[background] duration-150"
            style={{ background: "var(--fill)", color: "var(--ink)" }}
            onMouseEnter={(e) => (e.currentTarget.style.background = "var(--line)")}
            onMouseLeave={(e) => (e.currentTarget.style.background = "var(--fill)")}
          >
            이용가이드 먼저 보기
          </button>
        </div>
      ) : (
        <>
          <Reveal delay={0.02}>
            <h2 className="text-[22px] font-extrabold" style={{ color: "var(--ink)" }}>
              이용가이드
            </h2>
          </Reveal>
          <Reveal delay={0.08} className="mb-6">
            <p className="text-[13.5px]" style={{ color: "var(--mut)" }}>
              이 서비스에서 거래가 어떻게 이루어지는지 안내해 드려요
            </p>
          </Reveal>

          <div className="flex gap-4 max-md:flex-col">
            <div className="flex-1 space-y-3.5">
              {SECTIONS.slice(0, 3).map((s, i) => (
                <Reveal key={s.title} delay={0.14 + i * 0.12}>
                  <div style={{ background: "var(--card)", borderRadius: 20, padding: "22px 24px" }}>
                    <h4 className="mb-1.5 text-[15px] font-bold" style={{ color: "var(--ink)" }}>{s.title}</h4>
                    <p className="text-[13.5px] leading-relaxed" style={{ color: "var(--body)" }}>{s.body}</p>
                  </div>
                </Reveal>
              ))}
            </div>
            <div className="flex-1 space-y-3.5">
              {SECTIONS.slice(3).map((s, i) => (
                <Reveal key={s.title} delay={0.2 + i * 0.12}>
                  <div style={{ background: "var(--card)", borderRadius: 20, padding: "22px 24px" }}>
                    <h4 className="mb-1.5 text-[15px] font-bold" style={{ color: "var(--ink)" }}>{s.title}</h4>
                    <p className="text-[13.5px] leading-relaxed" style={{ color: "var(--body)" }}>{s.body}</p>
                  </div>
                </Reveal>
              ))}
            </div>
          </div>

          <Reveal delay={0.5} className="mt-4.5">
            <div className="rounded-2xl px-5 py-4" style={{ background: "var(--accentSoft)" }}>
              <p className="text-[12.5px] leading-relaxed" style={{ color: "var(--onAccentSoftText)" }}>
                <b>참고</b> — 이 서비스의 시세는 실제 시장 데이터를 사용하지만 수 초의 지연이 있으며, 회원의
                매수·매도는 실제 시장 가격에 영향을 주지 않아요. 모의 투자 결과가 실제 투자 성과를 보장하지
                않아요.
              </p>
            </div>
          </Reveal>
        </>
      )}
    </div>
  );
}
