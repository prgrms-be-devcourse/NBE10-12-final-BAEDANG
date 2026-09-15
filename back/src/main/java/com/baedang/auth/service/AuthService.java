package com.baedang.auth.service;

import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.AccessTokenResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.PasswordResetConfirmRequest;
import com.baedang.auth.dto.PasswordForgotRequest;
import com.baedang.auth.dto.RefreshTokenRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.mail.PasswordResetMailSender;
import com.baedang.auth.service.AuthSessionService.Tokens;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.trading.service.LedgerService;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.PasswordResetToken;
import com.baedang.user.entity.User;
import com.baedang.user.entity.UserStatus;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.PasswordResetTokenRepository;
import com.baedang.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    /** 재설정 토큰 원본 길이(바이트). Base64url 인코딩하면 URL에 그대로 실을 수 있는 문자열이 된다. */
    private static final int RESET_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final LedgerService ledgerService;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessions;
    private final PasswordResetMailSender passwordResetMailSender;
    private final BigDecimal initialCash;
    private final String frontendBaseUrl;
    private final Duration passwordResetTokenTtl;
    private final Duration passwordResetRequestCooldown;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 생성자 주입.
     *
     * <p>필드에 {@code @Autowired} 를 붙이지 않는 이유 — 생성자로 받으면
     * <b>final 로 선언할 수 있어서</b> 주입 이후 바뀌지 않는 게 보장되고,
     * 테스트에서 필요한 의존성을 대역으로 전달하여 바로 만들 수 있습니다.
     *
     * <p>생성자가 하나뿐이면 {@code @Autowired} 도 생략할 수 있습니다.
     */
    public AuthService(UserRepository userRepository,
                       AccountRepository accountRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       LedgerService ledgerService,
                       PasswordEncoder passwordEncoder,
                       AuthSessionService sessions,
                       PasswordResetMailSender passwordResetMailSender,
                       @Value("${trading.initial-cash}") BigDecimal initialCash,
                       @Value("${app.frontend-base-url}") String frontendBaseUrl,
                       @Value("${auth.password-reset.token-ttl:30m}") Duration passwordResetTokenTtl,
                       @Value("${auth.password-reset.request-cooldown:1m}") Duration passwordResetRequestCooldown,
                       Clock clock) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.ledgerService = ledgerService;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.passwordResetMailSender = passwordResetMailSender;
        this.initialCash = initialCash;
        this.frontendBaseUrl = frontendBaseUrl;
        this.passwordResetTokenTtl = passwordResetTokenTtl;
        this.passwordResetRequestCooldown = passwordResetRequestCooldown;
        this.clock = clock;
    }

    /**
     * 회원가입.
     *
     * <p><b>가입과 동시에 1회차 계좌를 개설합니다.</b> 계좌 없이 가입만 된 회원이
     * 생기면 이후 모든 조회에서 null 체크를 해야 합니다. 한 트랜잭션으로 묶어
     * "회원은 반드시 계좌가 있다" 를 불변식으로 만드는 편이 훨씬 단순합니다.
     *
     * <p>초기 지급 원장은 LedgerService에 위임하며 회원·계좌·원장을 같은 트랜잭션으로 저장합니다.
     */
    @Transactional
    public AuthResponse signUp(SignUpRequest request) {
        String normalizedEmail = DomainNormalizer.email(request.email());

        // 미리 확인해서 친절한 메시지를 주지만, 이것만 믿지는 않습니다.
        // 동시에 같은 이메일로 두 번 들어오면 둘 다 이 검사를 통과할 수 있습니다.
        // 최종 방어선은 DB 의 UNIQUE 제약이고, 아래 catch 가 그걸 받습니다.
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED, "email=" + normalizedEmail);
        }
        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED, request.nickname());
        }

        OffsetDateTime openedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String encodedPassword = passwordEncoder.encode(request.password());
        User user = User.create(normalizedEmail, encodedPassword, request.nickname());

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("동시 가입으로 UK 충돌: email={}, nickname={}", normalizedEmail, request.nickname(), e);
            ErrorCode errorCode = isConstraint(e, "uq_users_nickname")
                    ? ErrorCode.NICKNAME_DUPLICATED
                    : ErrorCode.EMAIL_DUPLICATED;
            throw new BusinessException(errorCode, "동시 가입 충돌");
        }

        Account account = accountRepository.save(
                Account.open(user.getUserId(), 1, initialCash, openedAt));
        ledgerService.recordInitialDeposit(
                account.getAccountId(),
                account.getInitialCash(),
                account.getRoundNo(),
                account.getOpenedAt());

        Tokens tokens = sessions.create(user.getUserId());

        log.info("회원가입 완료 userId={} normalizedEmail={}", user.getUserId(), normalizedEmail);
        return AuthResponse.from(user, account, tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
    }

    /**
     * 로그인.
     *
     * <p>ACTIVE 회원과 계좌를 확인한 뒤 Access/Refresh 토큰을 발급합니다.
     *
     * <p>이메일이 없을 때와 비밀번호가 틀렸을 때 <b>같은 에러를 던집니다.</b>
     * 구분해서 알려주면 "이 이메일은 가입돼 있다" 는 정보가 새어나가
     * 계정 목록을 수집하는 데 쓰일 수 있습니다.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = DomainNormalizer.email(request.email());

        User user = userRepository.findByEmailForUpdate(normalizedEmail).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "로그인 실패");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "비밀번호 불일치 userId=" + user.getUserId());
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "비활성 회원 userId=" + user.getUserId());
        }

        Account account = accountRepository
                .findByUserIdAndStatus(user.getUserId(), AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        Tokens tokens = sessions.create(user.getUserId());

        log.info("로그인 성공 userId={}", user.getUserId());
        return AuthResponse.from(user, account, tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
    }

    public AccessTokenResponse refresh(RefreshTokenRequest request) {
        Tokens tokens = sessions.rotate(request.refreshToken());
        return new AccessTokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
    }

    public void logout(RefreshTokenRequest request) { sessions.logout(request.refreshToken()); }

    /**
     * 비밀번호 찾기 메일 발송 요청.
     *
     * <p><b>가입 여부와 무관하게 항상 정상 종료합니다</b>(예외를 던지지 않습니다) —
     * 존재하지 않는 이메일에 다른 응답을 주면 "이 이메일은 가입 안 돼 있음"이 외부에
     * 노출되는 계정 열거(account enumeration) 공격이 됩니다(login()과 같은 원칙).
     * 실제 메일 발송은 ACTIVE 회원일 때만 하되, 호출자 입장에서는 항상 같은 결과로
     * 보입니다.
     *
     * <p>새 토큰을 발급하기 전에 그 회원의 이전 미사용 토큰을 전부 무효화합니다 —
     * 재설정 메일을 여러 번 요청했을 때 가장 최근 메일의 링크만 유효하게 합니다.
     *
     * <p><b>쿨다운(리뷰 지적, PR #207)</b> — 이 엔드포인트엔 별도의 rate limit이 없어,
     * 가입된 이메일 주소를 알면 무한정 재설정 메일을 보낼 수 있었습니다(메일 폭탄).
     * 게다가 요청마다 이전 토큰을 무효화하므로, 짧은 간격으로 반복 요청하면 정당한
     * 사용자에게 온 메일의 링크까지 계속 죽어버립니다. 그 회원의 가장 최근 토큰
     * 발급 시각이 쿨다운 이내면 아무것도 하지 않고 조용히 반환합니다 — 이 경우도
     * 호출자에게는 똑같이 성공으로 보입니다(계정 열거 방지 원칙과 동일).
     */
    @Transactional
    public void requestPasswordReset(PasswordForgotRequest request) {
        String normalizedEmail = DomainNormalizer.email(request.email());
        // 같은 회원의 쿨다운 검사·이전 토큰 폐기·발급을 한 잠금 안에서 직렬화합니다.
        User user = userRepository.findByEmailForUpdate(normalizedEmail).orElse(null);

        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            log.info("[password-reset] 가입되지 않았거나 비활성 상태인 이메일이라 메일을 보내지 않습니다(응답은 동일)");
            return;
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        OffsetDateTime lastIssuedAt = passwordResetTokenRepository
                .findFirstByUserIdOrderByCreatedAtDesc(user.getUserId())
                .map(PasswordResetToken::getCreatedAt)
                .orElse(null);
        if (lastIssuedAt != null && now.isBefore(lastIssuedAt.plus(passwordResetRequestCooldown))) {
            log.info("[password-reset] 쿨다운 이내 재요청이라 메일을 보내지 않습니다(응답은 동일) userId={}", user.getUserId());
            return;
        }

        passwordResetTokenRepository.invalidateUnusedByUserId(user.getUserId(), now);

        String rawToken = generateRawToken();
        PasswordResetToken token = PasswordResetToken.issue(
                user.getUserId(), hashToken(rawToken), now.plus(passwordResetTokenTtl));
        passwordResetTokenRepository.save(token);

        String resetUrl = frontendBaseUrl + "/reset-password?token=" + rawToken;
        passwordResetMailSender.sendResetLink(user.getEmail(), resetUrl);
        log.info("비밀번호 재설정 메일 발급 완료 userId={}", user.getUserId());
    }

    /**
     * 이메일 링크의 토큰으로 새 비밀번호를 확정합니다.
     *
     * <p>토큰이 존재하지 않거나 이미 사용됐으면 {@code PASSWORD_RESET_TOKEN_INVALID},
     * 만료됐으면 {@code PASSWORD_RESET_TOKEN_EXPIRED}를 던집니다. 두 실패를 구분해
     * 알려줘도 "그런 이메일이 있는지"는 새어나가지 않습니다 — 토큰은 이미 발급된
     * 뒤라 이 시점엔 이메일 존재 여부가 노출 대상이 아닙니다.
     *
     * <p>비밀번호 변경과 같은 트랜잭션에서 모든 auth_session을 폐기합니다.
     * 커밋 뒤 시작한 인증 검증은 기존 Access도 거절하며, 이미 인증된 요청은 소급 취소하지 않습니다.
     */
    @Transactional
    public void resetPassword(PasswordResetConfirmRequest request) {
        String tokenHash = hashToken(request.token());
        Long userId = passwordResetTokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID, "토큰 없음"));
        // 발급·로그인·RTR과 동일하게 사용자부터 잠그고 토큰 상태를 다시 확인합니다.
        User user = userRepository.findByUserIdAndStatusForUpdate(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID, "회원을 찾을 수 없음"));
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID, "토큰 없음"));

        if (token.isUsed()) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID, "이미 사용된 토큰");
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (token.isExpired(now)) {
            throw new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
        }

        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        sessions.revokeAll(user.getUserId());
        // 방금 쓴 토큰 자신을 포함해, 이 회원의 다른 미사용 토큰(메일을 여러 번
        // 요청했던 경우)까지 한 번에 소비 처리한다.
        passwordResetTokenRepository.invalidateUnusedByUserId(user.getUserId(), now);
        log.info("비밀번호 재설정 완료 userId={}", user.getUserId());
    }

    /** URL에 그대로 실을 수 있는 무작위 토큰(32바이트, Base64url, 패딩 없음). */
    private String generateRawToken() {
        byte[] bytes = new byte[RESET_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 토큰 자체는 저장하지 않고 SHA-256(hex) 해시만 저장·비교한다(비밀번호 해시와 같은 이유). */
    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256은 모든 JVM이 지원을 보장하는 알고리즘이라(MessageDigest 스펙) 실제로는 발생하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", exception);
        }
    }

    private boolean isConstraint(DataIntegrityViolationException exception, String constraintName) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return constraintName.equals(violation.getConstraintName());
            }
            current = current.getCause();
        }
        return false;
    }

}
