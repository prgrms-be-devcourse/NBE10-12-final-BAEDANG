/// 투자 성향 리포트 16유형의 별명·한줄 설명 — 웹 `front/src/lib/personality-types.ts`
/// 를 그대로 옮겼다. 백엔드는 사실 그대로의 typeLabel만 내려주고, 이 별명·설명은
/// 순수 표시용 콘텐츠라 여기서 typeCode로 찾아 붙인다.
///
/// 코드 순서는 분산(C/D)·시장(K/G)·유형(S/E)·공격성(A/B) — 백엔드
/// `InvestmentType.code()`와 같다.
class PersonalityTypeInfo {
  const PersonalityTypeInfo({required this.nickname, required this.description});

  final String nickname;
  final String description;
}

const Map<String, PersonalityTypeInfo> kPersonalityTypes = {
  'CKSA': PersonalityTypeInfo(nickname: '코스피 저격수', description: '국내 개별주 한두 종목에 레버리지까지 얹어 한 방을 노리는 승부사'),
  'CKSB': PersonalityTypeInfo(nickname: '우량주 집사', description: '삼성전자 같은 국내 대형 우량주 하나를 뚝심으로 들고 가는 장기 보유형'),
  'CKEA': PersonalityTypeInfo(nickname: '레버리지 돌격대', description: '국내 지수 레버리지·인버스 ETF에 집중 베팅하는 방향성 트레이더'),
  'CKEB': PersonalityTypeInfo(nickname: '코덱스 지킴이', description: '국내 대표 지수 ETF 하나로 시장 전체를 편하게 담는 실속파'),
  'CGSA': PersonalityTypeInfo(nickname: '서학개미 스나이퍼', description: '테슬라·엔비디아 같은 미국 성장주에 몰빵하는 공격적 서학개미'),
  'CGSB': PersonalityTypeInfo(nickname: '빅테크 우직러', description: '믿는 미국 우량주 하나를 흔들림 없이 장기 보유하는 뚝심형'),
  'CGEA': PersonalityTypeInfo(nickname: '나스닥 질주족', description: 'TQQQ류 해외 레버리지 ETF에 집중하는 고위험 질주형'),
  'CGEB': PersonalityTypeInfo(nickname: 'S&P 뚜벅이', description: '미국 대표 지수 ETF 하나로 우직하게 세계 경제에 올라타는 안정형'),
  'DKSA': PersonalityTypeInfo(nickname: '코스닥 사냥꾼', description: '여러 국내 종목에 레버리지를 섞어 공격적으로 굴리는 다종목 헌터'),
  'DKSB': PersonalityTypeInfo(nickname: '국장 균형러', description: '국내 여러 우량주에 고르게 나눠 담는 안정적 분산 투자자'),
  'DKEA': PersonalityTypeInfo(nickname: '섹터 로테이터', description: '국내 섹터·레버리지 ETF 여러 개를 돌려가며 공격적으로 베팅'),
  'DKEB': PersonalityTypeInfo(nickname: '인덱스 정석러', description: '국내 지수 ETF를 고르게 나눠 담는 교과서적 분산 안정형'),
  'DGSA': PersonalityTypeInfo(nickname: '글로벌 헌터', description: '미국 여러 성장주에 레버리지를 섞어 세계를 누비는 공격적 서학개미'),
  'DGSB': PersonalityTypeInfo(nickname: '글로벌 컬렉터', description: '미국 우량주 여러 개를 고르게 모으는 안정적 글로벌 분산러'),
  'DGEA': PersonalityTypeInfo(nickname: '월드 레버리지 서퍼', description: '해외 지수·테마 레버리지 ETF 여러 개로 파도를 타는 공격형'),
  'DGEB': PersonalityTypeInfo(nickname: '올웨더 항해사', description: '전 세계 지수 ETF에 고르게 분산해 어떤 장세든 항해하는 궁극의 안정 분산러'),
};

/// 각 축의 "높은 쪽"·"낮은 쪽" 글자와 이름 — `InvestmentType`의 4개 enum과 순서가 같다.
class PersonalityAxis {
  const PersonalityAxis({
    required this.title,
    required this.highLetter,
    required this.highName,
    required this.lowLetter,
    required this.lowName,
  });

  final String title;
  final String highLetter;
  final String highName;
  final String lowLetter;
  final String lowName;
}

const List<PersonalityAxis> kPersonalityAxes = [
  PersonalityAxis(title: '분산', highLetter: 'C', highName: '집중', lowLetter: 'D', lowName: '분산'),
  PersonalityAxis(title: '시장', highLetter: 'K', highName: '국내', lowLetter: 'G', lowName: '해외'),
  PersonalityAxis(title: '유형', highLetter: 'S', highName: '개별주', lowLetter: 'E', lowName: 'ETF'),
  PersonalityAxis(title: '공격성', highLetter: 'A', highName: '공격', lowLetter: 'B', lowName: '안정'),
];

/// 도움말 모달의 4축 판정 기준 문구 — 웹 HELP_ITEMS와 동일.
const List<({String title, String desc})> kPersonalityHelpItems = [
  (title: '분산 · 집중(C) ↔ 분산(D)', desc: '가장 큰 종목의 평가비중으로 판단해요. 한 종목이 50% 이상이면 집중(C), 아니면 분산(D)이에요.'),
  (title: '시장 · 국내(K) ↔ 해외(G)', desc: '국내 종목 평가비중이 50% 이상이면 국내(K), 아니면 해외(G)예요.'),
  (title: '유형 · 개별주(S) ↔ ETF(E)', desc: '개별주(개별주·우선주) 평가비중이 50% 이상이면 개별주(S), 아니면 ETF(E)예요.'),
  (title: '공격성 · 공격(A) ↔ 안정(B)', desc: '레버리지·인버스 상품 평가비중이 20% 이상이면 공격(A), 아니면 안정(B)이에요. (변동성 반영은 추후 예정)'),
];
