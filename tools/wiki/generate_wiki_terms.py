#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""terms.md → 프론트 위키 데이터 모듈 생성기 (1회성 데이터 파이프라인).

금융 용어 위키의 원본 데이터(tools/terms.md)를 파싱해 프론트에서
정적으로 import 하는 TypeScript 데이터 모듈을 만든다. 위키는 프론트-온리로,
백엔드/DB 없이 이 배열을 브라우저에서 필터링해 검색한다.

  실행:  python tools/wiki/generate_wiki_terms.py [출력.ts]
  기본 출력:  front/src/data/wikiTerms.ts

파싱 규칙(terms.md 머리말과 동일):
  · 용어 경계는 줄 시작 '## ' 로만 자른다. '---' 로 자르지 않는다(표의 |---| 때문).
  · 첫 '>' 줄들이 요약, '> alias:' 줄이 별칭(콤마 구분), 나머지가 본문(마크다운).

본문 마크다운 렌더(코드펜스/굵게 제거 등)는 런타임 컴포넌트가 처리하므로
여기서는 원본 body 문자열을 그대로 넘긴다.
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(HERE, "..", "terms.md")  # 리포 루트 tools/terms.md (생성기 옆 폴더 위)
DEFAULT_OUT = os.path.join(REPO_ROOT, "front", "src", "data", "wikiTerms.ts")


CHO = list("ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ")  # 19 현대 초성(호환 자모)


def chosung(s):
    """문자열의 초성 키. 한글 음절 → 초성 자모(호환 자모), 공백 제거, 그 외는 소문자 보존.
    사용자가 자판으로 친 홑자음(ㅅ 등)이 이 호환 자모와 같은 코드포인트라 그대로 매칭된다."""
    out = []
    for ch in s:
        if ch.isspace():
            continue
        code = ord(ch)
        if 0xAC00 <= code <= 0xD7A3:
            out.append(CHO[(code - 0xAC00) // 588])  # 588 = 중성21 × 종성28
        else:
            out.append(ch.lower())
    return "".join(out)


def alias_split(rest):
    return [a.strip() for a in rest.split(",") if a.strip()]


def _more_quotes_ahead(raw, i):
    j = i + 1
    while j < len(raw) and raw[j].strip() == "":
        j += 1
    return j < len(raw) and raw[j].strip().startswith(">")


def parse(path):
    lines = io.open(path, encoding="utf-8").read().splitlines()
    blocks = []
    cur = None
    for line in lines:
        if line.startswith("## "):
            if cur is not None:
                blocks.append(cur)
            cur = {"name": line[3:].strip(), "raw": []}
        elif cur is not None:
            cur["raw"].append(line)
    if cur is not None:
        blocks.append(cur)

    parsed = []
    for b in blocks:
        raw = b["raw"]
        i = 0
        while i < len(raw) and raw[i].strip() == "":
            i += 1
        summary_parts, aliases = [], []
        while i < len(raw):
            s = raw[i].strip()
            if s.startswith(">"):
                content = s[1:].strip()
                if content.lower().startswith("alias:"):
                    aliases = alias_split(content[len("alias:"):])
                elif content:
                    summary_parts.append(content)
                i += 1
            elif s == "" and _more_quotes_ahead(raw, i):
                i += 1
            else:
                break
        parsed.append({
            "name": b["name"],
            "summary": " ".join(summary_parts).strip(),
            "aliases": aliases,
            "body": "\n".join(raw[i:]).strip(),
            # 초성 검색용 미리 계산 키. 이름/별칭 각각의 초성을 담아 둔다.
            "chosung": chosung(b["name"]),
            "aliasChosungs": [chosung(a) for a in aliases],
        })
    return parsed


HEADER = """// ============================================================================
//  금융 용어 위키 데이터 (terms.md 자동 생성 — 손으로 고치지 마세요)
//
//  원본은 tools/terms.md 입니다. 내용을 바꾸려면 terms.md 를 고친 뒤
//  `python tools/wiki/generate_wiki_terms.py` 로 이 파일을 다시 생성하세요.
// ============================================================================

export type WikiTerm = {
  name: string;
  aliases: string[];
  summary: string;
  body: string;
  /** 초성 검색용 미리 계산 키(예: "시가총액" → "ㅅㄱㅊㅇ"). aliasChosungs 는 aliases 와 순서 대응. */
  chosung: string;
  aliasChosungs: string[];
};

export const WIKI_TERMS: WikiTerm[] =
"""


def render(parsed):
    # JSON 은 유효한 TS 리터럴이라 코드펜스/따옴표/개행이 든 body 도 안전하게 이스케이프된다.
    data = json.dumps(parsed, ensure_ascii=False, indent=2)
    return HEADER + data + ";\n"


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT
    parsed = parse(SRC)

    missing_summary = [p["name"] for p in parsed if not p["summary"]]
    missing_body = [p["name"] for p in parsed if not p["body"]]
    if missing_summary or missing_body:
        print(f"WARN missing_summary={missing_summary} missing_body={missing_body}", file=sys.stderr)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    io.open(out_path, "w", encoding="utf-8", newline="\n").write(render(parsed))

    n_alias = sum(len(p["aliases"]) for p in parsed)
    print(f"wrote {out_path}")
    print(f"terms={len(parsed)} aliases={n_alias}")


if __name__ == "__main__":
    main()
