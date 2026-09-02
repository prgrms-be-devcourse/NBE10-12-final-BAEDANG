"use client";

import { useEffect, useRef, useState } from "react";

export type TourStep = {
  /** 안내할 요소를 찾는 CSS 선택자. `data-tour="..."` 속성을 붙인 요소를 가리킨다. */
  target: string;
  title: string;
  /** 줄바꿈이 필요하면 "\n"을 넣는다(`white-space: pre-line`으로 그대로 반영된다). */
  description: string;
  /** 설명 문구 정렬. 기본은 왼쪽 정렬이고, 필요한 단계에서만 가운데 정렬로 넘긴다. */
  descriptionAlign?: "left" | "center";
};

/**
 * 화면 위에 스포트라이트(어둡게 처리 + 구멍)를 띄우고, 단계별로 요소를 짚어가며
 * 설명하는 범용 온보딩 투어 엔진.
 *
 * <p>스포트라이트 자체는 `pointer-events: none`이라 실제 버튼은 그대로 클릭할 수
 * 있다 — 매수/매도 선택, 일봉/1분봉 전환처럼 안내 중인 요소를 실제로 눌러보며
 * 체험할 수 있다. 다만 다음 단계로의 이동은 그 클릭과 무관하게 오직 툴팁의
 * "다음" 버튼을 눌러야만 일어난다 — 사용자가 버튼을 눌러보며 결과를 충분히
 * 확인할 시간을 갖도록, 클릭했다고 안내가 곧장 다음으로 넘어가버리지 않는다.
 *
 * <p>모달(회원가입, 차트 확대보기)보다 항상 아래에 깔리도록 z-index를 낮게
 * 잡는다 — 투어 도중 모달이 뜨면 모달이 정상적으로 위를 덮어야 하기 때문이다.
 *
 * <p>키보드 접근성: Esc로 언제든 투어를 닫을 수 있고, Tab은 툴팁 안의
 * 버튼(건너뛰기/이전/다음)만 순환한다 — 마우스로는 스포트라이트 밖 실제 요소를
 * 그대로 누를 수 있지만, 키보드 포커스는 페이지로 새지 않고 툴팁 안에 갇힌다.
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
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (active) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setStepIndex(0);
      scrolledStepRef.current = -1;
    }
  }, [active]);

  // 대상 요소의 위치를 계속 추적한다 — 창 크기 변경·스크롤은 리스너로, 캔들
  // 차트 로딩 완료처럼 다른 원인으로 생기는 레이아웃 변화는 대상 요소 자체를
  // 관찰하는 ResizeObserver로 잡는다(짧은 간격의 폴링 대신 실제 레이아웃
  // 변화 이벤트에만 반응해 불필요한 getBoundingClientRect 호출을 줄인다).
  // document.body 전체가 아니라 이 단계의 대상 요소 하나만 관찰해서, 페이지
  // 어디가 바뀌든 매번 다시 계산하지 않고 이 요소 자신의 크기 변화에만 반응한다.
  useEffect(() => {
    if (!active) return;
    const step = steps[stepIndex];
    if (!step) return;

    const found = document.querySelector(step.target);
    if (!found) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setRect(null);
      return;
    }
    const el: Element = found;

    function update() {
      setRect(el.getBoundingClientRect());
    }

    update();
    if (scrolledStepRef.current !== stepIndex) {
      scrolledStepRef.current = stepIndex;
      el.scrollIntoView({ behavior: "smooth", block: "center" });
    }

    const resizeObserver = typeof ResizeObserver !== "undefined" ? new ResizeObserver(update) : null;
    resizeObserver?.observe(el);
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    return () => {
      resizeObserver?.disconnect();
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
    };
  }, [active, stepIndex, steps]);

  // Esc로 언제든 투어를 닫을 수 있게 하고, Tab 순환이 툴팁 밖으로 새지
  // 않도록 포커스를 가둔다(스포트라이트가 pointer-events: none이라 마우스로는
  // 실제 페이지 요소를 그대로 누를 수 있지만, 키보드 포커스까지 페이지로
  // 새어나가면 투어 중임을 알기 어렵다).
  useEffect(() => {
    if (!active) return;

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        finish();
        return;
      }
      if (e.key !== "Tab") return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const items = Array.from(dialog.querySelectorAll<HTMLButtonElement>("button"));
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  // 단계가 바뀔 때마다(또는 투어가 막 시작될 때) 툴팁의 주 버튼("다음"/"완료")으로
  // 포커스를 옮긴다 — rect 자체가 아니라 "값이 처음 생겼는지"만 의존성으로 둬서,
  // 스크롤·리사이즈로 rect가 계속 갱신되는 동안 포커스를 계속 빼앗지 않는다.
  const hasRect = rect !== null;
  useEffect(() => {
    if (!active || !hasRect) return;
    const dialog = dialogRef.current;
    if (!dialog) return;
    const buttons = Array.from(dialog.querySelectorAll<HTMLButtonElement>("button"));
    buttons[buttons.length - 1]?.focus();
  }, [active, stepIndex, hasRect]);

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
        ref={dialogRef}
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
        <p
          className="mb-4 text-[13.5px] leading-relaxed"
          style={{ color: "var(--body)", whiteSpace: "pre-line", textAlign: step.descriptionAlign ?? "left" }}
        >
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
