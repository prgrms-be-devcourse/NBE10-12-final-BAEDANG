"use client";

import { useState } from "react";

/**
 * 회원가입 화면의 "이용약관과 개인정보 처리방침에 동의해요" 체크박스를 누르면 뜨는
 * 팝업(이슈: 체크박스만으로 바로 동의 처리되던 걸, 실제 약관/방침을 읽고 팝업 하단의
 * 별도 동의 체크박스를 눌러야 동의가 되도록 바꿔달라는 요청).
 *
 * <p>바깥 체크박스는 이 컴포넌트가 열려 있는 동안 스스로 상태를 바꾸지 않는다 —
 * `agreed`를 실제로 켜고 끄는 건 이 팝업 하단의 체크박스뿐이다(signup/page.tsx 참고).
 * 팝업을 열 때 지금까지의 동의 여부(`initialAgreed`)를 그대로 반영해서, 이미
 * 동의한 사용자가 다시 열어봐도 체크가 풀려 있지 않게 한다.
 */
export function TermsModal({
  initialAgreed,
  onConfirm,
  onClose,
}: {
  initialAgreed: boolean;
  onConfirm: (agreed: boolean) => void;
  onClose: () => void;
}) {
  const [localAgreed, setLocalAgreed] = useState(initialAgreed);

  function handleAgreeChange(checked: boolean) {
    setLocalAgreed(checked);
    onConfirm(checked);
    onClose();
  }

  return (
    <div
      className="fixed inset-0 z-[150] flex items-center justify-center px-4"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
      onClick={onClose}
    >
      <div
        className="flex w-full max-w-[560px] flex-col rounded-[24px] p-6.5"
        style={{ background: "var(--card)", maxHeight: "85vh", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-start justify-between">
          <h3 className="text-[18px] font-bold" style={{ color: "var(--ink)" }}>
            이용약관 및 개인정보 처리방침
          </h3>
          <button
            onClick={onClose}
            className="cursor-pointer rounded-full px-3 py-1.5 text-[13px] font-semibold"
            style={{ color: "var(--mut)" }}
            aria-label="닫기"
          >
            닫기
          </button>
        </div>

        <div
          className="min-h-0 flex-1 overflow-y-auto rounded-xl p-4 text-[12.5px] leading-[1.7]"
          style={{ background: "var(--fill)", color: "var(--body)" }}
        >
          <section className="mb-5">
            <h4 className="mb-2 text-[14px] font-bold" style={{ color: "var(--ink)" }}>
              이용약관
            </h4>
            <ol className="list-decimal space-y-2 pl-4.5">
              <li>
                본 서비스(이하 &ldquo;서비스&rdquo;)는 초보 투자자의 학습을 돕기 위한{" "}
                <b style={{ color: "var(--ink)" }}>모의 투자 서비스</b>이며, 서비스 내 모든 거래는 가상의
                투자금으로 이루어지는 시뮬레이션입니다. 실제 증권사에 주문이 전달되거나 실제 금전이
                오가는 매매는 이루어지지 않습니다.
              </li>
              <li>
                서비스가 제공하는 시세·차트·랭킹 등 시장 데이터는 외부 시세 제공사의 API를 통해 받아온
                참고용 정보이며, 지연되거나 실제 시장과 다를 수 있습니다. 이 데이터를 근거로 한 실제
                투자 판단에 대해 서비스는 책임을 지지 않습니다.
              </li>
              <li>
                가상의 투자금과 보유 종목 등 계정 내 자산 정보는 학습 목적의 시뮬레이션 데이터이며,
                실제 자산으로서의 가치나 환금성을 갖지 않습니다.
              </li>
              <li>
                이용자는 회원가입 시 입력한 정보(이메일, 닉네임, 비밀번호)가 정확해야 하며, 계정 정보
                관리에 대한 책임은 이용자 본인에게 있습니다.
              </li>
              <li>
                서비스는 사전 공지 후 기능 변경, 점검, 시즌(라운드) 초기화 등을 진행할 수 있으며, 이
                과정에서 시뮬레이션 자산·거래 내역이 초기화될 수 있습니다.
              </li>
              <li>부정한 방법으로 서비스를 이용하거나 다른 이용자에게 피해를 주는 행위는 제한될 수 있습니다.</li>
            </ol>
          </section>

          <section>
            <h4 className="mb-2 text-[14px] font-bold" style={{ color: "var(--ink)" }}>
              개인정보 처리방침
            </h4>
            <ol className="list-decimal space-y-2 pl-4.5">
              <li>
                서비스는 회원가입·로그인 등 계정 기능 제공을 위해 이메일, 닉네임, 비밀번호(암호화 저장)를
                수집합니다. 그 외의 민감 정보는 수집하지 않습니다.
              </li>
              <li>
                수집한 정보는 계정 식별, 로그인 유지, 서비스 이용 통계 등 서비스 운영 목적으로만
                사용하며, 이용자의 동의 없이 제3자에게 제공하지 않습니다.
              </li>
              <li>
                이용자는 언제든 마이페이지에서 본인 정보를 확인할 수 있고, 회원 탈퇴를 통해 개인정보
                삭제를 요청할 수 있습니다. 관련 법령에 따라 보관이 필요한 정보는 해당 기간 동안만 별도
                보관 후 파기합니다.
              </li>
              <li>
                로그인 상태 유지를 위해 쿠키 등 최소한의 기술적 수단을 사용할 수 있으며, 이용자는 브라우저
                설정을 통해 이를 거부할 수 있습니다(단, 이 경우 로그인 유지가 제한될 수 있습니다).
              </li>
              <li>본 방침은 서비스 개선에 따라 변경될 수 있으며, 중요한 변경 사항은 서비스 내 공지를 통해 안내합니다.</li>
            </ol>
          </section>
        </div>

        <label
          className="mt-4 flex cursor-pointer items-start gap-2.5 rounded-xl p-3.5 text-[13px] leading-[1.5]"
          style={{ background: "var(--accentSoft)", color: "var(--onAccentSoftText)" }}
        >
          <input
            type="checkbox"
            checked={localAgreed}
            onChange={(e) => handleAgreeChange(e.target.checked)}
            className="mt-0.5 h-4 w-4 cursor-pointer"
            style={{ accentColor: "var(--accent)" }}
          />
          <span className="font-semibold">위 이용약관과 개인정보 처리방침을 모두 확인했으며 동의합니다.</span>
        </label>
      </div>
    </div>
  );
}
