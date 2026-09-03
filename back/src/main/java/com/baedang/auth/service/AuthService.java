package com.baedang.auth.service;

import com.baedang.auth.dto.AuthResponse;
import com.baedang.auth.dto.AccessTokenResponse;
import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.RefreshTokenRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.security.JwtTokenProvider;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.global.normalizer.DomainNormalizer;
import com.baedang.trading.service.InitialDepositLedgerService;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.AccountStatus;
import com.baedang.user.entity.User;
import com.baedang.user.entity.UserStatus;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final InitialDepositLedgerService initialDepositLedgerService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final BigDecimal initialCash;
    private final Clock clock;

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
                       InitialDepositLedgerService initialDepositLedgerService,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       @Value("${trading.initial-cash}") BigDecimal initialCash,
                       Clock clock) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.initialDepositLedgerService = initialDepositLedgerService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.initialCash = initialCash;
        this.clock = clock;
    }

    /**
     * 회원가입.
     *
     * <p><b>가입과 동시에 1회차 계좌를 개설합니다.</b> 계좌 없이 가입만 된 회원이
     * 생기면 이후 모든 조회에서 null 체크를 해야 합니다. 한 트랜잭션으로 묶어
     * "회원은 반드시 계좌가 있다" 를 불변식으로 만드는 편이 훨씬 단순합니다.
     *
     * <p>초기 지급 원장은 InitialDepositLedgerService에 위임하며 회원·계좌·원장을 같은 트랜잭션으로 저장합니다.
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
            // UNIQUE 위반. 위 검사와 INSERT 사이에 다른 요청이 끼어든 경우입니다.
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED, "동시 가입 충돌");
        }

        Account account = accountRepository.save(
                Account.open(user.getUserId(), 1, initialCash, openedAt));
        initialDepositLedgerService.recordInitialDeposit(
                account.getAccountId(),
                account.getInitialCash(),
                account.getRoundNo(),
                account.getOpenedAt());

        String accessToken = jwtTokenProvider.createAccessToken(user.getUserId());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserId());

        log.info("회원가입 완료 userId={} normalizedEmail={}", user.getUserId(), normalizedEmail);
        return AuthResponse.from(user, account, accessToken, refreshToken);
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
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = DomainNormalizer.email(request.email());

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED, "없는 이메일: " + normalizedEmail));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "비밀번호 불일치 userId=" + user.getUserId());
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "비활성 회원 userId=" + user.getUserId());
        }

        Account account = accountRepository
                .findByUserIdAndStatus(user.getUserId(), AccountStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "userId=" + user.getUserId()));

        String accessToken = jwtTokenProvider.createAccessToken(user.getUserId());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getUserId());

        log.info("로그인 성공 userId={}", user.getUserId());
        return AuthResponse.from(user, account, accessToken, refreshToken);
    }

    @Transactional(readOnly = true)
    public AccessTokenResponse refresh(RefreshTokenRequest request) {
        Long userId;
        try {
            userId = jwtTokenProvider.parseRefreshToken(request.refreshToken());
        } catch (ExpiredJwtException exception) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        userRepository.findByUserIdAndStatus(userId, UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));

        return new AccessTokenResponse(jwtTokenProvider.createAccessToken(userId));
    }

}
