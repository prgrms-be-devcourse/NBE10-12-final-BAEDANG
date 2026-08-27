"use client";

import { useEffect, useRef, useState } from "react";

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
 * 전부 이 컴포넌트 하나로 구현된다 — design_handoff의 `segThumbN` 공식을 그대로 따른다:
 * `left: calc(index * (100/count)% + 3px)`, `width: calc((100/count)% - 4px)`.
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
  const count = options.length;
  const [squashing, setSquashing] = useState(false);
  const firstRender = useRef(true);

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    setSquashing(true);
    const t = setTimeout(() => setSquashing(false), 350);
    return () => clearTimeout(t);
  }, [value]);

  const resolvedPillColor = typeof pillColor === "function" ? pillColor(value) : pillColor;

  return (
    <div className={`relative flex ${trackClassName}`} style={trackStyle}>
      <div
        className="absolute top-[3px] bottom-[3px]"
        style={{
          left: `calc(${(activeIndex * 100) / count}% + 3px)`,
          width: `calc(${100 / count}% - 4px)`,
          borderRadius: pillRadius,
          background: resolvedPillColor,
          transition: "left .35s cubic-bezier(.4,0,.2,1), background .25s",
          animation: squashing
            ? `${squashAnimation === "liquid" ? "navThumbLiquid" : "modeThumbSquash"} .35s cubic-bezier(.4,0,.2,1)`
            : undefined,
        }}
      />
      {options.map((opt) => {
        const active = opt.value === value;
        return (
          <button
            key={opt.value}
            type="button"
            onClick={() => onChange(opt.value)}
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
