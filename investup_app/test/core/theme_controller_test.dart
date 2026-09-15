import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:investup_app/core/theme/theme_controller.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('저장값이 없으면 시스템 모드로 시작한다', () async {
    SharedPreferences.setMockInitialValues({});
    final controller = await ThemeController.load();
    expect(controller.mode, ThemeMode.system);
  });

  test('저장된 선택을 복원한다', () async {
    SharedPreferences.setMockInitialValues({'trading-theme': 'dark'});
    final controller = await ThemeController.load();
    expect(controller.mode, ThemeMode.dark);
  });

  test('선택을 저장하고 리스너에 알린다', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final controller = await ThemeController.load();
    var notified = 0;
    controller.addListener(() => notified++);

    controller.setDark();
    expect(controller.mode, ThemeMode.dark);
    expect(prefs.getString('trading-theme'), 'dark');
    expect(notified, 1);

    // 같은 모드를 다시 고르면 알림이 오지 않는다.
    controller.setDark();
    expect(notified, 1);

    controller.setLight();
    expect(controller.mode, ThemeMode.light);
    expect(prefs.getString('trading-theme'), 'light');
  });
}
