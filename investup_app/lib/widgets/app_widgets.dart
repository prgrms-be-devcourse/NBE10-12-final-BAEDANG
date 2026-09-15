import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class AppCard extends StatelessWidget {
  const AppCard({super.key, required this.child, this.padding});
  final Widget child;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) => Container(
    padding: padding ?? const EdgeInsets.all(20),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surface,
      border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
      borderRadius: BorderRadius.circular(AppTheme.cardRadius),
    ),
    child: child,
  );
}

class Notice extends StatelessWidget {
  const Notice({super.key, required this.message, this.onRetry});
  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(message, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          if (onRetry != null) ...[
            const SizedBox(height: 12),
            OutlinedButton.icon(onPressed: onRetry, icon: const Icon(Icons.refresh), label: const Text('다시 시도')),
          ],
        ],
      ),
    ),
  );
}

/// 웹 PillTabs의 모바일 판 — 옅은 남색 트랙 위로 진한 남색 pill이
/// 슬라이드하며 선택을 표시하는 세그먼트 탭.
class PillTabs<T> extends StatelessWidget {
  const PillTabs({
    super.key,
    required this.options,
    required this.value,
    required this.onChanged,
  });

  final List<({T value, String label})> options;
  final T value;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final accent = theme.colorScheme.primary;
    final n = options.length;
    final selected =
        options.indexWhere((o) => o.value == value).clamp(0, n - 1);
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: accent.withValues(alpha: 0.06),
        border: Border.all(color: accent.withValues(alpha: 0.12)),
        borderRadius: BorderRadius.circular(999),
      ),
      child: SizedBox(
        height: 36,
        child: Stack(
          children: [
            AnimatedAlign(
              duration: const Duration(milliseconds: 350),
              curve: const Cubic(0.4, 0, 0.2, 1),
              alignment: Alignment(n == 1 ? 0 : -1 + selected * 2 / (n - 1), 0),
              child: FractionallySizedBox(
                widthFactor: 1 / n,
                heightFactor: 1,
                child: Container(
                  decoration: BoxDecoration(
                    color: accent,
                    borderRadius: BorderRadius.circular(999),
                  ),
                ),
              ),
            ),
            Row(
              children: [
                for (final opt in options)
                  Expanded(
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () => onChanged(opt.value),
                      child: Center(
                        child: Text(
                          opt.label,
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                            color: opt.value == value
                                ? theme.colorScheme.onPrimary
                                : theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class PageList extends StatelessWidget {
  const PageList({super.key, required this.children, this.onRefresh});
  final List<Widget> children;
  final Future<void> Function()? onRefresh;

  @override
  Widget build(BuildContext context) {
    final list = ListView(
      // padding을 명시하면 상태바 인셋이 자동으로 더해지지 않으므로 SafeArea로 감싼다.
      padding: AppTheme.pagePadding,
      physics: const AlwaysScrollableScrollPhysics(),
      children: children,
    );
    return SafeArea(
      bottom: false,
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: AppTheme.contentWidth),
          child: onRefresh == null
              ? list
              : RefreshIndicator(onRefresh: onRefresh!, child: list),
        ),
      ),
    );
  }
}
