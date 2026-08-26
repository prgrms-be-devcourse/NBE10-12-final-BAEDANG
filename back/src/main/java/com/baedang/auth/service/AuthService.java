package com.baedang.auth.service;

import com.baedang.auth.dto.LoginRequest;
import com.baedang.auth.dto.SignUpRequest;
import com.baedang.auth.dto.UserResponse;
import com.baedang.global.error.BusinessException;
import com.baedang.global.error.ErrorCode;
import com.baedang.user.entity.Account;
import com.baedang.user.entity.User;
import com.baedang.user.entity.UserStatus;
import com.baedang.user.repository.AccountRepository;
import com.baedang.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final BigDecimal initialCash;

    /**
     * 생성자 주입.
     *
     * <p>필드에 {@code @Autowired} 를 붙이지 않는 이유 — 생성자로 받으면
     * <b>final 로 선언할 수 있어서</b> 주입 이후 바뀌지 않는 게 보장되고,
     * 테스트에서 {@code new AuthService(mock, mock, mock, cash)} 로 바로 만들 수 있습니다.
     *
     * <p>생성자가 하나뿐이면 {@code @Autowired} 도 생략할 수 있습니다.
     */
    public AuthService(UserRepository userRepository,
                       AccountRepository accountRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${trading.initial-cash}") BigDecimal initialCash) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.initialCash = initialCash;
    }

    /**
     * 회원가입.
     *
     * <p><b>가입과 동시에 1회차 계좌를 개설합니다.</b> 계좌 없이 가입만 된 회원이
     * 생기면 이후 모든 조회에서 null 체크를 해야 합니다. 한 트랜잭션으로 묶어
     * "회원은 반드시 계좌가 있다" 를 불변식으로 만드는 편이 훨씬 단순합니다.
     *
     * <p>초기 지급은 원장에도 남겨야 하지만, 원장 기록은 거래 도메인 담당자가
     * 만들 {@code LedgerService} 로 옮기는 게 맞습니다. 지금은 계좌만 만들고
     * TODO 로 남겨둡니다 — 여기서 LedgerEntry 를 직접 만들면 나중에 중복됩니다.
     */
    @Transactional
    public UserResponse signUp(SignUpRequest request) {
        String email = normalizeEmail(request.email());

        // 미리 확인해서 친절한 메시지를 주지만, 이것만 믿지는 않습니다.
        // 동시에 같은 이메일로 두 번 들어오면 둘 다 이 검사를 통과할 수 있습니다.
        // 최종 방어선은 DB 의 UNIQUE 제약이고, 아래 catch 가 그걸 받습니다.
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED, email);
        }
        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED, request.nickname());
        }

        User user = User.create(email, passwordEncoder.encode(request.password()), request.nickname());

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반. 위 검사와 INSERT 사이에 다른 요청이 끼어든 경우입니다.
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED, email);
        }

        accountRepository.save(Account.open(user.getUserId(), 1, initialCash));

        // TODO(거래 도메인): 초기 지급을 ledger_entry 에 INITIAL_DEPOSIT 으로 기록
        //   LedgerEntry.initialDeposit(accountId, initialCash, "모의투자금 지급", openedAt)
        //   원장 검증식 SUM(amount) = cash_balance 가 이 줄이 있어야 성립합니다.

        log.info("회원가입 완료 userId={} email={}", user.getUserId(), email);
        return UserResponse.from(user);
    }

    /**
     * 로그인.
     *
     * <p><b>토큰을 발급하지 않습니다.</b> 비밀번호가 맞는지 확인하고 회원 정보만
     * 돌려줍니다. 2주차에 JWT 를 붙일 때 이 메서드의 반환에 토큰을 추가하세요.
     *
     * <p>이메일이 없을 때와 비밀번호가 틀렸을 때 <b>같은 에러를 던집니다.</b>
     * 구분해서 알려주면 "이 이메일은 가입돼 있다" 는 정보가 새어나가
     * 계정 목록을 수집하는 데 쓰일 수 있습니다.
     */
    @Transactional(readOnly = true)
    public UserResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED, "없는 이메일: " + email));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "비밀번호 불일치 userId=" + user.getUserId());
        }
        if (user.getStatus() == UserStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED, "탈퇴한 회원 userId=" + user.getUserId());
        }

        log.info("로그인 성공 userId={}", user.getUserId());
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "userId=" + userId));
    }

    /**
     * 이메일은 대소문자를 구분하지 않는 게 사용자 기대에 맞습니다.
     * {@code A@b.com} 으로 가입하고 {@code a@b.com} 으로 로그인해도 되어야 합니다.
     * <b>저장할 때와 조회할 때 같은 함수를 타야</b> 하므로 여기 한 곳에 둡니다.
     */
    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
