package com.baedang.user.entity;

import com.baedang.global.entity.BaseEntity;
import jakarta.persistence.*;

/**
 * 회원. {@code users} 테이블 — {@code user} 는 PostgreSQL 예약어라 복수형입니다.
 *
 * <p>1주차에는 인증을 붙이지 않지만 테이블은 지금 만듭니다.
 * {@code account.user_id} 가 이걸 참조하기 때문에 나중에 추가하려면
 * FK 와 데이터를 함께 손봐야 합니다.
 *
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

    /** 화면에 노출되는 이름. 이메일 노출을 피하려고 둡니다. */
    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    /**
     * JPA 전용 기본 생성자.
     *
     * <p>Hibernate 가 리플렉션으로 객체를 만들 때 필요합니다. {@code protected} 인
     * 이유는 <b>우리 코드에서 실수로 빈 객체를 만들지 못하게</b> 막으려는 것입니다 —
     * {@code private} 으로 하면 Hibernate 프록시 생성이 막히므로 protected 가 맞습니다.
     */
    protected User() {
    }

    private User(String email, String passwordHash, String nickname) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.status = UserStatus.ACTIVE;
    }

    /**
     * 새 회원 생성.
     *
     * <p>빌더 대신 정적 팩토리를 쓴 이유 — 인자가 셋뿐이고 순서가 명확합니다.
     * 무엇보다 <b>필수값을 빠뜨릴 수 없습니다.</b> 빌더는 {@code .nickname()} 을
     * 안 부르고 {@code .build()} 해도 컴파일이 되지만, 이건 안 됩니다.
     */
    public static User create(String email, String passwordHash, String nickname) {
        return new User(email, passwordHash, nickname);
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
}
