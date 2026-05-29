package com.tap.backend.infra.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesGcmTextEncryptor {
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;

  private final SecretKeySpec secretKey;
  private final SecureRandom secureRandom = new SecureRandom();

  public AesGcmTextEncryptor(
      @Value("${tap.security.pta-credentials.secret:${JWT_SECRET:local_dev_only_pta_credentials_secret_change_me_1234567890}}")
      String secret) {
    this.secretKey = new SecretKeySpec(deriveKey(secret), "AES");
  }

  public String encrypt(String plaintext) {
    if (plaintext == null) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_LENGTH_BYTES];
      secureRandom.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
      buffer.put(iv);
      buffer.put(ciphertext);
      return Base64.getEncoder().encodeToString(buffer.array());
    } catch (Exception e) {
      throw new IllegalStateException("failed to encrypt PTA credential", e);
    }
  }

  public String decrypt(String encodedCiphertext) {
    if (encodedCiphertext == null || encodedCiphertext.isBlank()) {
      return null;
    }
    try {
      byte[] payload = Base64.getDecoder().decode(encodedCiphertext);
      if (payload.length <= IV_LENGTH_BYTES) {
        throw new IllegalArgumentException("invalid ciphertext payload");
      }

      ByteBuffer buffer = ByteBuffer.wrap(payload);
      byte[] iv = new byte[IV_LENGTH_BYTES];
      buffer.get(iv);
      byte[] ciphertext = new byte[buffer.remaining()];
      buffer.get(ciphertext);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] plaintext = cipher.doFinal(ciphertext);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("failed to decrypt PTA credential", e);
    }
  }

  private byte[] deriveKey(String secret) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return digest.digest((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("failed to derive encryption key", e);
    }
  }
}
