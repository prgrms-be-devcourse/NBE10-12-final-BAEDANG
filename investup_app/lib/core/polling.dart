import 'dart:async';

import 'package:flutter/widgets.dart';

/// 앱이 포그라운드일 때만 [interval] 주기로 [onTick]을 부르는 타이머.
/// 웹의 useVisiblePolling과 같은 역할 — 백그라운드에선 멈추고 돌아오면 재개한다.
///
/// 최초 마운트 즉시 호출은 하지 않는다 — 호출부가 이미 초기 데이터를 불러온
/// 뒤라 여기선 "그다음부터의 주기적 갱신"만 맡는다. 호출부 State의 dispose에서
/// 반드시 [dispose]를 호출해야 한다.
class PollingTimer {
  PollingTimer({required this.interval, required this.onTick});

  final Duration interval;
  final void Function() onTick;

  Timer? _timer;
  AppLifecycleListener? _lifecycle;

  void start() {
    _lifecycle ??= AppLifecycleListener(
      onStateChange: (_) => _sync(),
    );
    _sync();
  }

  void dispose() {
    _timer?.cancel();
    _timer = null;
    _lifecycle?.dispose();
    _lifecycle = null;
  }

  void _sync() {
    // 테스트 등 lifecycleState를 모르는 환경은 포그라운드로 본다.
    final state = WidgetsBinding.instance.lifecycleState;
    final foreground = state == null || state == AppLifecycleState.resumed;
    if (foreground && _timer == null) {
      _timer = Timer.periodic(interval, (_) => onTick());
    } else if (!foreground) {
      _timer?.cancel();
      _timer = null;
    }
  }
}
