"use client";

import { useEffect, useLayoutEffect, useRef, useState } from "react";

export type PillOption = {
  value: string;
  label: React.ReactNode;
};

type Props = {
  options: PillOption[];
  value: string;
  onChange: (value: string) => void;
  /** 트랙(배경) 클래스 — 배경색·패딩·라운드·폭은 화면마다 다르므로 호출부에서 지정한다. */
  trackClassName?: string;
  /** 라이트/다크에 따라 값이 달라지는 트랙 배경·테두리 등, className으로 못 넣는 인라인 스타일. */
  trackStyle?: React.CSSProperties;
  /** 슬라이딩 필박스 자체의 배경색. 매수/매도처럼 탭에 따라 색이 바뀌면 함수로 넘긴다. */
  pillColor?: string | ((value: string) => string);
  /** 필박스 라운드. 기본은 완전한 필(999px), 헤더 네비만 8px 라운드 사각형을 쓴다. */
  pillRadius?: string;
  /** 탭 전환 시 재생할 스쿼시 keyframe. 헤더 네비만 가로로만 출렁이는 `liquid`를 쓰고,
   * 나머지(테마 토글·국내해외·매수매도 등)는 세로+가로로 눌리는 `squash`를 쓴다. */
  squashAnimation?: "squash" | "liquid";
  buttonClassName?: string;
  activeTextClassName?: string;
  inactiveTextClassName?: string;
  activeTextStyle?: React.CSSProperties;
  inactiveTextStyle?: React.CSSProperties;
};

/**
 * 토스 스타일 디자인의 "슬라이딩 필박스" 세그먼트 탭. 전 화면의 탭 전환(헤더 네비,
 * 테마 토글, 국내/해외, 일봉/1분봉, 기간, 매수/매도, 가이드/위키, 보유/체결)이
 * 전부 이 컴포넌트 하나로 구현된다.
 *
 * 필박스의 위치·너비는 "트랙을 버튼 개수로 등분한다"는 계산식이 아니라, 활성 버튼의
 * 실제 DOM 위치(offsetLeft/offsetWidth)를 측정해서 그대로 적용한다. 예전에는 퍼센트
 * 계산식(padding·gap을 반영한 등분)을 썼는데, 이는 "모든 버튼의 폭이 같다"는 가정
 * 위에서만 맞는다 — 헤더 네비처럼 트랙 폭이 고정이고 버튼이 flex-1로 균등 분할되는
 * 경우엔 문제없지만, 종목 상세의 일봉/1분봉·기간(1개월/6개월/1년) 탭처럼 트랙이
 * `w-fit`이고 버튼마다 글자 길이가 달라 폭이 제각각인 경우엔 등분 계산과 실제 버튼
 * 폭이 어긋나 필박스 안에서 문구가 한쪽으로 쏠려 보이는 원인이 됐다. DOM을 직접
 * 측정하면 버튼 폭이 균등하든 제각각이든 항상 정확히 겹친다.
 */
export function PillTabs({
  options,
  value,
  onChange,
  trackClassName = "",
  trackStyle,
  pillColor = "var(--accent)",
  pillRadius = "999px",
  buttonClassName = "",
  activeTextClassName = "text-white",
  inactiveTextClassName = "",
  activeTextStyle,
  inactiveTextStyle = { color: "var(--mut)" },
  squashAnimation = "squash",
}: Props) {
  const activeIndex = Math.max(0, options.findIndex((o) => o.value === value));
  const [squashing, setSquashing] = useState(false);
  const squashTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const buttonRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const [pillRect, setPillRect] = useState<{ left: number; width: number } | null>(null);

  function measure() {
    const btn = buttonRefs.current[activeIndex];
    if (btn) setPillRect({ left: btn.offsetLeft, width: btn.offsetWidth });
  }

  // 값이 바뀌거나(activeIndex) 옵션 개수가 바뀌면 다시 측정한다. useLayoutEffect라
  // 브라우저가 화면을 그리기 전에 동기적으로 값을 세팅해 필박스가 잘못된 위치에
  // 잠깐 보이는 깜빡임이 없다.
  useLayoutEffect(measure, [activeIndex, options.length]);

  // 트랙 크기 자체가 바뀌는 경우(창 크기 변경, 반응형 줄바꿈, 폰트 로딩 등)에도
  // 다시 측정해서 필박스가 계속 버튼과 겹치게 한다.
  useEffect(() => {
    const track = trackRef.current;
    if (!track || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(track);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeIndex]);

  // 스쿼시는 "값이 바뀌었을 때" 반응하는 effect가 아니라, 실제 클릭 핸들러 안에서만
  // 직접 트리거한다. 이전에는 useEffect(() => ..., [value])에 useRef 플래그로
  // "처음 한 번은 건너뛴다"는 식으로 마운트 시 재생을 막았는데, React Strict Mode가
  // 개발 모드에서 effect를 일부러 두 번 실행하면서 그 플래그가 이미 소진돼 있어
  // 두 번째 실행에서 스쿼시가 재생돼버렸다(화면 진입 직후 필박스가 씰룩거리는
  // 원인). 클릭 이벤트는 Strict Mode가 두 번 실행하지 않으므로 이 방식이 안전하다.
  function handleSelect(next: string) {
    if (next !== value) {
      setSquashing(true);
      if (squashTimer.current) clearTimeout(squashTimer.current);
      squashTimer.current = setTimeout(() => setSquashing(false), 350);
    }
    onChange(next);
  }

  const resolvedPillColor = typeof pillColor === "function" ? pillColor(value) : pillColor;

  return (
    <div ref={trackRef} className={`relative flex overflow-hidden ${trackClassName}`} style={trackStyle}>
      <div
        className="absolute top-[3px] bottom-[3px]"
        style={{
          left: pillRect ? `${pillRect.left}px` : 0,
          width: pillRect ? `${pillRect.width}px` : 0,
          opacity: pillRect ? 1 : 0,
          borderRadius: pillRadius,
          background: resolvedPillColor,
          transition: "left .35s cubic-bezier(.4,0,.2,1), background .25s",
          animation: squashing
            ? `${squashAnimation === "liquid" ? "navThumbLiquid" : "modeThumbSquash"} .35s cubic-bezier(.4,0,.2,1)`
            : undefined,
        }}
      />
      {options.map((opt, i) => {
        const active = opt.value === value;
        return (
          <button
            key={opt.value}
            ref={(el) => {
              buttonRefs.current[i] = el;
            }}
            type="button"
            onClick={() => handleSelect(opt.value)}
            className={`relative z-[1] flex-1 cursor-pointer text-center whitespace-nowrap ${
              active ? activeTextClassName : inactiveTextClassName
            } ${buttonClassName}`}
            style={active ? activeTextStyle : inactiveTextStyle}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}
