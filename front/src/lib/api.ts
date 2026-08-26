/**
 * 백엔드 API 클라이언트. `back/src/main/java/com/baedang/auth/*` 에 이미 구현된
 * `POST /api/auth/signup`, `POST /api/auth/login`을 그대로 호출합니다.
 *
 * <p>1주차 백엔드는 토큰을 발급하지 않고 `userId`만 돌려줍니다 — 이후 요청은
 * `X-User-Id` 헤더로 사용자를 식별하는 개발용 방식입니다 (2주차에 JWT로 교체 예정).
 * `AuthProvider`가 이 `userId`를 들고 있다가 필요할 때 헤더에 실어 보냅니다.
 */

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type AuthUser = {
  userId: number;
  email: string;
  nickname: string;
};

/** 백엔드 ErrorResponse(code/message/data)를 그대로 감싼 에러. */
export class ApiError extends Error {
  code: string;
  fieldErrors?: Record<string, string>;

  constructor(code: string, message: string, fieldErrors?: Record<string, string>) {
    super(message);
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  } catch {
    // 백엔드가 안 떠 있거나 CORS 등으로 요청 자체가 안 나간 경우.
    throw new ApiError(
      "NETWORK_ERROR",
      "서버에 연결할 수 없어요. 백엔드가 실행 중인지 확인해주세요."
    );
  }

  const json = await res.json().catch(() => null);

  if (!res.ok) {
    const code = json?.code ?? "UNKNOWN_ERROR";
    const message = json?.message ?? "요청을 처리하지 못했어요.";
    // INVALID_INPUT일 때 data에 { 필드명: 에러메시지 } 형태로 옵니다 (GlobalExceptionHandler 참고).
    const fieldErrors = code === "INVALID_INPUT" && json?.data ? (json.data as Record<string, string>) : undefined;
    throw new ApiError(code, message, fieldErrors);
  }

  return json as T;
}

export function signUp(input: { email: string; password: string; nickname: string }): Promise<AuthUser> {
  return postJson<AuthUser>("/api/auth/signup", input);
}

export function login(input: { email: string; password: string }): Promise<AuthUser> {
  return postJson<AuthUser>("/api/auth/login", input);
}
