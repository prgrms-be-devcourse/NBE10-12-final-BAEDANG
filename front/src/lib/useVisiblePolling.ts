"use client";

import { useEffect, useRef } from "react";

/**
 * 문서가 보일 때만 `callback`을 `intervalMs` 주기로 반복 실행한다.
 *
 * <p>탭이 백그라운드로 가면(`document.hidden`) 폴링을 멈춰서 불필요한 요청을
 * 줄이고, 다시 보이면 그 주기 그대로 재개한다. 랭킹 목록·종목 상세 시세처럼
 * "정기적으로 다시 조회해서 최신 상태를 반영"해야 하는 화면에서 공용으로 쓴다
 * (환율은 `ExchangeRateProvider`가 별도로 1시간 주기 폴링을 이미 하고 있어
 * 이 훅을 쓰지 않는다).
 *
 * <p>최초 마운트 시 즉시 한 번 호출하지 않는다 — 호출부가 보통 이미 초기
 * 데이터를 별도로 불러온 뒤라서, 이 훅에는 "그다음부터의 주기적 갱신"만
 * 맡기면 된다.
 */
export function useVisiblePolling(callback: () => void, intervalMs: number, enabled = true) {
  const callbackRef = useRef(callback);
  useEffect(() => {
    callbackRef.current = callback;
  }, [callback]);

  useEffect(() => {
    if (!enabled || typeof document === "undefined") return;

    let intervalId: number | null = null;

    function start() {
      if (intervalId !== null) return;
      intervalId = window.setInterval(() => callbackRef.current(), intervalMs);
    }
    function stop() {
      if (intervalId === null) return;
      window.clearInterval(intervalId);
      intervalId = null;
    }
    function handleVisibility() {
      if (document.hidden) stop();
      else start();
    }

    if (!document.hidden) start();
    document.addEventListener("visibilitychange", handleVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", handleVisibility);
    };
  }, [intervalMs, enabled]);
}
