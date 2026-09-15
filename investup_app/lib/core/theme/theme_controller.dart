import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 라이트/다크 테마 — 웹 `ThemeProvider` 대응.
///
/// 웹과 같은 규칙: 사용자가 고른 값('light'/'dark')을 저장하고, 저장값이
/// 없으면 시스템 설정을 따른다. 키 이름도 웹 localStorage와 같은
/// `trading-theme`을 쓴다.
class ThemeController extends ChangeNotifier {
  /// 저장소 없이 만드는 인메모리 컨트롤러 — 위젯 테스트용.
  ThemeController() : _prefs = null;

  ThemeController._(this._prefs);

  static const _key = 'trading-theme';

  final SharedPreferences? _prefs;
  ThemeMode _mode = ThemeMode.system;

  /// 저장된 선택을 읽어 만든다 — 앱 진입점은 이걸 쓴다.
  static Future<ThemeController> load() async {
    final prefs = await SharedPreferences.getInstance();
    return ThemeController._(prefs).._restore();
  }

  void _restore() {
    _mode = switch (_prefs?.getString(_key)) {
      'light' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => ThemeMode.system,
    };
  }

  ThemeMode get mode => _mode;

  /// 현재 화면에 실제로 적용된 밝기 — 시스템 모드면 플랫폼 설정을 해석한다.
  /// 토글의 활성 쪽 표시에 쓴다.
  bool isDark(BuildContext context) => switch (_mode) {
    ThemeMode.dark => true,
    ThemeMode.light => false,
    ThemeMode.system =>
      MediaQuery.platformBrightnessOf(context) == Brightness.dark,
  };

  void setLight() => _set('light', ThemeMode.light);
  void setDark() => _set('dark', ThemeMode.dark);

  void _set(String stored, ThemeMode mode) {
    if (_mode == mode) return;
    _mode = mode;
    _prefs?.setString(_key, stored);
    notifyListeners();
  }
}
