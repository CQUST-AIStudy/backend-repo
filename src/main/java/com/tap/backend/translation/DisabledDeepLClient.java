package com.tap.backend.translation;

import java.util.List;

public class DisabledDeepLClient implements DeepLClient {
  @Override
  public String name() {
    return "disabled";
  }

  @Override
  public boolean isEnabled() {
    return false;
  }

  @Override
  public List<String> translateText(List<String> texts, String targetLang) {
    throw new IllegalStateException("翻译功能未启用");
  }
}
