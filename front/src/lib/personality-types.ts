/**
 * 투자 성향 리포트 16유형의 별명·한줄 설명 — 첨부받은 디자인 시안("투자 성향 리포트
 * (standalone).html")의 TYPES 테이블을 그대로 옮겼다. 백엔드(`InvestmentType.code()`)는
 * "집중·국내·개별주·안정형" 같은 사실 그대로의 typeLabel만 내려주고, 이 재치있는
 * 별명·설명은 순수 프론트 표시용 콘텐츠라 여기서 typeCode로 찾아 붙인다.
 *
 * <p>코드 순서는 분산(C/D)·시장(K/G)·유형(S/E)·공격성(A/B) — 백엔드
 * `InvestmentType.code()`와 완전히 같다(`back/.../report/support/InvestmentType.java`).
 */
export const PERSONALITY_TYPES: Readonly<Record<string, { nickname: string; description: string }>> = {
  CKSA: { nickname: "코스피 저격수", description: "국내 개별주 한두 종목에 레버리지까지 얹어 한 방을 노리는 승부사" },
  CKSB: { nickname: "우량주 집사", description: "삼성전자 같은 국내 대형 우량주 하나를 뚝심으로 들고 가는 장기 보유형" },
  CKEA: { nickname: "레버리지 돌격대", description: "국내 지수 레버리지·인버스 ETF에 집중 베팅하는 방향성 트레이더" },
  CKEB: { nickname: "코덱스 지킴이", description: "국내 대표 지수 ETF 하나로 시장 전체를 편하게 담는 실속파" },
  CGSA: { nickname: "서학개미 스나이퍼", description: "테슬라·엔비디아 같은 미국 성장주에 몰빵하는 공격적 서학개미" },
  CGSB: { nickname: "빅테크 우직러", description: "믿는 미국 우량주 하나를 흔들림 없이 장기 보유하는 뚝심형" },
  CGEA: { nickname: "나스닥 질주족", description: "TQQQ류 해외 레버리지 ETF에 집중하는 고위험 질주형" },
  CGEB: { nickname: "S&P 뚜벅이", description: "미국 대표 지수 ETF 하나로 우직하게 세계 경제에 올라타는 안정형" },
  DKSA: { nickname: "코스닥 사냥꾼", description: "여러 국내 종목에 레버리지를 섞어 공격적으로 굴리는 다종목 헌터" },
  DKSB: { nickname: "국장 균형러", description: "국내 여러 우량주에 고르게 나눠 담는 안정적 분산 투자자" },
  DKEA: { nickname: "섹터 로테이터", description: "국내 섹터·레버리지 ETF 여러 개를 돌려가며 공격적으로 베팅" },
  DKEB: { nickname: "인덱스 정석러", description: "국내 지수 ETF를 고르게 나눠 담는 교과서적 분산 안정형" },
  DGSA: { nickname: "글로벌 헌터", description: "미국 여러 성장주에 레버리지를 섞어 세계를 누비는 공격적 서학개미" },
  DGSB: { nickname: "글로벌 컬렉터", description: "미국 우량주 여러 개를 고르게 모으는 안정적 글로벌 분산러" },
  DGEA: { nickname: "월드 레버리지 서퍼", description: "해외 지수·테마 레버리지 ETF 여러 개로 파도를 타는 공격형" },
  DGEB: { nickname: "올웨더 항해사", description: "전 세계 지수 ETF에 고르게 분산해 어떤 장세든 항해하는 궁극의 안정 분산러" },
};

/** 각 축의 왼쪽(비중이 높은 쪽)·오른쪽 글자와 이름 — `InvestmentType`의 4개 enum과 순서가 같다. */
export const PERSONALITY_AXES = [
  { key: "concentration", title: "분산", highLetter: "C", highName: "집중", lowLetter: "D", lowName: "분산" },
  { key: "domestic", title: "시장", highLetter: "K", highName: "국내", lowLetter: "G", lowName: "해외" },
  { key: "individual", title: "유형", highLetter: "S", highName: "개별주", lowLetter: "E", lowName: "ETF" },
  { key: "aggressive", title: "공격성", highLetter: "A", highName: "공격", lowLetter: "B", lowName: "안정" },
] as const;
