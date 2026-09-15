import { NextRequest, NextResponse } from 'next/server';

const COOKIE = 'baedang_refresh';
const ACTIONS = new Set(['signup', 'login', 'refresh', 'logout']);

function error(code: string, status: number) {
  return NextResponse.json({ code, message: '인증 요청을 처리하지 못했어요.' },
    { status, headers: { 'Cache-Control': 'no-store' } });
}

export async function POST(request: NextRequest, context: { params: Promise<{ action: string }> }) {
  const { action } = await context.params;
  if (!ACTIONS.has(action)) return error('NOT_FOUND', 404);
  const upstream = process.env.AUTH_BACKEND_URL;
  const publicOrigin = process.env.AUTH_PUBLIC_ORIGIN;
  if (!upstream || !publicOrigin) return error('AUTH_UNAVAILABLE', 503);
  let backend: URL;
  let origin: URL;
  try { backend = new URL(upstream); origin = new URL(publicOrigin); }
  catch { return error('AUTH_UNAVAILABLE', 503); }
  const local = ['127.0.0.1', 'localhost'].includes(backend.hostname)
    && ['127.0.0.1', 'localhost'].includes(origin.hostname) && !process.env.VERCEL;
  if (backend.username || backend.password || backend.search || backend.hash
    || backend.pathname !== '/' || origin.username || origin.password || origin.search || origin.hash
    || origin.pathname !== '/' || (backend.protocol !== 'https:' && !(local && backend.protocol === 'http:'))
    || (origin.protocol !== 'https:' && !(local && origin.protocol === 'http:'))) return error('AUTH_UNAVAILABLE', 503);
  // 쿠키를 사용하는 모든 변경 요청은 고정된 프론트 Origin과 JSON 전용 헤더를 확인합니다.
  if (request.headers.get('origin') !== origin.origin || request.headers.get('x-auth-request') !== '1'
    || request.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'application/json') return error('FORBIDDEN', 403);
  let body: unknown;
  if (action === 'refresh' || action === 'logout') {
    const refreshToken = request.cookies.get(COOKIE)?.value;
    if (!refreshToken) {
      return action === 'logout' ? new NextResponse(null, { status: 204, headers: { 'Cache-Control': 'no-store' } })
        : error('UNAUTHORIZED', 401);
    }
    body = { refreshToken };
  } else {
    try { body = await request.json(); }
    catch { return error('INVALID_INPUT', 400); }
  }
  try {
    const response = await fetch(new URL(`/api/auth/${action}`, backend), {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(10000),
    });
    const payload = await response.json().catch(() => null);
    const cookieOptions = { httpOnly: true, secure: !local, sameSite: 'lax' as const, path: '/api/auth' };
    if (action === 'logout' && (response.ok || response.status === 401)) {
      const result = new NextResponse(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
      result.cookies.set(COOKIE, '', { ...cookieOptions, maxAge: 0 });
      return result;
    }
    if (!response.ok) {
      // 401에서도 쿠키를 덮어쓰지 않습니다. 늦은 오류 응답이 새 로그인의 쿠키를 지우면 안 됩니다.
      return NextResponse.json({ code: payload?.code ?? 'AUTH_UNAVAILABLE',
        message: payload?.message ?? '인증 요청을 처리하지 못했어요.', ...(payload?.data ? { data: payload.data } : {}) },
      { status: response.status, headers: { 'Cache-Control': 'no-store' } });
    }
    if (typeof payload?.accessToken !== 'string' || typeof payload?.refreshToken !== 'string'
      || !Number.isFinite(Date.parse(payload.expiresAt))) return error('AUTH_UNAVAILABLE', 502);
    const { refreshToken, ...publicPayload } = payload;
    const result = NextResponse.json(publicPayload, { status: response.status, headers: { 'Cache-Control': 'no-store' } });
    result.cookies.set(COOKIE, refreshToken, { ...cookieOptions, expires: new Date(payload.expiresAt) });
    return result;
  } catch { return error('AUTH_UNAVAILABLE', 503); }
}
