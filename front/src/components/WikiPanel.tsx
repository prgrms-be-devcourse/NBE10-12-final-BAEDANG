"use client";

import { useEffect, useMemo, useState } from "react";
import { Reveal } from "@/components/Reveal";
import { WIKI_TERMS, type WikiTerm } from "@/data/wikiTerms";

/** 본문(마크다운) 을 문단과 코드블록으로 쪼갠다. wikiUI 디자인의 paras() 와 동일 규칙:
 *  ``` 로 코드펜스를 가르고, 나머지는 빈 줄 기준으로 문단을 나눈다. 표 렌더링은 하지
 *  않으므로 `**`(굵게)·백틱(인라인 코드) 표식만 벗겨 평문으로 보여준다. */
type Para = { text: string; code: boolean };

function toParas(body: string): Para[] {
  const clean = (s: string) => s.replace(/\*\*/g, "").replace(/`/g, "").trim();
  const out: Para[] = [];
  (body || "").split("```").forEach((part, i) => {
    if (i % 2 === 1) {
      const t = part.replace(/^\n+|\n+$/g, "");
      if (t.trim()) out.push({ text: t, code: true });
    } else {
      part.split(/\n\s*\n/).forEach((p) => {
        const t = clean(p);
        if (t) out.push({ text: t, code: false });
      });
    }
  });
  return out;
}

/** 전체 목록에서 n 개를 고르게 솎아낸다. 무작위가 아니라 결정적이라 서버·클라이언트
 *  렌더가 일치한다(hydration 불일치·이펙트 없이 마퀴를 그릴 수 있다). */
function spread<T>(arr: T[], n: number): T[] {
  const step = Math.max(1, Math.floor(arr.length / n));
  const out: T[] = [];
  for (let i = 0; i < arr.length && out.length < n; i += step) out.push(arr[i]);
  return out;
}

const MARQUEE_TERMS = spread(WIKI_TERMS, 24);

function TermPill({ term, onOpen }: { term: WikiTerm; onOpen: (t: WikiTerm) => void }) {
  return (
    <button
      type="button"
      onClick={() => onOpen(term)}
      className="flex-none cursor-pointer whitespace-nowrap rounded-full px-[26px] py-[15px] text-[18px] font-normal transition-shadow duration-150"
      style={{ background: "var(--card)", color: "var(--ink)" }}
      onMouseEnter={(e) => (e.currentTarget.style.boxShadow = "0 4px 14px rgba(15,56,104,.12)")}
      onMouseLeave={(e) => (e.currentTarget.style.boxShadow = "none")}
    >
      {term.name}
    </button>
  );
}

/** 검색 전 유휴 상태의 흐르는 마퀴. 두 줄이 서로 다른 속도로 좌로 흐른다. */
function IdleMarquee({ onOpen }: { onOpen: (t: WikiTerm) => void }) {
  const half = Math.ceil(MARQUEE_TERMS.length / 2);
  const rows = [MARQUEE_TERMS.slice(0, half), MARQUEE_TERMS.slice(half)];

  return (
    <div
      className="mt-[34px] flex w-full flex-col gap-3 overflow-hidden"
      style={{
        maskImage: "linear-gradient(90deg,transparent,#000 8%,#000 92%,transparent)",
        WebkitMaskImage: "linear-gradient(90deg,transparent,#000 8%,#000 92%,transparent)",
      }}
    >
      {rows.map((row, ri) => (
        <div key={ri} className="wiki-marquee-row" style={{ animationDuration: ri === 0 ? "44s" : "33s" }}>
          {/* 이음매 없는 반복을 위해 같은 목록을 네 번 이어 붙인다(-25% 이동). */}
          {[0, 1, 2, 3].map((copy) =>
            row.map((t, i) => <TermPill key={`${copy}-${i}`} term={t} onOpen={onOpen} />)
          )}
        </div>
      ))}
    </div>
  );
}

function TermModal({ term, onClose }: { term: WikiTerm; onClose: () => void }) {
  const paras = useMemo(() => toParas(term.body), [term.body]);
  const chips = term.aliases.length ? term.aliases : ["별칭 없음"];

  // 모달이 떠 있는 동안 뒷배경(가이드 페이지) 스크롤을 잠근다. 이 컴포넌트는 열릴 때만
  // 마운트되므로 마운트에서 잠그고 언마운트에서 원복한다(이전 overflow 값 보존).
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-[150] flex items-center justify-center p-6"
      style={{ background: "var(--modalOverlay)", animation: "modalFade .28s" }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="wiki-term-title"
        onClick={(e) => e.stopPropagation()}
        className="flex w-full max-w-[620px] flex-col rounded-[24px] px-8 py-[30px]"
        style={{ maxHeight: "85vh", background: "var(--card)", animation: "modalPop .4s cubic-bezier(.2,.9,.3,1.1)" }}
      >
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h3 id="wiki-term-title" className="text-[26px] font-extrabold tracking-[-.02em]" style={{ color: "var(--ink)" }}>
              {term.name}
            </h3>
            <div className="mt-[9px] flex flex-wrap gap-1.5">
              {chips.map((al) => (
                <span
                  key={al}
                  className="rounded-full px-2.5 py-1 text-[12px] font-bold"
                  style={{ background: "var(--accentSoft)", color: "var(--onAccentSoftText)" }}
                >
                  {al}
                </span>
              ))}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex-none cursor-pointer border-0 bg-transparent px-2.5 py-1.5 text-[13px] font-semibold"
            style={{ color: "var(--mut)" }}
          >
            닫기
          </button>
        </div>

        <p
          className="mt-[18px] rounded-[14px] px-[18px] py-4 text-[15px] font-bold leading-[1.7]"
          style={{ background: "var(--accentSoft)", color: "var(--onAccentSoftText)" }}
        >
          {term.summary}
        </p>

        <div className="mt-4 flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto">
          {paras.map((p, i) =>
            p.code ? (
              <pre
                key={i}
                className="whitespace-pre-wrap rounded-[12px] px-4 py-3.5 text-[12.5px] leading-[1.7]"
                style={{ background: "var(--fill)", color: "var(--body)", fontFamily: "ui-monospace,Menlo,monospace" }}
              >
                {p.text}
              </pre>
            ) : (
              <p
                key={i}
                className="text-[14px] leading-[1.78]"
                style={{ color: "var(--body)", whiteSpace: "pre-line" }}
              >
                {p.text}
              </p>
            )
          )}
        </div>
      </div>
    </div>
  );
}

export function WikiPanel() {
  const [q, setQ] = useState("");
  const [modal, setModal] = useState<WikiTerm | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setModal(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const query = q.trim().toLowerCase();
  const found = useMemo(() => {
    if (!query) return [];
    // 쿼리가 자음 자모(ㄱ~ㅎ)로만 이뤄졌으면 초성 검색. 사용자가 자판으로 친 홑자음은
    // 생성기가 미리 계산해 둔 chosung 과 같은 호환 자모라 변환 없이 바로 대조한다.
    const isChosung = /^[ㄱ-ㅎ]+$/.test(query.replace(/\s/g, ""));
    if (isChosung) {
      const cq = query.replace(/\s/g, "");
      return WIKI_TERMS.filter(
        (t) => t.chosung.includes(cq) || t.aliasChosungs.some((c) => c.includes(cq))
      ).slice(0, 40);
    }
    return WIKI_TERMS.filter(
      (t) =>
        t.name.toLowerCase().includes(query) ||
        t.aliases.some((a) => a.toLowerCase().includes(query))
    ).slice(0, 40);
  }, [query]);

  const hint = query
    ? `"${q.trim()}" 검색 결과 ${found.length}개`
    : `전체 ${WIKI_TERMS.length}개 · 마우스를 올리면 멈춰요`;

  return (
    <div>
      <Reveal delay={0.02} className="flex flex-col items-center pt-[44px]">
        <h2
          className="text-center text-[42px] font-extrabold leading-[1.28] tracking-[-.02em]"
          style={{ color: "var(--ink)", textWrap: "pretty" } as React.CSSProperties}
        >
          모르는 용어가 있다면?
          <br />
          <span style={{ color: "var(--accentText)" }}>검색해보세요.</span>
        </h2>
        <p className="mt-3.5 text-[15px]" style={{ color: "var(--mut)" }}>
          거래 화면에 실제로 등장하는 용어만 쉬운 말로 풀어 두었어요
        </p>

        <div className="relative mt-[26px] w-full max-w-[480px]">
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2"
          >
            <circle cx="11" cy="11" r="7" stroke="var(--mut2)" strokeWidth="2" />
            <path d="M21 21l-4.3-4.3" stroke="var(--mut2)" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <input
            type="text"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="용어·별칭·초성(ㅅㄱ)으로 검색"
            className="w-full rounded-[12px] border-0 py-2.5 pl-[42px] pr-4 text-[15px] outline-none"
            style={{ background: "var(--card)", color: "var(--ink)" }}
          />
        </div>
        <p className="mt-3 text-[12.5px]" style={{ color: "var(--mut2)" }}>
          {hint}
        </p>
      </Reveal>

      {query ? (
        <div className="mx-auto mt-[30px] flex w-full max-w-[820px] flex-wrap justify-center gap-2.5">
          {found.length ? (
            found.map((t) => <TermPill key={t.name} term={t} onOpen={setModal} />)
          ) : (
            <p className="py-8 text-[14px]" style={{ color: "var(--mut2)" }}>
              검색 결과가 없어요. 다른 말로 검색해 보세요.
            </p>
          )}
        </div>
      ) : (
        <IdleMarquee onOpen={setModal} />
      )}

      {modal && <TermModal term={modal} onClose={() => setModal(null)} />}
    </div>
  );
}
