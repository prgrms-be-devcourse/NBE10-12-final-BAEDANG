/**
 * 실제 확정된 비즈니스 상수 모음.
 *
 * <p>과거에는 백엔드 API가 준비되기 전 화면 인터랙션을 검증하기 위한 목데이터
 * (랭킹/종목상세/보유종목/체결내역 목업)도 이 파일에 함께 있었지만, 이제 해당 화면들은
 * 모두 실제 API(`@/lib/api`)로 연동됐다(이슈 #61). 아래 값들은 목데이터가 아니라
 * `docs/erd.md`/`AGENTS.md`에 명시된 확정 수수료·세율·초기 투자금이라 그대로 남겨둔다.
 */

/** 모의 투자금 초기 지급액(원) — 포트폴리오 초기화 시에도 동일하게 지급된다. */
export const INITIAL_CASH = 50_000_000;

export const FEE_RATE = 0.0001; // 0.01%, 매수·매도 공통
export const KR_TAX_RATE = 0.002; // 국내 매도세 0.2%
export const US_TAX_RATE = 0.0000206; // 미국 SEC Fee
export const US_TAX_MIN_USD = 0.01;
