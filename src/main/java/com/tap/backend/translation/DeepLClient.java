package com.tap.backend.translation;

import java.util.List;

public interface DeepLClient {
  String name();

  List<String> translateText(List<String> texts, String targetLang);
}
