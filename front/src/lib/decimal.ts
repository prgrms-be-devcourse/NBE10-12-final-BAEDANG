import Decimal from "decimal.js";

/**
 * 이 프로젝트의 반올림 정책(HALF_UP)을 고정한 Decimal 클래스입니다.
 * 금액·수량 계산은 전부 이 모듈을 통해서만 하세요.
 *
 * <p>자바스크립트 `number`는 IEEE 754 배정밀도 부동소수점(2진수)이라, 0.1·0.2처럼
 * 흔한 10진 소수도 정확히 표현하지 못해 계산할 때마다 오차가 쌓입니다
 * (다훈님 리뷰, PR #17). `decimal.js`는 10진 자릿수를 그대로 저장해서 이 문제가
 * 애초에 생기지 않습니다.
 *
 * <p>백엔드가 `AGENTS.md` 규칙대로 금액을 `BigDecimal`로 다루는 것과 같은 원칙을
 * 프론트에도 적용한 것입니다. `docs/erd.md`의 "미국 금액은 센트 단위 반올림 →
 * 원화 환산 → 최종 원 단위 HALF_UP 반올림" 규칙도 이 설정(`ROUND_HALF_UP`)과
 * 그대로 맞아떨어집니다.
 */
export const D = Decimal.clone({ rounding: Decimal.ROUND_HALF_UP });
