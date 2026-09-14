import 'package:flutter/material.dart';

import '../../core/api/api_error.dart';
import '../../core/api/stock_api.dart';
import '../../core/models/market_country.dart';
import '../../core/models/ranking.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';

/// 랭킹 탭. 국내/해외 상위 종목을 보여준다. 찜한 종목은 하트로 표시만 한다
/// (찜 토글·검색·상세는 다음 단계에서 연결한다).
class RankingsScreen extends StatefulWidget {
  const RankingsScreen({super.key, required this.stocks});

  final StockApi stocks;

  @override
  State<RankingsScreen> createState() => _RankingsScreenState();
}

class _RankingsScreenState extends State<RankingsScreen> {
  MarketCountry _market = MarketCountry.kr;
  Future<RankingPage>? _future;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _future = widget.stocks.getRankings(market: _market, size: 50);
  }

  Future<void> _reload() async {
    final future = widget.stocks.getRankings(market: _market, size: 50);
    setState(() {
      _future = future;
    });
    try {
      await future;
    } on ApiException {
      // FutureBuilder가 오류 상태를 그린다.
    }
  }

  void _select(MarketCountry market) {
    if (market == _market) return;
    setState(() {
      _market = market;
      _load();
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: [
        SafeArea(
          bottom: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
            child: SegmentedButton<MarketCountry>(
              segments: const [
                ButtonSegment(
                  value: MarketCountry.kr,
                  label: Text('국내 주식'),
                ),
                ButtonSegment(
                  value: MarketCountry.us,
                  label: Text('해외 주식'),
                ),
              ],
              selected: {_market},
              onSelectionChanged: (set) => _select(set.first),
            ),
          ),
        ),
        Expanded(
          child: FutureBuilder<RankingPage>(
            future: _future,
            builder: (context, snapshot) {
              if (snapshot.hasError) {
                return PageList(
                  onRefresh: _reload,
                  children: [
                    Notice(
                      message: snapshot.error is ApiException
                          ? (snapshot.error! as ApiException).message
                          : '랭킹을 불러오지 못했어요',
                      onRetry: _reload,
                    ),
                  ],
                );
              }
              final page = snapshot.data;
              if (page == null) {
                return const Center(child: CircularProgressIndicator());
              }
              if (page.items.isEmpty) {
                return PageList(
                  onRefresh: _reload,
                  children: [
                    AppCard(
                      child: Text(
                        '표시할 종목이 없어요',
                        style: TextStyle(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                  ],
                );
              }
              return RefreshIndicator(
                onRefresh: _reload,
                child: ListView.separated(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
                  itemCount: page.items.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, index) =>
                      _RankingRow(item: page.items[index]),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _RankingRow extends StatelessWidget {
  const _RankingRow({required this.item});

  final RankingItem item;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final rateColor = changeColor(item.changeRate, scheme);
    return AppCard(
      child: Row(
        children: [
          SizedBox(
            width: 32,
            child: Text(
              '${item.rank}',
              style: theme.textTheme.titleLarge,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(
                        item.name,
                        style: theme.textTheme.bodyLarge,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (item.stockLikeId != null) ...[
                      const SizedBox(width: 4),
                      const Icon(
                        Icons.favorite,
                        size: 14,
                        color: Color(0xFFEF4444),
                      ),
                    ],
                  ],
                ),
                Text(
                  '${item.symbol} · ${item.market}',
                  style: TextStyle(
                    fontSize: 12,
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                formatMoney(item.lastPrice, item.currency),
                style: theme.textTheme.bodyLarge,
              ),
              Text(
                formatRate(item.changeRate),
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: rateColor,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
