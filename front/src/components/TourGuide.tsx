"use client";

import { useEffect, useRef, useState } from "react";

export type TourStep = {
  /** 안내할 요소를 찾는 CSS 선택자. `data-tour="..."` 속성을 붙인 요소를 가리킨다. */
  target: string;
  title: string;
  description: string;
};

/**
 * 화면 위에 스포트라이트(어둡게 처리 + 구멍)를 띄우고, 단계별로 요소를 짚어가며
 * 설명하는 범용 온보딩 투어 엔진.
 *
 * <p>스포트라이트 자체는 `pointer-events: none`이라 실제 버튼은 그대로 클릭할 수
 * 있다 — 그 클릭을 document 캡처 단계에서 감지해서 다음 단계로 자동으로 넘어간다
 * ("버튼을 누르면 다음 안내가 나타난다"는 체험형 흐름). 툴팁의 "다음"/"이전"
 * 버튼으로도 동일하게 이동할 수 있어, 클릭으로 진행하기 애매한 설명 전용 단계도
 * 자연스럽게 넘어간다.
 *
 * <p>모달(회원가입, 차트 확대보기)보다 항상 아래에 깔리도록 z-index를 낮게
 * 잡는다 — 투어 도중 모달이 뜨면 모달이 정상적으로 위를 덮어야 하기 때문이다.
 */
export function TourGuide({
  steps,
  storageKey,
  active,
  onFinish,
}: {
  steps: TourStep[];
  storageKey: string;
  active: boolean;
  onFinish: () => void;
}) {
  const [stepIndex, setStepIndex] = useState(0);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const scrolledStepRef = useRef(-1);

  useEffect(() => {
    if (active) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setStepIndex(0);
      scrolledStepRef.current = -1;
    }
  }, [active]);

  // 대상 요소의 위치를 계속 추적한다 — 창 크기 변경·스크롤은 물론, 캔들 차트
  // 로딩 완료처럼 다른 원인으로 생기는 레이아웃 변화도 짧은 간격의 폴링으로 잡는다.
  useEffect(() => {
    if (!active) return;
    const step = steps[stepIndex];
    if (!step) return;

    function update() {
      const el = document.querySelector(step.target);
      if (!el) {
        setRect(null);
        return;
      }
      setRect(el.getBoundingClientRect());
      if (scrolledStepRef.current !== stepIndex) {
        scrolledStepRef.current = stepIndex;
        el.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    }

    update();
    const id = window.setInterval(update, 300);
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    return () => {
      window.clearInterval(id);
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
    };
  }, [active, stepIndex, steps]);

  // 스포트라이트가 뚫어놓은 실제 버튼을 사용자가 진짜로 클릭하면 다음 단계로 넘어간다.
  useEffect(() => {
    if (!active) return;
    const step = steps[stepIndex];
    if (!step) return;

    function handleClick(e: MouseEvent) {
      const el = document.querySelector(step!.target);
      if (el && e.target instanceof Node && el.contains(e.target)) {
        goNext();
      }
    }

    document.addEventListener("click", handleClick, true);
    return () => document.removeEventListener("click", handleClick, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, stepIndex, steps]);

  function finish() {
    try {
      localStorage.setItem(storageKey, "1");
    } catch {
      // localStorage를 못 쓰는 환경(프라이빗 모드 등)이어도 투어 자체는 정상 종료돼야 한다.
    }
    onFinish();
  }

  function goNext() {
    setStepIndex((i) => {
      if (i >= steps.length - 1) {
        finish();
        return i;
      }
      return i + 1;
    });
  }

  function goPrev() {
    setStepIndex((i) => Math.max(0, i - 1));
  }

  if (!active || !rect || steps.length === 0) return null;

  const step = steps[stepIndex];
  const PAD = 8;
  const TOOLTIP_W = 320;
  const viewportW = window.innerWidth;
  const viewportH = window.innerHeight;
  const showBelow = rect.bottom + 190 < viewportH;

  return (
    <>
      <div
        aria-hidden
        style={{
          position: "fixed",
          top: Math.max(rect.top - PAD, 0),
          left: Math.max(rect.left - PAD, 0),
          width: rect.width + PAD * 2,
          height: rect.height + PAD * 2,
          borderRadius: 14,
          boxShadow: "0 0 0 9999px rgba(4,10,20,.6)",
          pointerEvents: "none",
          zIndex: 140,
          transition: "top .25s ease, left .25s ease, width .25s ease, height .25s ease",
        }}
      />
      <div
        role="dialog"
        aria-label={step.title}
        className="rounded-[18px] p-4.5"
        style={{
          position: "fixed",
          top: showBelow ? rect.bottom + PAD + 12 : undefined,
          bottom: showBelow ? undefined : Math.max(viewportH - rect.top + PAD + 12, 12),
          left: Math.min(Math.max(rect.left, 16), Math.max(viewportW - TOOLTIP_W - 16, 16)),
          width: TOOLTIP_W,
          maxWidth: `calc(100vw - 32px)`,
          zIndex: 141,
          background: "var(--card)",
          boxShadow: "0 12px 40px rgba(0,0,0,.35)",
        }}
      >
        <div className="mb-1 text-[12px] font-bold" style={{ color: "var(--mut2)" }}>
          {stepIndex + 1} / {steps.length}
        </div>
        <div className="mb-1.5 text-[15px] font-bold" style={{ color: "var(--ink)" }}>
          {step.title}
        </div>
        <p className="mb-4 text-[13.5px] leading-relaxed" style={{ color: "var(--body)" }}>
          {step.description}
        </p>
        <div className="flex items-center justify-between">
          <button type="button" onClick={finish} className="tour-skip-btn cursor-pointer text-[12.5px] font-semibold">
            건너뛰기
          </button>
          <div className="flex gap-1.5">
            {stepIndex > 0 && (
              <button
                type="button"
                onClick={goPrev}
                className="tour-prev-btn cursor-pointer rounded-full px-3.5 py-1.5 text-[13px] font-bold transition-colors duration-150"
              >
                이전
              </button>
            )}
            <button
              type="button"
              onClick={goNext}
              className="cursor-pointer rounded-full px-4 py-1.5 text-[13px] font-bold text-white transition-[background] duration-150"
              style={{ background: "var(--accent)" }}
              onMouseEnter={(e) => (e.currentTarget.style.background = "var(--buyHover)")}
              onMouseLeave={(e) => (e.currentTarget.style.background = "var(--accent)")}
            >
              {stepIndex === steps.length - 1 ? "완료" : "다음"}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
