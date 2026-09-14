import 'package:flutter/material.dart';

import '../../widgets/app_widgets.dart';
import 'wiki_terms.dart';
import 'wiki_terms_source.dart';

/// 가이드 탭. 이용 안내와 금융 용어 사전 검색을 제공한다.
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

  final _searchController = TextEditingController();
  List<WikiTerm> _terms = const [];

  @override
  void initState() {
    super.initState();
    widget.termsSource.fetch().then((terms) {
      if (mounted) setState(() => _terms = terms);
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  String get _query => _searchController.text.trim();

  /// 웹 위키 패널과 같은 규칙: 초성만 치면 초성 키로, 아니면 이름·별칭 포함 검색.
  List<WikiTerm> get _results {
    final query = _query.toLowerCase();
    if (query.isEmpty) return _terms;
    final isChosung = RegExp(r'^[ㄱ-ㅎ]+$').hasMatch(query.replaceAll(' ', ''));
    if (isChosung) {
      final cq = query.replaceAll(' ', '');
      return _terms
          .where(
            (t) =>
                t.chosung.contains(cq) ||
                t.aliasChosungs.any((a) => a.contains(cq)),
          )
          .toList(growable: false);
    }
    return _terms
        .where(
          (t) =>
              t.name.toLowerCase().contains(query) ||
              t.aliases.any((a) => a.toLowerCase().contains(query)),
        )
        .toList(growable: false);
  }

  void _openTerm(WikiTerm term) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.6,
        maxChildSize: 0.9,
        builder: (context, scrollController) => SafeArea(
          child: ListView(
            controller: scrollController,
            padding: const EdgeInsets.all(24),
            children: [
              Text(term.name, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              Text(
                term.summary,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 16),
              Text(term.body, style: Theme.of(context).textTheme.bodyLarge),
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final searching = _query.isNotEmpty;
    final results = _results;
    return Column(
      children: [
        SafeArea(
          bottom: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
            child: TextField(
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
          ),
        ),
        Expanded(
          child: searching ? _buildResults(theme, results) : _buildGuide(theme),
        ),
      ],
    );
  }

  Widget _buildResults(ThemeData theme, List<WikiTerm> results) {
    if (results.isEmpty) {
      return PageList(
        children: [
          AppCard(
            child: Text(
              '검색 결과가 없어요. 다른 말로 검색해 보세요.',
              style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
        ],
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
      itemCount: results.length,
      separatorBuilder: (_, _) => const SizedBox(height: 8),
      itemBuilder: (context, index) => _TermCard(
        term: results[index],
        onTap: () => _openTerm(results[index]),
      ),
    );
  }

  Widget _buildGuide(ThemeData theme) {
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
        if (_terms.isNotEmpty) ...[
          Text('용어 사전', style: theme.textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(
            '모르는 단어를 위에서 검색해보세요.',
            style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          for (final term in _terms.take(5)) ...[
            _TermCard(term: term, onTap: () => _openTerm(term)),
            const SizedBox(height: 8),
          ],
          AppCard(
            child: Text(
              '총 ${_terms.length}개 용어를 검색할 수 있어요',
              style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
        ],
      ],
    );
  }
}

class _TermCard extends StatelessWidget {
  const _TermCard({required this.term, required this.onTap});

  final WikiTerm term;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AppCard(
      child: InkWell(
        onTap: onTap,
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(term.name, style: theme.textTheme.bodyLarge),
                  const SizedBox(height: 2),
                  Text(
                    term.summary,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right),
          ],
        ),
      ),
    );
  }
}
