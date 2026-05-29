package com.tap.backend.infra.crypto;

import java.security.MessageDigest;

public final class Digests {
  private Digests() {}

  public static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] out = digest.digest(bytes);
      StringBuilder sb = new StringBuilder(out.length * 2);
      for (byte b : out) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("sha256 failed", e);
    }
  }
}
