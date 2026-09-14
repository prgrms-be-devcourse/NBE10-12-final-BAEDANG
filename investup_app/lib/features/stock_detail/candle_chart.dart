import 'dart:math';

import 'package:flutter/material.dart';

import '../../core/models/candle.dart';

/// 의존성 없이 그리는 캔들스틱 차트. 상승 빨강·하락 파랑(국내 관례).
class CandleChart extends StatelessWidget {
  const CandleChart({super.key, required this.items, this.height = 200});

  final List<Candle> items;
  final double height;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) {
      return SizedBox(
        height: height,
        child: Center(
          child: Text(
            '표시할 차트 데이터가 없어요',
            style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
        ),
      );
    }
    return SizedBox(
      height: height,
      width: double.infinity,
      child: CustomPaint(
        painter: _CandlePainter(
          items: items,
          labelColor: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}

class _CandlePainter extends CustomPainter {
  _CandlePainter({required this.items, required this.labelColor});

  static const _up = Color(0xFFEF4444);
  static const _down = Color(0xFF3B82F6);
  static const _labelWidth = 52.0;
  static const _padV = 12.0;

  final List<Candle> items;
  final Color labelColor;

  @override
  void paint(Canvas canvas, Size size) {
    final chartWidth = size.width - _labelWidth;
    if (chartWidth <= 0) return;

    var minPrice = double.infinity;
    var maxPrice = double.negativeInfinity;
    final parsed = <({double o, double h, double l, double c})>[];
    for (final c in items) {
      final o = double.tryParse(c.open) ?? 0;
      final h = double.tryParse(c.high) ?? 0;
      final l = double.tryParse(c.low) ?? 0;
      final cl = double.tryParse(c.close) ?? 0;
      parsed.add((o: o, h: h, l: l, c: cl));
      minPrice = min(minPrice, l);
      maxPrice = max(maxPrice, h);
    }
    if (minPrice >= maxPrice) {
      minPrice -= 1;
      maxPrice += 1;
    }

    final chartHeight = size.height - _padV * 2;
    double y(double price) =>
        _padV + (maxPrice - price) / (maxPrice - minPrice) * chartHeight;

    // 가격 눈금: 최고/최저/중간.
    final labelStyle = TextStyle(fontSize: 10, color: labelColor);
    for (final price in [maxPrice, (maxPrice + minPrice) / 2, minPrice]) {
      final tp = TextPainter(
        text: TextSpan(text: _formatPrice(price), style: labelStyle),
        textDirection: TextDirection.ltr,
      )..layout();
      tp.paint(canvas, Offset(chartWidth + 6, y(price) - tp.height / 2));
      final grid = Paint()
        ..color = labelColor.withValues(alpha: 0.15)
        ..strokeWidth = 1;
      canvas.drawLine(
        Offset(0, y(price)),
        Offset(chartWidth, y(price)),
        grid,
      );
    }

    final slot = chartWidth / items.length;
    final bodyWidth = max(1.0, slot * 0.6);
    final wickPaint = Paint()..strokeWidth = 1;
    final bodyPaint = Paint();

    for (var i = 0; i < parsed.length; i++) {
      final c = parsed[i];
      final cx = slot * i + slot / 2;
      final rising = c.c >= c.o;
      final color = rising ? _up : _down;
      wickPaint.color = color;
      canvas.drawLine(Offset(cx, y(c.h)), Offset(cx, y(c.l)), wickPaint);
      bodyPaint.color = color;
      final top = y(max(c.o, c.c));
      final bottom = y(min(c.o, c.c));
      canvas.drawRect(
        Rect.fromLTRB(
          cx - bodyWidth / 2,
          top,
          cx + bodyWidth / 2,
          bottom == top ? top + 1 : bottom,
        ),
        bodyPaint,
      );
    }
  }

  String _formatPrice(double price) {
    if (price >= 1000) return price.toStringAsFixed(0);
    if (price >= 1) return price.toStringAsFixed(2);
    return price.toStringAsFixed(4);
  }

  @override
  bool shouldRepaint(_CandlePainter old) => old.items != items;
}
