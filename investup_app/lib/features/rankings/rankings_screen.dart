import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api_error.dart';
import '../../core/api/exchange_rate_api.dart';
import '../../core/api/stock_api.dart';
import '../../core/auth/auth_session.dart';
import '../../core/models/exchange_rate.dart';
import '../../core/models/market_country.dart';
import '../../core/models/ranking.dart';
import '../../core/models/stock_search.dart';
import '../../formatters.dart';
import '../../widgets/app_widgets.dart';
import 'exchange_rate_widgets.dart';

/// 랭킹 탭. 국내/해외 상위 종목과 종목 검색을 보여준다.
class RankingsScreen extends StatefulWidget {
  const RankingsScreen({
    super.key,
    required this.stocks,
    required this.session,
    required this.exchangeRates,
  });

  final StockApi stocks;
  final AuthSession session;
  final ExchangeRateApi exchangeRates;

  @override
  State<RankingsScreen> createState() => _RankingsScreenState();
}

class _RankingsScreenState extends State<RankingsScreen> {
  MarketCountry _market = MarketCountry.kr;
  Future<ExchangeRateLatest>? _rateFuture;
  String? _usdKrwRate;

  /// 커서 페이지네이션으로 누적하는 랭킹 목록. 오프셋 방식은 갱신 사이에
  /// 순위가 바뀌어 중복/누락이 생기므로 서버가 주는 nextCursor만 쓴다.
  List<RankingItem> _items = const [];
  String? _nextCursor;
  bool _hasNext = false;
  bool _loading = true;
  bool _loadingMore = false;
  String? _loadError;

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
    _refreshRankings();
    // 환율은 해외 종목 원화 환산 표시에도 쓰므로 화면이 들고 있는다.
    _rateFuture = widget.exchangeRates.getLatest().then((r) {
      if (mounted) setState(() => _usdKrwRate = r.rate);
      return r;
    });
  }

  Future<void> _refreshRankings() async {
    setState(() {
      _loading = true;
      _loadError = null;
      _items = const [];
      _nextCursor = null;
      _hasNext = false;
      _likeOverrides.clear();
    });
    try {
      final page = await widget.stocks.getRankings(market: _market, size: 20);
      if (!mounted) return;
      setState(() {
        _items = page.items;
        _nextCursor = page.nextCursor;
        _hasNext = page.hasNext;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loadError = e.message;
        _loading = false;
      });
    }
  }

  Future<void> _loadMore() async {
    final cursor = _nextCursor;
    if (!_hasNext || cursor == null || _loadingMore) return;
    setState(() => _loadingMore = true);
    try {
      final page = await widget.stocks.getRankings(
        market: _market,
        size: 20,
        cursor: cursor,
      );
      if (!mounted) return;
      setState(() {
        _items = [..._items, ...page.items];
        _nextCursor = page.nextCursor;
        _hasNext = page.hasNext;
        _loadingMore = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _loadingMore = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  Future<void> _reload() async {
    final rateFuture = widget.exchangeRates.getLatest().then((r) {
      if (mounted) setState(() => _usdKrwRate = r.rate);
      return r;
    });
    setState(() => _rateFuture = rateFuture);
    await _refreshRankings();
  }

  void _select(MarketCountry market) {
    if (market == _market) return;
    setState(() => _market = market);
    _refreshRankings();
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
    if (_loadError != null && _items.isEmpty) {
      return PageList(
        onRefresh: _reload,
        children: [Notice(message: _loadError!, onRetry: _reload)],
      );
    }
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_items.isEmpty) {
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
    // index 0은 환율 배너, 마지막은 "더 보기" 버튼이다.
    final total = _items.length + 2;
    return RefreshIndicator(
      onRefresh: _reload,
      child: ListView.separated(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
        itemCount: total,
        separatorBuilder: (_, _) => const SizedBox(height: 8),
        itemBuilder: (context, index) {
          if (index == 0) {
            return ExchangeRateBanner(
              future: _rateFuture,
              api: widget.exchangeRates,
            );
          }
          if (index == total - 1) {
            return _LoadMoreButton(
              hasNext: _hasNext,
              loading: _loadingMore,
              shown: _items.length,
              onTap: _loadMore,
            );
          }
          final item = _items[index - 1];
          return _RankingRow(
            item: item,
            usdKrwRate: _usdKrwRate,
            likeId: _likeIdOf(item),
            busy: _likeBusy.contains(item.stockId),
            onTap: () => _openDetail(item),
            onLike: () => _toggleLike(item),
          );
        },
      ),
    );
  }
}

/// 커서 페이지네이션의 "더 보기" 버튼. in-flight 요청 중엔 막고,
/// hasNext가 꺼지면 안내 문구만 남긴다.
class _LoadMoreButton extends StatelessWidget {
  const _LoadMoreButton({
    required this.hasNext,
    required this.loading,
    required this.shown,
    required this.onTap,
  });

  final bool hasNext;
  final bool loading;
  final int shown;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (!hasNext) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Center(
          child: Text(
            '모든 종목을 불러왔어요',
            style: TextStyle(
              fontSize: 12.5,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      );
    }
    return Column(
      children: [
        Text(
          '$shown개 표시 중',
          style: TextStyle(
            fontSize: 12,
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 6),
        OutlinedButton(
          onPressed: loading ? null : onTap,
          child: Text(loading ? '불러오는 중…' : '더 보기'),
        ),
      ],
    );
  }
}

class _RankingRow extends StatelessWidget {
  const _RankingRow({
    required this.item,
    required this.usdKrwRate,
    required this.likeId,
    required this.busy,
    required this.onTap,
    required this.onLike,
  });

  final RankingItem item;

  /// USD/KRW 최신 환율. 있으면 해외 종목은 원화 환산가를 주 표시로 쓴다.
  final String? usdKrwRate;
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
                if (item.currency == 'USD') ...[
                  // 정책상 거래는 원화라 USD 종목은 환산 원화를 주 표시로 둔다.
                  Text(
                    '${formatNumber(toKrw(item.lastPrice, item.currency, usdKrwRate))}원',
                    style: theme.textTheme.bodyLarge,
                  ),
                  Text(
                    formatMoney(item.lastPrice, item.currency),
                    style: TextStyle(
                      fontSize: 11,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ] else
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
                // 랭킹 선정 기준(거래대금)을 그대로 보여준다 — 왜 이 순서인지 알 수 있게.
                if (item.tradingAmount != null)
                  Text(
                    '거래대금 ${formatKoreanAmount(item.tradingAmount)}',
                    style: TextStyle(
                      fontSize: 11,
                      color: scheme.onSurfaceVariant,
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
