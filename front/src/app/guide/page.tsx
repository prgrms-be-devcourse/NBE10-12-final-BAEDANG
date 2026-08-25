"use client";

import { useState } from "react";

const SECTIONS = [
  {
    title: "1. 모의 투자금 받기",
    body: (
      <>
        회원가입을 하면 <b>모의 투자금 5,000만원</b>이 자동으로 지급됩니다. 실제 돈이 아니므로 잃어도
        아무 손해가 없습니다. 자금을 다 소진했거나 처음부터 다시 해보고 싶다면 마이페이지에서{" "}
        <b>포트폴리오 초기화</b>를 누르면 5,000만원으로 되돌아갑니다.
      </>
    ),
  },
  {
    title: "2. 종목 고르기",
    body: (
      <>
        주식 종목 랭킹에서 <b>거래대금 상위 100개</b> 종목을 국내·해외로 나눠 보여드립니다. 거래대금은
        그 종목에 실제로 오간 돈의 규모로, 시장의 관심이 어디에 쏠려 있는지 보여주는 지표입니다.
        종목명이나 티커로 직접 검색할 수도 있습니다.
      </>
    ),
  },
  {
    title: "3. 매수하기",
    body: (
      <>
        종목 상세 페이지에서 수량을 입력하고 매수 버튼을 누르면 <b>현재가로 즉시 체결</b>됩니다. 이때
        예수금에서 주문 금액과 수수료가 함께 빠져나가고, 보유 종목에 그 수량이 더해집니다. 주문 버튼을
        누르기 전에 총 차감액을 미리 확인하실 수 있습니다.
      </>
    ),
  },
  {
    title: "4. 매도하기",
    body: (
      <>
        보유한 종목을 팔면 매도 금액에서 <b>수수료와 증권거래세</b>가 빠진 금액이 예수금으로 들어옵니다.
        여기서 많은 초보자가 놀라는 지점이 있습니다 — <b>산 가격 그대로 팔면 본전이 아니라 손해</b>
        입니다. 사고팔 때마다 비용이 발생하기 때문입니다.
      </>
    ),
  },
  {
    title: "5. 거래 가능 시간",
    body: (
      <>
        실제 주식시장과 동일하게 운영됩니다. 국내 주식은 <b>평일 09:00~15:30</b>, 미국 주식은{" "}
        <b>한국 시간 기준 밤~새벽</b>에만 거래할 수 있습니다. 주말과 공휴일에는 거래가 불가능합니다.
        <br />
        다만 <b>시세 조회와 차트는 언제든 볼 수 있습니다.</b>
      </>
    ),
  },
  {
    title: "6. 거래할 수 없는 경우",
    body: (
      <>
        거래정지·정리매매 종목이거나, 주문가능금액이 부족하거나, 보유 수량보다 많이 팔려고 하면 주문이
        거절됩니다. 실제 시장의 규칙을 그대로 적용하고 있으며, 거절될 때는 이유를 함께 안내해 드립니다.
      </>
    ),
  },
];

export default function GuidePage() {
  const [tab, setTab] = useState<"guide" | "wiki">("guide");

  return (
    <div className="p-6">
      <div className="mb-3.5 flex gap-0.5 border-b border-gray-200">
        <button
          onClick={() => setTab("guide")}
          className={`border-b-2 px-3.5 py-1.5 text-[13px] ${
            tab === "guide" ? "border-gray-900 font-semibold text-gray-900" : "border-transparent text-gray-400"
          }`}
        >
          이용가이드
        </button>
        <button
          onClick={() => setTab("wiki")}
          className={`flex items-center gap-1.5 border-b-2 px-3.5 py-1.5 text-[13px] ${
            tab === "wiki" ? "border-gray-900 font-semibold text-gray-900" : "border-transparent text-gray-400"
          }`}
        >
          금융 용어 위키
          <span className="rounded border border-gray-300 px-1 py-0.5 text-[10px] text-gray-400">부가기능</span>
        </button>
      </div>

      {tab === "wiki" ? (
        <div className="flex h-64 flex-col items-center justify-center gap-1.5 rounded-lg border border-dashed border-gray-300 text-[13px] text-gray-400">
          <b className="text-gray-600">금융 용어 위키는 2주차 이후 제공 예정입니다</b>
          <span>부가 기능 — LLM 기반 용어 설명을 준비 중입니다</span>
        </div>
      ) : (
        <>
          <h2 className="text-[19px] font-bold text-gray-900">이용가이드</h2>
          <p className="mb-4.5 text-[13px] text-gray-500">이 서비스에서 거래가 어떻게 이루어지는지 안내합니다</p>

          <div className="flex gap-4">
            <div className="flex-1 space-y-3.5">
              {SECTIONS.slice(0, 3).map((s) => (
                <div key={s.title} className="rounded-lg border border-gray-200 p-4">
                  <h4 className="mb-1.5 text-[14px] font-semibold text-gray-900">{s.title}</h4>
                  <p className="text-[13px] leading-relaxed text-gray-600">{s.body}</p>
                </div>
              ))}
            </div>
            <div className="flex-1 space-y-3.5">
              {SECTIONS.slice(3).map((s) => (
                <div key={s.title} className="rounded-lg border border-gray-200 p-4">
                  <h4 className="mb-1.5 text-[14px] font-semibold text-gray-900">{s.title}</h4>
                  <p className="text-[13px] leading-relaxed text-gray-600">{s.body}</p>
                </div>
              ))}
            </div>
          </div>

          <div className="mt-4.5 rounded-lg bg-gray-100 px-5 py-4">
            <p className="text-[12.5px] leading-relaxed text-gray-600">
              <b className="text-gray-900">참고</b> — 이 서비스의 시세는 실제 시장 데이터를 사용하지만 수 초의
              지연이 있으며, 회원의 매수·매도는 실제 시장 가격에 영향을 주지 않습니다. 모의 투자 결과가 실제
              투자 성과를 보장하지 않습니다.
            </p>
          </div>
        </>
      )}
    </div>
  );
}
