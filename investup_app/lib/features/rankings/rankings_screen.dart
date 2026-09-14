import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../core/api/stock_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/models/market_country.dart';
import '../../core/models/ranking.dart';
import '../../core/models/stock_search.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';

/// 랭킹 탭. 국내/해외 상위 종목과 종목 검색을 보여준다.
class RankingsScreen extends StatefulWidget {
  const RankingsScreen({super.key, required this.stocks, required this.session});

  final StockApi stocks;
  final AuthSession session;

  @override
  State<RankingsScreen> createState() => _RankingsScreenState();
}

class _RankingsScreenState extends State<RankingsScreen> {
  MarketCountry _market = MarketCountry.kr;
  Future<RankingPage>? _future;

  final _searchController = TextEditingController();
  Timer? _debounce;
  CancelToken? _searchToken;
  List<StockSearchItem>? _searchResults;
  String? _searchError;
  bool _searching = false;

  /// 찜 토글의 로컬 반영. 서버 응답 stockLikeId 위에 덮어쓴다.
  final Map<int, int?> _likeOverrides = {};
  final Set<int> _likeBusy = {};

  @override
  void initState() {
    super.initState();
    _load();
    _searchController.addListener(_onSearchChanged);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchToken?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  void _load() {
    _future = widget.stocks.getRankings(market: _market, size: 50);
  }

  Future<void> _reload() async {
    final future = widget.stocks.getRankings(market: _market, size: 50);
    setState(() {
      _likeOverrides.clear();
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

  void _onSearchChanged() {
    _debounce?.cancel();
    final query = _searchController.text.trim();
    if (query.isEmpty) {
      _searchToken?.cancel();
      setState(() {
        _searchResults = null;
        _searchError = null;
        _searching = false;
      });
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 400), () => _search(query));
  }

  Future<void> _search(String query) async {
    _searchToken?.cancel();
    final token = CancelToken();
    _searchToken = token;
    setState(() => _searching = true);
    try {
      final results = await widget.stocks.search(
        query: query,
        size: 20,
        cancelToken: token,
      );
      if (!mounted || token.isCancelled) return;
      setState(() {
        _searchResults = results;
        _searchError = null;
        _searching = false;
      });
    } on DioException catch (e) {
      if (e.type == DioExceptionType.cancel) return;
      if (!mounted) return;
      setState(() {
        _searchError = '검색에 실패했어요';
        _searching = false;
      });
    } on ApiException catch (e) {
      if (!mounted || token.isCancelled) return;
      setState(() {
        _searchResults = null;
        _searchError = e.message;
        _searching = false;
      });
    }
  }

  int? _likeIdOf(RankingItem item) =>
      _likeOverrides.containsKey(item.stockId)
          ? _likeOverrides[item.stockId]
          : item.stockLikeId;

  Future<void> _toggleLike(RankingItem item) async {
    if (!widget.session.isAuthenticated) {
      context.go('/login?from=${Uri.encodeComponent('/rankings')}');
      return;
    }
    if (_likeBusy.contains(item.stockId)) return;
    setState(() => _likeBusy.add(item.stockId));
    try {
      final likeId = _likeIdOf(item);
      if (likeId != null) {
        await widget.stocks.unlike(stockLikeId: likeId);
        if (mounted) setState(() => _likeOverrides[item.stockId] = null);
      } else {
        final id = await widget.stocks.like(stockId: item.stockId);
        if (mounted) setState(() => _likeOverrides[item.stockId] = id);
      }
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _likeBusy.remove(item.stockId));
    }
  }

  void _openDetail(RankingItem item) {
    // 랭킹은 탭 선택 시장 기준으로 내려오므로 marketCountry는 현재 탭이다.
    final likeId = _likeIdOf(item);
    context.push(
      '/stocks/${item.symbol}'
      '?marketCountry=${_market.wireValue}'
      '&stockId=${item.stockId}'
      '${likeId != null ? '&likeId=$likeId' : ''}',
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final searching = _searchController.text.trim().isNotEmpty;
    return Column(
      children: [
        SafeArea(
          bottom: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
            child: Column(
              children: [
                TextField(
                  controller: _searchController,
                  decoration: InputDecoration(
                    hintText: '종목명 또는 티커 검색',
                    prefixIcon: const Icon(Icons.search),
                    suffixIcon: searching
                        ? IconButton(
                            icon: const Icon(Icons.close),
                            onPressed: () => _searchController.clear(),
                          )
                        : null,
                  ),
                ),
                if (!searching) ...[
                  const SizedBox(height: 8),
                  Center(
                    child: SizedBox(
                      width: 220,
                      child: PillTabs<MarketCountry>(
                        options: const [
                          (value: MarketCountry.kr, label: '국내 주식'),
                          (value: MarketCountry.us, label: '해외 주식'),
                        ],
                        value: _market,
                        onChanged: _select,
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
        Expanded(
          child: searching
              ? _buildSearchResults(theme)
              : _buildRankings(theme),
        ),
      ],
    );
  }

  Widget _buildSearchResults(ThemeData theme) {
    if (_searching) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_searchError != null) {
      return PageList(
        children: [
          Notice(message: _searchError!, onRetry: _onSearchChanged),
        ],
      );
    }
    final results = _searchResults;
    if (results == null || results.isEmpty) {
      return PageList(
        children: [
          AppCard(
            child: Text(
              '검색 결과가 없어요',
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
      itemBuilder: (context, index) {
        final item = results[index];
        return AppCard(
          child: InkWell(
            onTap: () => context.push(
              '/stocks/${item.symbol}'
              '?marketCountry=${item.marketCountry.wireValue}',
            ),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(item.name, style: theme.textTheme.bodyLarge),
                      Text(
                        '${item.symbol} · '
                        '${item.market ?? item.marketCountry.wireValue} · '
                        '${item.category.wireValue}',
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
      },
    );
  }

  Widget _buildRankings(ThemeData theme) {
    return FutureBuilder<RankingPage>(
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
                  style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
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
            itemBuilder: (context, index) {
              final item = page.items[index];
              return _RankingRow(
                item: item,
                likeId: _likeIdOf(item),
                busy: _likeBusy.contains(item.stockId),
                onTap: () => _openDetail(item),
                onLike: () => _toggleLike(item),
              );
            },
          ),
        );
      },
    );
  }
}

class _RankingRow extends StatelessWidget {
  const _RankingRow({
    required this.item,
    required this.likeId,
    required this.busy,
    required this.onTap,
    required this.onLike,
  });

  final RankingItem item;
  final int? likeId;
  final bool busy;
  final VoidCallback onTap;
  final VoidCallback onLike;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final rateColor = changeColor(item.changeRate, scheme);
    return AppCard(
      child: InkWell(
        onTap: onTap,
        child: Row(
          children: [
            SizedBox(
              width: 32,
              child: Text('${item.rank}', style: theme.textTheme.titleLarge),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.name,
                    style: theme.textTheme.bodyLarge,
                    overflow: TextOverflow.ellipsis,
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
            IconButton(
              onPressed: busy ? null : onLike,
              icon: Icon(
                likeId != null ? Icons.favorite : Icons.favorite_border,
                size: 20,
                color: likeId != null ? const Color(0xFFEF4444) : null,
              ),
              tooltip: '찜하기',
            ),
          ],
        ),
      ),
    );
  }
}
