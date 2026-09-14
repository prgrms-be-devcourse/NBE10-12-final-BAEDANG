package com.baedang.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 메일 발송.
 *
 * <p><b>SMTP가 설정돼 있지 않아도 백엔드는 정상 기동해야 합니다.</b> Toss/KIS와
 * 같은 이유로, 발송 자체를 {@code mail.enabled}(기본 false)로 게이팅합니다 — 꺼져
 * 있으면 실제 메일을 보내는 대신 재설정 링크를 로그에 남겨서, SMTP 계정이 없는
 * 로컬 개발에서도 "메일에 있어야 할 링크"를 확인하고 흐름을 끝까지 테스트할 수
 * 있게 합니다. {@code mail.enabled=true} + {@code spring.mail.*}(호스트·계정)를
 * 채우면 실제 발송으로 전환됩니다.
 *
 * <p>{@link MailSender}를 생성자에서 직접 받지 않고 {@link ObjectProvider}로 받는
 * 이유 — spring-boot-starter-mail이 클래스패스에 있으면 {@code spring.mail.host}가
 * 비어 있어도 빈 자체는 만들어지지만(비어있는 호스트로), 혹시라도 그 빈이 없는
 * 환경(설정 프로필 차이 등)에서까지 기동이 깨지지 않도록 방어적으로 지연 조회합니다.
 *
 * <p>발송 실패(SMTP 인증 실패, 연결 오류 등)는 호출자에게 전파하지 않고 로그만
 * 남깁니다 — {@code POST /api/auth/password/forgot}는 가입 여부·메일 서버 상태와
 * 무관하게 항상 200을 돌려줘야 한다는 계약(계정 열거 공격 방지) 때문입니다.
 */
@Component
public class PasswordResetMailSender {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailSender.class);

    private final ObjectProvider<MailSender> mailSenderProvider;
    private final boolean enabled;
    private final String from;

    public PasswordResetMailSender(
            ObjectProvider<MailSender> mailSenderProvider,
            @Value("${mail.enabled:false}") boolean enabled,
            @Value("${mail.from:no-reply@investup.local}") String from
    ) {
        this.mailSenderProvider = mailSenderProvider;
        this.enabled = enabled;
        this.from = from;
    }

    public void sendResetLink(String toEmail, String resetUrl) {
        if (!enabled) {
            log.info("[password-reset] mail.enabled=false — 실제 메일 대신 링크만 로그에 남깁니다: to={}, url={}",
                    toEmail, resetUrl);
            return;
        }

        MailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.warn("[password-reset] mail.enabled=true 인데 MailSender 빈을 찾을 수 없어요 — " +
                    "spring.mail.host 등 설정을 확인해주세요. to={}", toEmail);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("[InvestUP] 비밀번호 재설정 안내");
        message.setText(
                "비밀번호 재설정을 요청하셨어요.\n\n" +
                "아래 링크에서 새 비밀번호를 설정해주세요(30분 이내에만 유효):\n" +
                resetUrl + "\n\n" +
                "요청하지 않으셨다면 이 메일은 무시하셔도 괜찮아요."
        );

        try {
            mailSender.send(message);
            log.info("[password-reset] 재설정 메일 발송 완료 to={}", toEmail);
        } catch (MailException exception) {
            log.error("[password-reset] 재설정 메일 발송 실패 to={}", toEmail, exception);
        }
    }
}
