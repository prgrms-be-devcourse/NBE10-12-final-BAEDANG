#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""terms.md → Flutter 앱 위키 데이터 모듈 생성기.

generate_wiki_terms.py 의 파서를 그대로 쓰고 Dart 파일만 따로 만든다.

  실행:  python tools/wiki/generate_wiki_terms_dart.py [출력.dart]
  기본 출력:  investup_app/lib/features/guide/wiki_terms.dart
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from generate_wiki_terms import SRC, parse  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_OUT = os.path.join(
    REPO_ROOT, "investup_app", "lib", "features", "guide", "wiki_terms.dart"
)

HEADER = """// ============================================================================
//  금융 용어 위키 데이터 (terms.md 자동 생성 — 손으로 고치지 마세요)
//
//  원본은 tools/terms.md 입니다. 내용을 바꾸려면 terms.md 를 고친 뒤
//  `python tools/wiki/generate_wiki_terms_dart.py` 로 이 파일을 다시 생성하세요.
// ============================================================================

/// 용어 한 개. chosung 은 초성 검색용 미리 계산 키다.
class WikiTerm {
  const WikiTerm({
    required this.name,
    required this.summary,
    required this.aliases,
    required this.body,
    required this.chosung,
    required this.aliasChosungs,
  });

  final String name;
  final String summary;
  final List<String> aliases;
  final String body;
  final String chosung;
  final List<String> aliasChosungs;
}

const wikiTerms = <WikiTerm>[
"""


def dart_str(s):
    out = (
        s.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("\t", "\\t")
    )
    return f"'{out}'"


def dart_str_list(items):
    return "[" + ", ".join(dart_str(a) for a in items) + "]"


def render(parsed):
    parts = [HEADER]
    for p in parsed:
        parts.append(
            "  WikiTerm(\n"
            f"    name: {dart_str(p['name'])},\n"
            f"    summary: {dart_str(p['summary'])},\n"
            f"    aliases: {dart_str_list(p['aliases'])},\n"
            f"    body: {dart_str(p['body'])},\n"
            f"    chosung: {dart_str(p['chosung'])},\n"
            f"    aliasChosungs: {dart_str_list(p['aliasChosungs'])},\n"
            "  ),\n"
        )
    parts.append("];\n")
    return "".join(parts)


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT
    parsed = parse(SRC)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    io.open(out_path, "w", encoding="utf-8", newline="\n").write(render(parsed))
    n_alias = sum(len(p["aliases"]) for p in parsed)
    print(f"wrote {out_path}")
    print(f"terms={len(parsed)} aliases={n_alias}")


if __name__ == "__main__":
    main()
