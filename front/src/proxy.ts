import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * 홈페이지("/") 첫 방문자를 서비스 소개 화면("/intro")으로 보낸다.
 * `IntroScreen`이 남기는 쿠키가 있으면(=한 번이라도 봤으면) 그대로 통과시킨다.
 *
 * Next.js 16부터 `middleware.ts`가 `proxy.ts`로 이름이 바뀌었다(기능은 동일) —
 * 이 프로젝트는 이 버전을 쓰므로 옛 이름(`middleware.ts`)으로 만들면 동작하지 않는다.
 */
export function proxy(request: NextRequest) {
  if (request.cookies.has("iv_intro_seen")) {
    return NextResponse.next();
  }
  return NextResponse.redirect(new URL("/intro", request.url));
}

export const config = {
  matcher: "/",
};
