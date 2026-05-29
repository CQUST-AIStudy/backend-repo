package com.tap.backend.infra.text;

public final class LanguageHeuristic {
  private LanguageHeuristic() {}

  public static String detect(String text) {
    if (text == null || text.isBlank()) return null;
    int limit = Math.min(text.length(), 2000);
    for (int i = 0; i < limit; i++) {
      char c = text.charAt(i);
      if (c >= 0x4E00 && c <= 0x9FFF) return "ZH";
    }
    return "EN";
  }
}
