import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// 하단 4탭 셸. 탭 전환은 go_router의 ShellRoute 위에서 동작한다.
class MainShell extends StatelessWidget {
  const MainShell({super.key, required this.child});

  final Widget child;

  static const _tabs = [
    (path: '/', icon: Icons.home_outlined, active: Icons.home, label: '홈'),
    (
      path: '/rankings',
      icon: Icons.leaderboard_outlined,
      active: Icons.leaderboard,
      label: '랭킹',
    ),
    (
      path: '/guide',
      icon: Icons.menu_book_outlined,
      active: Icons.menu_book,
      label: '가이드',
    ),
    (path: '/my', icon: Icons.person_outline, active: Icons.person, label: '마이'),
  ];

  int _indexOf(BuildContext context) {
    final location = GoRouterState.of(context).matchedLocation;
    final index = _tabs.indexWhere((tab) => tab.path == location);
    return index < 0 ? 0 : index;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: _indexOf(context),
        onDestinationSelected: (index) => context.go(_tabs[index].path),
        destinations: [
          for (final tab in _tabs)
            NavigationDestination(
              icon: Icon(tab.icon),
              selectedIcon: Icon(tab.active),
              label: tab.label,
            ),
        ],
      ),
    );
  }
}
