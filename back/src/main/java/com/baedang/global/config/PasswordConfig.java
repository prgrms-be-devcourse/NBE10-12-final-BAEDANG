package com.baedang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시 인코더.
 *
 * <p>Spring Security Starter를 사용하며, 비밀번호 암호화 및 검증을 위한
 * {@link PasswordEncoder} 빈을 등록합니다.
 *
 * <p><b>BCrypt</b>는 의도적으로 느리게 설계되어 브루트포스 공격을 방어하며,
 * Salt를 해시 문자열 내에 포함하여 관리합니다.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
