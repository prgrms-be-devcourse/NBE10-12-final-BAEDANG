/**
 * 주문 실패 후 `clientOrderId`를 어떻게 다룰지 결정한다.
 *
 * <p>백엔드의 `ClientOrderRetryPolicy`
 * (`back/src/main/java/com/baedang/trading/model/ClientOrderRetryPolicy.java`)와
 * 정확히 같은 계약을 프론트에서 그대로 따른다.
 *
 * <table>
 *   <tr><th>정책</th><th>의미</th><th>프론트 동작</th></tr>
 *   <tr><td>{@code SAME_CLIENT_ORDER_ID}</td>
 *       <td>DB에 주문 행이 아직 생성되지 않고 중단됨 (사전 검증 실패, 외부 조회 일시
 *           실패, 15초 만료 등)</td>
 *       <td>같은 clientOrderId 그대로 재시도</td></tr>
 *   <tr><td>{@code NEW_CLIENT_ORDER_ID}</td>
 *       <td>트랜잭션 내에서 확정되어 DB에 REJECTED 행이 영구 커밋됨 (잔액·수량 부족,
 *           시세 만료, 장 마감 확정 등)</td>
 *       <td>새 clientOrderId를 발급해서 재시도 — 같은 ID로 재시도하면 이미 커밋된
 *           REJECTED 결과만 반복해서 돌려받는다(멱등성)</td></tr>
 *   <tr><td>{@code NOT_RETRYABLE}</td>
 *       <td>같은 clientOrderId를 다른 내용의 주문으로 재사용하는 등 논리적 모순
 *           (예: 이미 존재하는 ID인데 종목/수량/방향이 다름)</td>
 *       <td>이 요청은 재전송하면 안 됨 — clientOrderId를 버리고, 사용자가 주문 내용을
 *           확인한 뒤 완전히 새로운 시도로 다시 시작해야 한다</td></tr>
 * </table>
 */
export type ClientOrderRetryPolicy = "SAME_CLIENT_ORDER_ID" | "NEW_CLIENT_ORDER_ID" | "NOT_RETRYABLE";

/**
 * 새 `clientOrderId`를 발급한다.
 *
 * <p>`crypto.randomUUID()`는 Secure Context(HTTPS 또는 `localhost`)에서만 지원되는
 * 브라우저 API다. 사설 IP로 접속하는 로컬 테스트 환경이나 구형 웹뷰처럼 Secure
 * Context가 아닌 곳에서는 `crypto.randomUUID is not a function`으로 죽을 수 있어,
 * 그런 환경에서도 안전하게 동작하도록 폴백을 둔다. 이 ID는 암호학적 강도가 필요한
 * 값이 아니라 주문 멱등성 키일 뿐이므로 `Math.random()` 기반 폴백으로 충분하다.
 */
export function generateClientOrderId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * 다음 시도에 쓸 `clientOrderId`를 계산한다.
 *
 * @param policy 실패 응답의 `data.retryPolicy`. 없으면(네트워크 오류처럼 서버에 닿기도
 *               전에 실패한 경우) 안전한 기본값으로 `SAME_CLIENT_ORDER_ID`처럼 취급한다 —
 *               요청 자체가 서버에 도달하지 않았으니 같은 ID로 다시 보내도 안전하다.
 * @param currentClientOrderId 방금 실패한 시도에서 쓴 clientOrderId
 * @returns 다음 시도에 쓸 clientOrderId. `null`이면 이 ID로는 재시도하면 안 된다는 뜻 —
 *          호출한 쪽에서 상태를 초기화하고, 다음 제출 시 완전히 새 ID를 발급해야 한다.
 */
export function nextClientOrderId(
  policy: ClientOrderRetryPolicy | undefined,
  currentClientOrderId: string
): string | null {
  switch (policy) {
    case "NEW_CLIENT_ORDER_ID":
      return generateClientOrderId();
    case "NOT_RETRYABLE":
      return null;
    case "SAME_CLIENT_ORDER_ID":
    case undefined:
    default:
      return currentClientOrderId;
  }
}
