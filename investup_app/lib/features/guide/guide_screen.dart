import 'package:flutter/material.dart';

import '../../widgets/app_widgets.dart';
import 'wiki_terms.dart';
import 'wiki_terms_source.dart';

/// 가이드 탭. 이용가이드와 금융 용어 위키 두 패널로 나뉜다.
/// 용어는 [WikiTermsSource]를 통해 가져온다 — 원문(terms.md)이
/// 바뀌면 리모트 어댑터가 최신 내용을 주고, 실패 시 스냅샷으로 폴백한다.
class GuideScreen extends StatefulWidget {
  const GuideScreen({
    super.key,
    this.termsSource = const BundledWikiTermsSource(),
  });

  final WikiTermsSource termsSource;

  @override
  State<GuideScreen> createState() => _GuideScreenState();
}

class _GuideScreenState extends State<GuideScreen> {
  static const steps = [
    (
      '계좌를 만들어요',
      '회원가입하면 모의 투자금으로 연습을 시작할 수 있어요. 실제 돈을 입금하거나 출금하는 서비스는 아니에요.',
      Icons.account_balance_wallet_outlined,
    ),
    (
      '종목을 살펴봐요',
      '국내·해외 랭킹에서 종목의 가격과 등락을 확인해보세요. 표시된 가격의 기준 시각도 함께 살펴보세요.',
      Icons.travel_explore,
    ),
    (
      '가격과 주문을 이해해요',
      '시세는 계속 변할 수 있어요. 주문 전 견적과 실제 체결 결과는 다를 수 있다는 점을 기억하세요.',
      Icons.menu_book_outlined,
    ),
    (
      '나의 투자 과정을 돌아봐요',
      '수익률만 보기보다 어떤 이유로 종목을 선택했는지 기록해보세요. 작은 연습을 쌓아 투자 감각을 길러요.',
      Icons.insights_outlined,
    ),
  ];

  String _tab = 'guide'; // guide | wiki
  List<WikiTerm> _terms = const [];

  @override
  void initState() {
    super.initState();
    widget.termsSource.fetch().then((terms) {
      if (mounted) setState(() => _terms = terms);
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: [
        SafeArea(
          bottom: false,
          child: Center(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
              child: SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'guide', label: Text('이용가이드')),
                  ButtonSegment(value: 'wiki', label: Text('금융 용어 위키')),
                ],
                selected: {_tab},
                showSelectedIcon: false,
                onSelectionChanged: (set) => setState(() => _tab = set.first),
              ),
            ),
          ),
        ),
        Expanded(
          child: _tab == 'guide'
              ? _buildGuidePanel(theme)
              : _WikiPanel(terms: _terms),
        ),
      ],
    );
  }

  Widget _buildGuidePanel(ThemeData theme) {
    return PageList(
      children: [
        Text('처음이어도 괜찮아요', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 8),
        const Text('실수는 가볍게, 투자 감각은 제대로.'),
        const SizedBox(height: 24),
        for (var i = 0; i < steps.length; i++) ...[
          AppCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  steps[i].$3,
                  color: theme.colorScheme.primary,
                  size: 32,
                ),
                const SizedBox(height: 16),
                Text(
                  '${i + 1}. ${steps[i].$1}',
                  style: theme.textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                Text(steps[i].$2, style: theme.textTheme.bodyLarge),
              ],
            ),
          ),
          const SizedBox(height: 16),
        ],
      ],
    );
  }
}

/// 웹 WikiPanel의 모바일 판. 검색 전에는 용어 pill이 두 줄로 흐르고,
/// 검색 중에는 결과 pill만 모아 보여준다. pill을 누르면 모달이 열린다.
class _WikiPanel extends StatefulWidget {
  const _WikiPanel({required this.terms});

  final List<WikiTerm> terms;

  @override
  State<_WikiPanel> createState() => _WikiPanelState();
}

class _WikiPanelState extends State<_WikiPanel> {
  final _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  String get _query => _searchController.text.trim();

  /// 웹과 같은 규칙: 초성만 치면 초성 키로, 아니면 이름·별칭 포함 검색.
  List<WikiTerm> get _results {
    final query = _query.toLowerCase();
    if (query.isEmpty) return const [];
    final isChosung = RegExp(r'^[ㄱ-ㅎ]+$').hasMatch(query.replaceAll(' ', ''));
    if (isChosung) {
      final cq = query.replaceAll(' ', '');
      return widget.terms
          .where(
            (t) =>
                t.chosung.contains(cq) ||
                t.aliasChosungs.any((a) => a.contains(cq)),
          )
          .toList(growable: false);
    }
    return widget.terms
        .where(
          (t) =>
              t.name.toLowerCase().contains(query) ||
              t.aliases.any((a) => a.toLowerCase().contains(query)),
        )
        .toList(growable: false);
  }

  void _openTerm(WikiTerm term) {
    showDialog<void>(
      context: context,
      builder: (context) => _TermDialog(term: term),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final searching = _query.isNotEmpty;
    final results = _results;

    return CustomScrollView(
      slivers: [
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 24, 20, 0),
            child: Column(
              children: [
                Text(
                  '모르는 용어가 있다면?\n검색해보세요.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.headlineMedium?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  '거래 화면에 실제로 등장하는 용어만 쉬운 말로 풀어 두었어요',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: scheme.onSurfaceVariant),
                ),
                const SizedBox(height: 20),
                TextField(
                  controller: _searchController,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    hintText: '용어·별칭·초성(ㅅㄱ)으로 검색',
                    prefixIcon: const Icon(Icons.search),
                    suffixIcon: searching
                        ? IconButton(
                            icon: const Icon(Icons.close),
                            onPressed: () {
                              _searchController.clear();
                              setState(() {});
                            },
                          )
                        : null,
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  searching
                      ? '"$_query" 검색 결과 ${results.length}개'
                      : '전체 ${widget.terms.length}개',
                  style: TextStyle(
                    fontSize: 12.5,
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ),
        if (searching)
          SliverToBoxAdapter(
            child: results.isEmpty
                ? Padding(
                    padding: const EdgeInsets.all(32),
                    child: Text(
                      '검색 결과가 없어요. 다른 말로 검색해 보세요.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: scheme.onSurfaceVariant),
                    ),
                  )
                : Padding(
                    padding: const EdgeInsets.all(20),
                    child: Wrap(
                      spacing: 10,
                      runSpacing: 10,
                      alignment: WrapAlignment.center,
                      children: [
                        for (final term in results)
                          _TermPill(
                            term: term,
                            onTap: () => _openTerm(term),
                          ),
                      ],
                    ),
                  ),
          )
        else if (widget.terms.isNotEmpty)
          SliverToBoxAdapter(
            child: _IdleMarquee(terms: widget.terms, onOpen: _openTerm),
          ),
      ],
    );
  }
}

/// 유휴 상태의 흐르는 마퀴. 두 줄이 서로 다른 속도로 좌로 흐른다.
class _IdleMarquee extends StatelessWidget {
  const _IdleMarquee({required this.terms, required this.onOpen});

  final List<WikiTerm> terms;
  final ValueChanged<WikiTerm> onOpen;

  /// 웹과 같은 결정적 샘플링 — 전체에서 n개를 고르게 솎아낸다.
  static List<WikiTerm> _spread(List<WikiTerm> terms, int n) {
    final step = terms.length / n < 1 ? 1 : (terms.length / n).floor();
    return [for (var i = 0; i < terms.length; i += step) terms[i]].take(n).toList();
  }

  @override
  Widget build(BuildContext context) {
    final marquee = _spread(terms, 24);
    final half = (marquee.length / 2).ceil();
    return Padding(
      padding: const EdgeInsets.only(top: 28),
      child: ShaderMask(
        shaderCallback: (rect) => const LinearGradient(
          colors: [
            Colors.transparent,
            Colors.black,
            Colors.black,
            Colors.transparent,
          ],
          stops: [0, 0.08, 0.92, 1],
        ).createShader(rect),
        blendMode: BlendMode.dstIn,
        child: Column(
          children: [
            _MarqueeRow(
              terms: marquee.sublist(0, half),
              seconds: 44,
              onOpen: onOpen,
            ),
            const SizedBox(height: 12),
            _MarqueeRow(
              terms: marquee.sublist(half),
              seconds: 33,
              onOpen: onOpen,
            ),
          ],
        ),
      ),
    );
  }
}

/// 한 줄의 무한 스크롤 pill 행. 콘텐츠를 두 번 이어 붙여 절반 지점에서
/// 오프셋을 감췄다 다시 시작해 이음매 없이 반복된다.
class _MarqueeRow extends StatefulWidget {
  const _MarqueeRow({
    required this.terms,
    required this.seconds,
    required this.onOpen,
  });

  final List<WikiTerm> terms;
  final int seconds;
  final ValueChanged<WikiTerm> onOpen;

  @override
  State<_MarqueeRow> createState() => _MarqueeRowState();
}

class _MarqueeRowState extends State<_MarqueeRow>
    with SingleTickerProviderStateMixin {
  final _scroll = ScrollController();
  late final AnimationController _ticker = AnimationController(
    vsync: this,
    duration: Duration(seconds: widget.seconds),
  )..repeat();

  @override
  void initState() {
    super.initState();
    _ticker.addListener(_drive);
  }

  void _drive() {
    if (!_scroll.hasClients) return;
    final pos = _scroll.position;
    if (!pos.hasContentDimensions) return;
    final half = (pos.maxScrollExtent + pos.viewportDimension) / 2;
    if (half <= 0) return;
    _scroll.jumpTo(_ticker.value * half);
  }

  @override
  void dispose() {
    _ticker.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      controller: _scroll,
      scrollDirection: Axis.horizontal,
      physics: const NeverScrollableScrollPhysics(),
      child: Row(
        children: [
          for (var copy = 0; copy < 2; copy++)
            for (final term in widget.terms)
              Padding(
                padding: const EdgeInsets.only(right: 10),
                child: _TermPill(term: term, onTap: () => widget.onOpen(term)),
              ),
        ],
      ),
    );
  }
}

class _TermPill extends StatelessWidget {
  const _TermPill({required this.term, required this.onTap});

  final WikiTerm term;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Material(
      color: theme.cardColor,
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(999),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 12),
          child: Text(term.name, style: theme.textTheme.bodyLarge),
        ),
      ),
    );
  }
}

/// 용어 모달 — 웹 TermModal과 같은 구성: 이름+닫기, 별칭 칩,
/// 강조 요약 상자, 본문 문단(``` 코드블록은 고정폭).
class _TermDialog extends StatelessWidget {
  const _TermDialog({required this.term});

  final WikiTerm term;

  /// 본문을 문단/코드로 쪼갠다. ``` 로 코드펜스를 가르고 나머지는 빈 줄 기준.
  /// 표 렌더링은 하지 않으므로 **·` 표식만 벗긴다.
  static List<({String text, bool code})> _toParas(String body) {
    String clean(String s) =>
        s.replaceAll('**', '').replaceAll('`', '').trim();
    final out = <({String text, bool code})>[];
    final parts = body.split('```');
    for (var i = 0; i < parts.length; i++) {
      if (i.isOdd) {
        final t = parts[i].trim();
        if (t.isNotEmpty) out.add((text: t, code: true));
      } else {
        for (final p in parts[i].split(RegExp(r'\n\s*\n'))) {
          final t = clean(p);
          if (t.isNotEmpty) out.add((text: t, code: false));
        }
      }
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final chips = term.aliases.isEmpty ? ['별칭 없음'] : term.aliases;
    final paras = _toParas(term.body);

    return Dialog(
      insetPadding: const EdgeInsets.all(20),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: 620,
          maxHeight: MediaQuery.of(context).size.height * 0.85,
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          term.name,
                          style: theme.textTheme.headlineSmall?.copyWith(
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 6,
                          runSpacing: 6,
                          children: [
                            for (final alias in chips)
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 10,
                                  vertical: 4,
                                ),
                                decoration: BoxDecoration(
                                  color: scheme.primaryContainer,
                                  borderRadius: BorderRadius.circular(999),
                                ),
                                child: Text(
                                  alias,
                                  style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.w700,
                                    color: scheme.onPrimaryContainer,
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('닫기'),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(
                  horizontal: 18,
                  vertical: 14,
                ),
                decoration: BoxDecoration(
                  color: scheme.primaryContainer,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Text(
                  term.summary,
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    height: 1.7,
                    color: scheme.onPrimaryContainer,
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Flexible(
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    for (final p in paras)
                      p.code
                          ? Container(
                              margin: const EdgeInsets.only(bottom: 10),
                              padding: const EdgeInsets.all(14),
                              decoration: BoxDecoration(
                                color: scheme.surfaceContainerHighest,
                                borderRadius: BorderRadius.circular(12),
                              ),
                              child: Text(
                                p.text,
                                style: const TextStyle(
                                  fontFamily: 'monospace',
                                  fontSize: 12.5,
                                  height: 1.7,
                                ),
                              ),
                            )
                          : Padding(
                              padding: const EdgeInsets.only(bottom: 10),
                              child: Text(
                                p.text,
                                style: const TextStyle(
                                  fontSize: 14,
                                  height: 1.78,
                                ),
                              ),
                            ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
