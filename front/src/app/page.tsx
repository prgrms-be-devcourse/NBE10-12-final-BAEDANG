import Link from "next/link";

const STEPS = [
  { step: "STEP 1", title: "모의 투자금 5,000만원 받기", desc: "가입하면 자동 지급됩니다. 다 쓰면 포트폴리오를 초기화해 다시 시작할 수 있습니다." },
  { step: "STEP 2", title: "랭킹에서 종목 고르기", desc: "거래대금 상위 100개 종목을 국내·해외로 나눠 보여드립니다." },
  { step: "STEP 3", title: "실제 시세로 매수·매도", desc: "장 운영 시간에 시장가로 즉시 체결됩니다. 수수료와 세금도 그대로 반영됩니다." },
];

const COMPARE_ROWS = [
  { label: "목적", other: "거래 체결", ours: "학습과 훈련" },
  { label: "실수했을 때", other: "실제 손실, 되돌릴 수 없음", ours: "손실 없음, 초기화하고 다시" },
  { label: "수수료·세금", other: "거래 후 결과에만 반영", ours: "주문 전 미리 보여줌" },
  { label: "사용법 안내", other: "없음", ours: "이용가이드 · 용어 위키 제공" },
];

export default function MainPage() {
  return (
    <div className="p-6">
      {/* 히어로 */}
      <div className="mb-8 flex items-center gap-8 rounded-lg bg-gray-100 p-10">
        <div className="flex-[1.15]">
          <span className="inline-block rounded border border-gray-300 px-1.5 py-0.5 text-[10.5px] text-gray-500">
            투자 연습장
          </span>
          <h1 className="mt-3 text-[26px] font-bold leading-snug text-gray-900">
            잃어도 괜찮은 돈으로,
            <br />
            잃지 않는 법을 배웁니다
          </h1>
          <p className="my-3 max-w-[440px] text-[14px] text-gray-500">
            실제 시장 시세로 국내·해외 주식을 사고팔며 투자 감각을 길러보세요.{" "}
            <b className="text-gray-900">모의 투자금 5,000만원</b>이 가입 즉시 지급됩니다.
          </p>
          <div className="flex gap-2.5">
            <Link
              href="/rankings"
              className="rounded-md bg-gray-900 px-6 py-2.5 text-[13px] text-white hover:bg-black"
            >
              모의 투자금 받고 시작하기
            </Link>
            <Link
              href="/guide"
              className="rounded-md border border-gray-300 bg-white px-5 py-2.5 text-[13px] text-gray-900 hover:bg-gray-50"
            >
              이용가이드 보기
            </Link>
          </div>
          <div className="mt-3 text-[11.5px] text-gray-400">
            실제 돈이 오가지 않습니다 · 언제든 포트폴리오를 초기화할 수 있습니다
          </div>
        </div>
        <div className="flex h-[200px] flex-1 flex-col items-center justify-center gap-1.5 rounded-md border border-dashed border-gray-300 bg-white text-[12.5px] text-gray-400">
          <b className="text-[13px] text-gray-900">서비스 화면 미리보기</b>
          <span>거래 화면 · 보유 종목</span>
        </div>
      </div>

      {/* 3단계 */}
      <div className="mb-8">
        <h2 className="text-center text-[17px] font-bold text-gray-900">이렇게 사용합니다</h2>
        <p className="mb-4.5 text-center text-[13px] text-gray-500">가입부터 첫 거래까지 3단계</p>
        <div className="flex gap-4">
          {STEPS.map((s) => (
            <div key={s.step} className="flex-1 rounded-lg border border-gray-200 p-4">
              <div className="text-[11.5px] font-bold text-gray-500">{s.step}</div>
              <h4 className="my-1.5 text-[14px] font-semibold text-gray-900">{s.title}</h4>
              <p className="text-[11.5px] leading-relaxed text-gray-500">{s.desc}</p>
            </div>
          ))}
        </div>
      </div>

      {/* 비교표 */}
      <div className="mb-8">
        <h2 className="text-[17px] font-bold text-gray-900">증권사 앱과 무엇이 다른가요?</h2>
        <p className="mb-4.5 text-[13px] text-gray-500">
          증권사 앱은 거래를 <b className="text-gray-900">체결</b>시키는 도구이고, 저희는 거래를{" "}
          <b className="text-gray-900">이해</b>시키는 도구입니다.
        </p>
        <table className="w-full overflow-hidden rounded-lg border border-gray-200 text-[13px]">
          <thead>
            <tr>
              <th className="w-[26%] border-b border-gray-200 p-2.5" />
              <th className="w-[37%] border-b border-gray-200 p-2.5 text-left font-medium">
                일반 증권사 앱
              </th>
              <th className="w-[37%] border-b border-gray-200 p-2.5 text-left font-bold text-gray-900">
                모의주식 트레이딩
              </th>
            </tr>
          </thead>
          <tbody>
            {COMPARE_ROWS.map((row) => (
              <tr key={row.label}>
                <td className="border-b border-gray-100 p-2.5 text-gray-500">{row.label}</td>
                <td className="border-b border-gray-100 p-2.5">{row.other}</td>
                <td className="border-b border-gray-100 p-2.5">
                  <b>{row.ours}</b>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* CTA */}
      <div className="rounded-lg bg-gray-100 p-8 text-center">
        <h2 className="text-[19px] font-bold text-gray-900">첫 거래는 오늘, 첫 손실은 0원</h2>
        <p className="mx-auto my-2 max-w-[420px] text-[13px] text-gray-500">
          모의 투자금 5,000만원으로 지금 시작해보세요
        </p>
        <Link
          href="/rankings"
          className="inline-block rounded-md bg-gray-900 px-7 py-2.5 text-[13px] text-white hover:bg-black"
        >
          시작하기
        </Link>
      </div>

      <p className="mt-4 text-center text-[11.5px] leading-relaxed text-gray-400">
        본 서비스는 <b>투자 교육을 목적으로 하는 모의 투자 서비스</b>입니다. 실제 매매가 이루어지지
        않으며, 특정 종목에 대한 투자 조언이나 매매 권유를 제공하지 않습니다.
        <br />
        시세는 토스증권 Open API를 통해 제공되며 실시간과 수 초의 차이가 있을 수 있습니다.
      </p>
    </div>
  );
}
