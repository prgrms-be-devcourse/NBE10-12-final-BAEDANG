package com.baedang.user.entity;

import com.baedang.global.entity.BaseEntity;
import jakarta.persistence.*;

/**
 * 회원. {@code users} 테이블 — {@code user} 는 PostgreSQL 예약어라 복수형입니다.
 *
 * <p>JWT subject가 가리키는 회원 식별자를 보관하며, 탈퇴는 상태 전환으로 처리합니다.
 * {@code account.user_id} 가 이 회원을 참조하므로 물리 삭제하지 않습니다.
 * <p><b>setter 가 없습니다.</b> 상태를 바꾸는 건 의미가 분명한 메서드
 * ({@link #changeNickname}, {@link #withdraw})로만 열어둡니다.
 * setter 를 열어두면 어디서 뭐가 바뀌는지 추적이 안 됩니다.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    /** 로그인 아이디를 겸합니다. 저장 전 소문자로 정규화하세요. */
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /** BCrypt 해시. <b>평문이 여기 들어가는 일은 절대 없어야 합니다.</b> */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 화면에 노출되는 이름. 다른 회원과 중복될 수 없습니다. */
    @Column(name = "nickname", nullable = false, unique = true, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    /** 개발/데모용 합성 회원 여부. 프로덕션 리더보드·리포트는 {@code false} 만 집계합니다(#152). */
    @Column(name = "is_seed", nullable = false)
    private boolean seed;

    /**
     * V17에서 추가된 레거시 버전입니다. 재설정 시 증가 동작은 호환성을 위해 유지합니다.
     * 운영 Stateful 인증은 이 값 대신 auth_session의 활성 여부를 확인합니다.
     */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    /**
     * JPA 전용 기본 생성자.
     *
     * <p>Hibernate 가 리플렉션으로 객체를 만들 때 필요합니다. {@code protected} 인
     * 이유는 <b>우리 코드에서 실수로 빈 객체를 만들지 못하게</b> 막으려는 것입니다 —
     * {@code private} 으로 하면 Hibernate 프록시 생성이 막히므로 protected 가 맞습니다.
     */
    protected User() {
    }

    private User(String email, String passwordHash, String nickname, boolean seed) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.status = UserStatus.ACTIVE;
        this.seed = seed;
        this.tokenVersion = 0;
    }

    /**
     * 새 회원 생성.
     *
     * <p>빌더 대신 정적 팩토리를 쓴 이유 — 인자가 셋뿐이고 순서가 명확합니다.
     * 무엇보다 <b>필수값을 빠뜨릴 수 없습니다.</b> 빌더는 {@code .nickname()} 을
     * 안 부르고 {@code .build()} 해도 컴파일이 되지만, 이건 안 됩니다.
     */
    public static User create(String email, String passwordHash, String nickname) {
        return new User(email, passwordHash, nickname, false);
    }

    /** 개발/데모용 합성 회원. {@code is_seed=true} 로 실유저와 분리합니다(#152 시딩). */
    public static User createSeed(String email, String passwordHash, String nickname) {
        return new User(email, passwordHash, nickname, true);
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("비밀번호 hash는 필수입니다");
        }
        this.passwordHash = passwordHash;
    }

    /** 탈퇴는 삭제가 아니라 상태 전환입니다. 원장이 이 회원을 참조하고 있습니다. */
    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
    }

    /**
     * 호환용 버전만 증가시킵니다. 이 호출만으로 Stateful 세션이 폐기되지는 않습니다.
     * 실제 비밀번호 변경·재설정·탈퇴는 서비스에서 AuthSessionService.revokeAll을 호출합니다.
     */
    public void invalidateSessions() {
        this.tokenVersion++;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isSeed() {
        return seed;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }
}
