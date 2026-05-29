package com.tap.backend.translation;

import java.util.ArrayList;
import java.util.List;

public final class ParagraphSplitter {
  private ParagraphSplitter() {}

  public static List<String> split(String text) {
    if (text == null) return List.of();
    String t = text.replace("\r\n", "\n").replace('\r', '\n').trim();
    if (t.isBlank()) return List.of();

    String[] parts = t.split("\\n\\s*\\n+");
    List<String> out = new ArrayList<>();
    for (String p : parts) {
      String s = p == null ? "" : p.trim();
      if (!s.isBlank()) out.add(s);
    }
    if (out.size() <= 1 && t.contains("\n")) {
      out.clear();
      for (String line : t.split("\\n+")) {
        String s = line.trim();
        if (!s.isBlank()) out.add(s);
      }
    }
    return out;
  }
}
