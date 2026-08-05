package com.tap.backend.translation;

import java.util.List;

public interface DeepLClient {
  String name();

  default boolean isEnabled() {
    return true;
  }

  List<String> translateText(List<String> texts, String targetLang);
}
