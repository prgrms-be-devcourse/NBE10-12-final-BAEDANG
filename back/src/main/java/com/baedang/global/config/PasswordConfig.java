package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시 인코더.
 *
 * <p>Spring Security 전체가 아니라 {@code spring-security-crypto} 모듈만 쓰고 있어서
 * 이 빈은 <b>우리가 직접 등록</b>해야 합니다. starter-security 를 넣으면 자동으로
 * 등록되지만, 그러면 필터 체인이 통째로 켜져 모든 요청에 로그인이 걸립니다.
 * 1주차에는 해시 기능만 필요하므로 이 편이 가볍습니다.
 *
 * <p><b>BCrypt 를 쓰는 이유</b> — SHA-256 같은 일반 해시는 너무 빨라서
 * 초당 수억 번 시도할 수 있습니다. BCrypt 는 <b>의도적으로 느리게</b> 설계됐고
 * salt 를 해시 문자열 안에 함께 담아줘서 따로 컬럼을 둘 필요가 없습니다.
 *
 * <p>강도 10 은 기본값이고 한 번 검증에 약 50~100ms 걸립니다. 올리면 두 배씩
 * 느려집니다 — 로그인이 체감될 정도로 느려지므로 함부로 올리지 마세요.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
