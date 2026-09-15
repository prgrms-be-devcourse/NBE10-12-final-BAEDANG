import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:webview_flutter/webview_flutter.dart';

import '../../core/models/candle.dart';

/// 웹 `CandlestickChart`와 같은 TradingView `lightweight-charts`로 그리는 캔들 +
/// 거래량 차트. 라이브러리는 JS 전용이라 차트 영역만 WebView로 심고, 라이브러리와
/// 브리지 페이지는 `assets/chart/`에 번들로 싣는다(오프라인에서도 동작).
///
/// `items`가 바뀌면 차트를 새로 만들지 않고 `setData`만 보낸다 — 웹이 마운트
/// 시점에 한 번만 `createChart`하는 것과 같은 이유(사용자의 확대·스크롤 상태 유지).
class LwCandleChart extends StatefulWidget {
  const LwCandleChart({super.key, required this.items, this.height = 260});

  final List<Candle> items;
  final double height;

  @override
  State<LwCandleChart> createState() => _LwCandleChartState();
}

class _LwCandleChartState extends State<LwCandleChart> {
  // 위젯 테스트에는 플랫폼 WebView가 없다 — 플레이스홀더로 대체한다.
  static bool get _isTest => Platform.environment.containsKey('FLUTTER_TEST');

  WebViewController? _controller;
  bool _pageReady = false;

  @override
  void initState() {
    super.initState();
    if (_isTest) return;
    final controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0x00000000))
      ..addJavaScriptChannel(
        'ChartChannel',
        onMessageReceived: (_) {},
      )
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (_) => _onPageFinished(),
        ),
      );
    _controller = controller;
    controller.loadFlutterAsset('assets/chart/candle_chart.html');
  }

  Future<void> _onPageFinished() async {
    final controller = _controller;
    if (controller == null) return;
    // 일부 플랫폼에서 에셋 상대경로(script src)가 풀리지 않을 때를 대비해
    // 라이브러리를 직접 주입하는 폴백.
    final hasLib = await controller
        .runJavaScriptReturningResult('typeof LightweightCharts')
        .then((r) => '$r'.contains('object'));
    if (!hasLib) {
      final js = await rootBundle.loadString(
        'assets/chart/lightweight-charts.js',
      );
      await controller.runJavaScript(js);
    }
    if (!mounted) return;
    setState(() => _pageReady = true);
    _pushData();
  }

  @override
  void didUpdateWidget(LwCandleChart oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_pageReady && !identical(widget.items, oldWidget.items)) {
      _pushData();
    }
  }

  /// Color를 CSS `rgb()/rgba()` 문자열로 — 페이지는 파싱 가능한 구체적 색만 받는다.
  static String _css(Color c) {
    final r = (c.r * 255).round();
    final g = (c.g * 255).round();
    final b = (c.b * 255).round();
    return c.a >= 1.0
        ? 'rgb($r, $g, $b)'
        : 'rgba($r, $g, $b, ${c.a.toStringAsFixed(3)})';
  }

  Future<void> _pushData() async {
    final controller = _controller;
    if (controller == null || !mounted) return;
    final scheme = Theme.of(context).colorScheme;
    final items = [
      for (final c in widget.items)
        if (c.at != null)
          {
            'time': c.at!.millisecondsSinceEpoch ~/ 1000,
            'open': num.tryParse(c.open),
            'high': num.tryParse(c.high),
            'low': num.tryParse(c.low),
            'close': num.tryParse(c.close),
            'volume': num.tryParse(c.volume),
          },
    ];
    final payload = jsonEncode({
      'items': items,
      'colors': {
        // 국내 관례(상승 빨강·하락 파랑) — formatters.dart의 changeColor와 같은 값.
        'up': '#EF4444',
        'down': '#3B82F6',
        'ink': _css(scheme.onSurface),
        'line2': _css(scheme.outlineVariant),
      },
    });
    await controller.runJavaScript('window.render(${jsonEncode(payload)})');
  }

  @override
  Widget build(BuildContext context) {
    if (_isTest || _controller == null) {
      return SizedBox(height: widget.height);
    }
    return ClipRect(
      child: SizedBox(
        height: widget.height,
        width: double.infinity,
        child: WebViewWidget(controller: _controller!),
      ),
    );
  }
}
