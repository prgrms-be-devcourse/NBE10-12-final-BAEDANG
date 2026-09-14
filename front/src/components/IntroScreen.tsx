"use client";

import { useEffect } from "react";
import { InvestupIntro } from "./InvestupIntro";

/** `proxy.ts`가 같은 이름의 쿠키로 "이미 봤는지"를 판단한다. */
export const INTRO_SEEN_COOKIE = "iv_intro_seen";

/**
 * `/intro` 페이지 본문. 화면에 도착하는 즉시(끝까지 보지 않고 나가도) "봤음" 쿠키를
 * 남겨서, 다음부터는 `proxy.ts`가 더 이상 "/"에서 이 화면으로 돌려보내지 않는다.
 * 시작하기 버튼은 실제 메인 화면("/")으로 이동한다.
 */
export function IntroScreen() {
  useEffect(() => {
    // 1년 — 사실상 "이 브라우저에서는 한 번만" 보여주기 위한 값이다.
    document.cookie = `${INTRO_SEEN_COOKIE}=1; path=/; max-age=${60 * 60 * 24 * 365}`;
  }, []);

  return <InvestupIntro ctaHref="/" />;
}
