import 'package:dio/dio.dart';

import 'wiki_terms.dart';

/// 금융 용어 공급원(port).
///
/// terms.md가 수정되면 앱도 최신 내용을 보여야 하므로
/// 생성본을 박아두기만 하지 않고 원문을 가져와 런타임에 변환한다.
/// 실패 시에는 생성된 스냅샷(wiki_terms.dart)으로 폴백한다.
abstract class WikiTermsSource {
  Future<List<WikiTerm>> fetch();
}

/// terms.md 원문 위치. 리포지토리 기본 브랜치의 원본을 그대로 가져온다.
const kWikiTermsSourceUrl =
    'https://raw.githubusercontent.com/prgrms-be-devcourse/'
    'NBE10-12-final-BAEDANG/develop/tools/terms.md';

/// 원격 어댑터: terms.md 원문을 가져와 런타임에 파싱한다.
class RemoteWikiTermsSource implements WikiTermsSource {
  RemoteWikiTermsSource({Dio? dio, this.url = kWikiTermsSourceUrl})
    : _dio = dio ?? Dio();

  final Dio _dio;
  final String url;

  @override
  Future<List<WikiTerm>> fetch() async {
    final response = await _dio.get<String>(url);
    final body = response.data;
    if (body == null || body.isEmpty) {
      throw StateError('용어 원문이 비어 있어요');
    }
    return parseWikiTerms(body);
  }
}

/// 로컬 어댑터: 생성된 스냅샷을 그대로 돌려준다. 네트워크 실패 시 폴백용.
class BundledWikiTermsSource implements WikiTermsSource {
  const BundledWikiTermsSource();

  @override
  Future<List<WikiTerm>> fetch() async => wikiTerms;
}

/// 폴백 어댑터: 1차 공급원이 실패하면 2차를 돌려준다.
class FallbackWikiTermsSource implements WikiTermsSource {
  const FallbackWikiTermsSource(this.primary, this.fallback);

  final WikiTermsSource primary;
  final WikiTermsSource fallback;

  @override
  Future<List<WikiTerm>> fetch() async {
    try {
      return await primary.fetch();
    } catch (_) {
      return fallback.fetch();
    }
  }
}

/// terms.md 마크다운을 WikiTerm 목록으로 변환한다.
/// `## 이름` → `> 요약` → `> alias: 별칭` → 본문 문단 순서를 따른다.
List<WikiTerm> parseWikiTerms(String markdown) {
  final terms = <WikiTerm>[];
  String? name;
  String summary = '';
  List<String> aliases = [];
  final body = StringBuffer();

  void flush() {
    if (name == null) return;
    terms.add(
      WikiTerm(
        name: name!,
        summary: summary,
        aliases: aliases,
        body: body.toString().trim(),
        chosung: chosungOf(name!),
        aliasChosungs: [for (final a in aliases) chosungOf(a)],
      ),
    );
    name = null;
    summary = '';
    aliases = [];
    body.clear();
  }

  for (final raw in markdown.split('\n')) {
    final line = raw.trimRight();
    if (line.startsWith('## ')) {
      flush();
      name = line.substring(3).trim();
    } else if (name != null && line.startsWith('> ')) {
      final quote = line.substring(2);
      if (quote.startsWith('alias:')) {
        aliases = [
          for (final a in quote.substring(6).split(','))
            if (a.trim().isNotEmpty) a.trim(),
        ];
      } else {
        summary = quote.trim();
      }
    } else if (name != null && line.trim().isNotEmpty) {
      body.writeln(line.trim());
    }
  }
  flush();
  return terms;
}

/// 한글 초성(자음)만 남기고 나머지 문자는 그대로 유지한다.
/// 웹 검색과 같은 규칙 — "삼성전자" → "ㅅㅅㅈㅈ".
String chosungOf(String text) {
  const initials = [
    'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ',
    'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
  ];
  final buffer = StringBuffer();
  for (final unit in text.codeUnits) {
    if (unit >= 0xAC00 && unit <= 0xD7A3) {
      buffer.write(initials[(unit - 0xAC00) ~/ 588]);
    } else if (unit >= 0x3130 && unit <= 0x318F) {
      // 이미 자모인 경우 그대로.
      buffer.writeCharCode(unit);
    } else {
      buffer.writeCharCode(unit);
    }
  }
  return buffer.toString();
}
