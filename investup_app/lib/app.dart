import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:go_router/go_router.dart';

import 'core/api/account_api.dart';
import 'core/api/auth_api.dart';
import 'core/api/exchange_rate_api.dart';
import 'core/api/market_api.dart';
import 'core/api/order_api.dart';
import 'core/api/stock_api.dart';
import 'core/auth/auth_session.dart';
import 'core/models/market_country.dart';
import 'features/auth/forgot_password_screen.dart';
import 'features/auth/login_screen.dart';
import 'features/auth/signup_screen.dart';
import 'features/guide/guide_screen.dart';
import 'features/guide/wiki_terms_source.dart';
import 'features/home/home_screen.dart';
import 'features/my/my_screen.dart';
import 'features/rankings/rankings_screen.dart';
import 'features/shell/main_shell.dart';
import 'features/splash/splash_screen.dart';
import 'features/stock_detail/stock_detail_screen.dart';
import 'theme/app_theme.dart';

/// 앱 루트. 라우팅과 테마만 담고, 인증 상태에 따른 화면 전환은
/// [AuthSession]의 알림을 받는 redirect가 담당한다.
class InvestUpApp extends StatefulWidget {
  const InvestUpApp({
    super.key,
    required this.session,
    required this.auth,
    required this.market,
    required this.stocks,
    required this.account,
    required this.orders,
    required this.exchangeRates,
    required this.wiki,
  });

  final AuthSession session;
  final AuthApi auth;
  final MarketApi market;
  final StockApi stocks;
  final AccountApi account;
  final OrderApi orders;
  final ExchangeRateApi exchangeRates;
  final WikiTermsSource wiki;

  @override
  State<InvestUpApp> createState() => _InvestUpAppState();
}

class _InvestUpAppState extends State<InvestUpApp> {
  late final GoRouter _router = _buildRouter();

  GoRouter _buildRouter() {
    final session = widget.session;
    return GoRouter(
      initialLocation: '/splash',
      // 로그인/로그아웃/복원 완료마다 redirect를 다시 평가한다.
      refreshListenable: session,
      redirect: (context, state) {
        final status = session.status;
        final location = state.matchedLocation;
        if (status == AuthStatus.unknown || status == AuthStatus.unavailable) {
          return location == '/splash' ? null : '/splash';
        }
        // 웹과 같은 원칙: 둘러보기는 로그인 없이 열어두고, 계좌·거래처럼
        // 인증이 필요한 기능을 쓸 때만 로그인으로 유도한다.
        // 복원이 끝났으면 스플래시에서 빠져나와 홈으로 간다.
        if (location == '/splash') return '/';
        final onAuthPage =
            location == '/login' ||
            location == '/signup' ||
            location == '/forgot-password';
        if (status == AuthStatus.authenticated && onAuthPage) {
          final from = state.uri.queryParameters['from'];
          return from != null && from.startsWith('/') ? from : '/';
        }
        return null;
      },
      routes: [
        GoRoute(
          path: '/splash',
          builder: (context, state) => SplashScreen(session: session),
        ),
        GoRoute(
          path: '/login',
          builder: (context, state) => LoginScreen(session: session),
        ),
        GoRoute(
          path: '/signup',
          builder: (context, state) => SignupScreen(session: session),
        ),
        GoRoute(
          path: '/forgot-password',
          builder: (context, state) =>
              ForgotPasswordScreen(auth: widget.auth),
        ),
        GoRoute(
          path: '/stocks/:symbol',
          builder: (context, state) {
            final params = state.uri.queryParameters;
            return StockDetailScreen(
              symbol: state.pathParameters['symbol']!,
              marketCountry: MarketCountry.fromWire(params['marketCountry']),
              stocks: widget.stocks,
              session: session,
              orders: widget.orders,
              account: widget.account,
              stockId: int.tryParse(params['stockId'] ?? ''),
              stockLikeId: int.tryParse(params['likeId'] ?? ''),
            );
          },
        ),
        ShellRoute(
          builder: (context, state, child) => MainShell(child: child),
          routes: [
            // 하단 탭 사이 전환에는 페이지 슬라이드를 쓰지 않는다.
            // 기본 MaterialPage 전환이 이전 탭 잔상을 남긴다.
            GoRoute(
              path: '/',
              pageBuilder: (context, state) => NoTransitionPage(
                child: HomeScreen(session: session, market: widget.market),
              ),
            ),
            GoRoute(
              path: '/rankings',
              pageBuilder: (context, state) => NoTransitionPage(
                child: RankingsScreen(
                stocks: widget.stocks,
                session: session,
                exchangeRates: widget.exchangeRates,
                market: widget.market,
              ),
              ),
            ),
            GoRoute(
              path: '/guide',
              pageBuilder: (context, state) => NoTransitionPage(
                child: GuideScreen(termsSource: widget.wiki),
              ),
            ),
            GoRoute(
              path: '/my',
              pageBuilder: (context, state) => NoTransitionPage(
                child: MyScreen(
                  session: session,
                  stocks: widget.stocks,
                  account: widget.account,
                  orders: widget.orders,
                  exchangeRates: widget.exchangeRates,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'InvestUP',
      theme: AppTheme.forBrightness(Brightness.light),
      darkTheme: AppTheme.forBrightness(Brightness.dark),
      routerConfig: _router,
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: const [Locale('ko'), Locale('en')],
      locale: const Locale('ko'),
    );
  }
}
