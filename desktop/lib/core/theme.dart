import 'dart:io';

import 'package:flutter/material.dart';

class LvguoPalette {
  final Color bg;
  final Color surface;
  final Color surface2;
  final Color surface3;
  final Color fill;
  final Color fill2;
  final Color fill3;
  final Color sep;
  final Color text;
  final Color text2;
  final Color text3;
  final Color text4;
  final Color primary;
  final Color primaryOn;
  final Color primarySoft;
  final Color gold;
  final Color danger;
  final Color skeleton;

  const LvguoPalette({
    required this.bg,
    required this.surface,
    required this.surface2,
    required this.surface3,
    required this.fill,
    required this.fill2,
    required this.fill3,
    required this.sep,
    required this.text,
    required this.text2,
    required this.text3,
    required this.text4,
    required this.primary,
    required this.primaryOn,
    required this.primarySoft,
    required this.gold,
    required this.danger,
    required this.skeleton,
  });

  static const light = LvguoPalette(
    bg: Color(0xFFFFFFFF),
    surface: Color(0xFFFFFFFF),
    surface2: Color(0xFFF6F6F6),
    surface3: Color(0xFFECECEC),
    fill: Color(0x0F000000),
    fill2: Color(0x09000000),
    fill3: Color(0x1C000000),
    sep: Color(0x17000000),
    text: Color(0xFF0C0C0D),
    text2: Color(0xFF4B4B4F),
    text3: Color(0xFF8B8B90),
    text4: Color(0xFFB9B9BE),
    primary: Color(0xFF15803D),
    primaryOn: Color(0xFFFFFFFF),
    primarySoft: Color(0x1A15803D),
    gold: Color(0xFFA16207),
    danger: Color(0xFFDC2626),
    skeleton: Color(0xFFF0F0F1),
  );

  static const dark = LvguoPalette(
    bg: Color(0xFF121316),
    surface: Color(0xFF1B1D22),
    surface2: Color(0xFF23262C),
    surface3: Color(0xFF2E323A),
    fill: Color(0x1EFFFFFF),
    fill2: Color(0x12FFFFFF),
    fill3: Color(0x30FFFFFF),
    sep: Color(0x22FFFFFF),
    text: Color(0xFFF8FAFC),
    text2: Color(0xFFD4D7DD),
    text3: Color(0xFF9EA4B0),
    text4: Color(0xFF707784),
    primary: Color(0xFF4ADE80),
    primaryOn: Color(0xFF052E16),
    primarySoft: Color(0x2B4ADE80),
    gold: Color(0xFFFACC15),
    danger: Color(0xFFF87171),
    skeleton: Color(0xFF22252B),
  );

  static LvguoPalette of(BuildContext context) {
    return Theme.of(context).brightness == Brightness.dark ? dark : light;
  }
}

class LvguoTheme {
  static ThemeData buildLight() {
    const p = LvguoPalette.light;
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      scaffoldBackgroundColor: p.bg,
      colorScheme: ColorScheme.light(
        primary: p.primary,
        onPrimary: p.primaryOn,
        surface: p.surface,
        onSurface: p.text,
        error: p.danger,
        outline: p.sep,
        outlineVariant: p.sep,
      ),
      dividerColor: p.sep,
      dividerTheme: DividerThemeData(color: p.sep, thickness: 1, space: 1),
      popupMenuTheme: PopupMenuThemeData(
        color: p.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 12,
        shadowColor: const Color(0x1F000000),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: BorderSide(color: p.sep),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: p.surface2,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        hintStyle: TextStyle(color: p.text4, fontSize: 13.5),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: p.sep),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: p.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: p.danger),
        ),
      ),
      cardColor: p.surface,
      fontFamily: Platform.isWindows ? 'Microsoft YaHei' : null,
      textTheme: TextTheme(
        bodyMedium: TextStyle(
          color: p.text,
          fontSize: 15,
          fontFamilyFallback: const ['PingFang SC', 'Hiragino Sans GB', 'Noto Sans CJK SC', 'Microsoft YaHei', 'sans-serif'],
        ),
        bodySmall: TextStyle(
          color: p.text2,
          fontSize: 13,
          fontFamilyFallback: const ['PingFang SC', 'Hiragino Sans GB', 'Noto Sans CJK SC', 'Microsoft YaHei', 'sans-serif'],
        ),
      ),
    );
  }

  static ThemeData buildDark() {
    const p = LvguoPalette.dark;
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: p.bg,
      colorScheme: ColorScheme.dark(
        primary: p.primary,
        onPrimary: p.primaryOn,
        surface: p.surface,
        onSurface: p.text,
        error: p.danger,
        outline: p.sep,
        outlineVariant: p.sep,
      ),
      dividerColor: p.sep,
      dividerTheme: DividerThemeData(color: p.sep, thickness: 1, space: 1),
      popupMenuTheme: PopupMenuThemeData(
        color: p.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 12,
        shadowColor: const Color(0x40000000),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: BorderSide(color: p.sep),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: p.surface2,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        hintStyle: TextStyle(color: p.text4, fontSize: 13.5),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: p.sep),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: p.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: p.danger),
        ),
      ),
      cardColor: p.surface,
      fontFamily: Platform.isWindows ? 'Microsoft YaHei' : null,
      textTheme: TextTheme(
        bodyMedium: TextStyle(
          color: p.text,
          fontSize: 15,
          fontFamilyFallback: const ['PingFang SC', 'Hiragino Sans GB', 'Noto Sans CJK SC', 'Microsoft YaHei', 'sans-serif'],
        ),
        bodySmall: TextStyle(
          color: p.text2,
          fontSize: 13,
          fontFamilyFallback: const ['PingFang SC', 'Hiragino Sans GB', 'Noto Sans CJK SC', 'Microsoft YaHei', 'sans-serif'],
        ),
      ),
    );
  }
}
