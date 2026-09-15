package com.baedang.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** 유예 동안 동일한 후속 토큰을 복구합니다. 키는 DB 및 JWT 서명 키와 분리합니다. */
@Component
public class RefreshTokenCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenCipher(@Value("${auth.session.encryption-key}") String encodedKey) {
        byte[] bytes = Base64.getDecoder().decode(encodedKey);
        if (bytes.length != 32) throw new IllegalArgumentException("세션 암호화 키는 32바이트여야 합니다");
        key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(UUID sessionId, String token) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, sessionId, nonce, token.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                .put(nonce).put(encrypted).array());
    }

    public String decrypt(UUID sessionId, String value) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(value));
        byte[] nonce = new byte[12];
        buffer.get(nonce);
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return new String(crypt(Cipher.DECRYPT_MODE, sessionId, nonce, data), StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, UUID sessionId, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(sessionId.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("세션 토큰 암호화 처리 실패", exception);
        }
    }
}
