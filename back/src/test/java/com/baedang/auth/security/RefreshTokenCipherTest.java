package com.baedang.auth.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenCipherTest {
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void 같은_키로_재생성한_인스턴스도_복호화하되_세션과_암호문_변조는_거절한다() {
        UUID session = UUID.randomUUID();
        RefreshTokenCipher cipher = new RefreshTokenCipher(key);
        String encrypted = cipher.encrypt(session, "test-refresh");
        assertThat(encrypted).isNotEqualTo(cipher.encrypt(session, "test-refresh"));
        assertThat(new RefreshTokenCipher(key).decrypt(session, encrypted)).isEqualTo("test-refresh");
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), encrypted)).isInstanceOf(IllegalStateException.class);
        byte[] corrupted = Base64.getDecoder().decode(encrypted);
        corrupted[corrupted.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(session, Base64.getEncoder().encodeToString(corrupted)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 짧은_키는_시작시_거절한다() {
        assertThatThrownBy(() -> new RefreshTokenCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
