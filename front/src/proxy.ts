import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * 홈페이지("/")로 들어오는 모든 방문을 서비스 소개 화면("/intro")으로 보낸다.
 *
 * <p>예전엔 "이 브라우저에서 한 번이라도 봤는지"를 쿠키(iv_intro_seen)로 가려서
 * 첫 방문자에게만 보여줬는데, 방문할 때마다(재방문 포함) 무조건 소개 화면부터
 * 보여달라는 요청으로 그 조건을 없앴다 — 실제 메인 화면은 "/main"으로 옮겨졌고,
 * 소개 화면의 시작하기 버튼과 SKIP 버튼이 거기로 보낸다(IntroScreen 참고).
 *
 * Next.js 16부터 `middleware.ts`가 `proxy.ts`로 이름이 바뀌었다(기능은 동일) —
 * 이 프로젝트는 이 버전을 쓰므로 옛 이름(`middleware.ts`)으로 만들면 동작하지 않는다.
 */
export function proxy(request: NextRequest) {
  return NextResponse.redirect(new URL("/intro", request.url));
}

export const config = {
  matcher: "/",
};
