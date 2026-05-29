package com.tap.backend.translation;

import java.util.List;
import java.util.Locale;

public class MockDeepLClient implements DeepLClient {
  @Override
  public String name() {
    return "mock-deepl";
  }

  @Override
  public List<String> translateText(List<String> texts, String targetLang) {
    String to = targetLang == null ? "" : targetLang.toUpperCase(Locale.ROOT);
    String prefix = switch (to) {
      case "ZH", "ZH-HANS", "ZH-HANT" -> "（mock译）";
      case "EN" -> "(mock) ";
      default -> "(mock:" + to + ") ";
    };
    return texts.stream().map(t -> prefix + (t == null ? "" : t)).toList();
  }
}
