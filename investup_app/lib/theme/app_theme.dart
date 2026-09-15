import 'package:flutter/material.dart';

abstract final class AppTheme {
  static const pagePadding = EdgeInsets.all(20);
  static const cardRadius = 20.0;
  static const controlRadius = 12.0;
  static const contentWidth = 720.0;
  static const formWidth = 480.0;

  static ThemeData forBrightness(Brightness brightness) {
    final dark = brightness == Brightness.dark;
    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xff0f3868),
      brightness: brightness,
    ).copyWith(
      primary: dark ? const Color(0xff5fa0d6) : const Color(0xff0f3868),
      onPrimary: dark ? const Color(0xff071829) : Colors.white,
      surface: dark ? const Color(0xff1a1a1a) : Colors.white,
      onSurface: dark ? const Color(0xfff2f2f2) : const Color(0xff071829),
      onSurfaceVariant: dark ? const Color(0xff969696) : const Color(0xff1b6da3),
      outlineVariant: dark ? const Color(0xff333333) : const Color(0xffd3e4f1),
      surfaceContainerHighest: dark ? const Color(0xff222222) : const Color(0xffe4eff8),
      error: dark ? const Color(0xffffb4ab) : const Color(0xffb42318),
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: dark ? Colors.black : const Color(0xffeff6fc),
      appBarTheme: AppBarTheme(
        backgroundColor: dark ? Colors.black : const Color(0xffeff6fc),
        foregroundColor: scheme.onSurface,
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 0,
      ),
      textTheme: const TextTheme(
        headlineLarge: TextStyle(fontSize: 32, fontWeight: FontWeight.w700, height: 1.3),
        headlineMedium: TextStyle(fontSize: 28, fontWeight: FontWeight.w700, height: 1.3),
        titleLarge: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, height: 1.4),
        bodyLarge: TextStyle(fontSize: 16, height: 1.5),
        bodyMedium: TextStyle(fontSize: 14, height: 1.5),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surface,
        contentPadding: const EdgeInsets.all(16),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(controlRadius)),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(controlRadius),
          borderSide: BorderSide(color: scheme.outlineVariant),
        ),
        errorMaxLines: 3,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(48, 52),
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(controlRadius)),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: scheme.surface,
        indicatorColor: scheme.surfaceContainerHighest,
      ),
    );
  }
}
