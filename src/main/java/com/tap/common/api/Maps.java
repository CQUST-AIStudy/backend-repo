package com.tap.common.api;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Maps {
  private Maps() {}

  public static Map<String, Object> of(Object... kvs) {
    if (kvs.length % 2 != 0) throw new IllegalArgumentException("odd number of arguments");
    Map<String, Object> m = new LinkedHashMap<>(kvs.length / 2 + 1);
    for (int i = 0; i < kvs.length; i += 2) {
      m.put((String) kvs[i], kvs[i + 1]);
    }
    return m;
  }
}
