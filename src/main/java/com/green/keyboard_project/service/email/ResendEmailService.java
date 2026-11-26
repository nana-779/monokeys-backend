package com.green.keyboard_project.service.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 호출자가 전달한 회원가입 인증번호를 Resend로 발송하는 서비스입니다.
 * 인증번호의 생성·저장과 사용자가 입력한 번호의 대조는 호출하는 쪽에서 담당합니다.
 */
@Service
public class ResendEmailService {
    private static final Pattern RECIPIENT = Pattern.compile(
            "[^\\s\\p{Cntrl}@<>,;]+@[^\\s\\p{Cntrl}@<>,;]+\\.[^\\s\\p{Cntrl}@<>,;]+");
    private static final Pattern CODE_FORMAT = Pattern.compile("[0-9]{6}");
    private static final String SUBJECT = "[Monokeys] 회원가입 이메일 인증번호";

    private final Resend resend;
    private final String sender;

    /**
     * Spring이 서버 실행 환경의 API 키와 발신 주소를 주입합니다.
     * 키를 소스에 넣으면 저장소에서 노출될 수 있고, 발신 주소는 실행 환경마다 달라질 수 있습니다.
     * 설정이 비어 있을 때도 빈을 만들 수 있도록 두 값의 기본값을 빈 문자열로 둡니다.
     */
    @Autowired
    public ResendEmailService(@Value("${RESEND_API_KEY:}") String apiKey,
                              @Value("${EMAIL_FROM:}") String sender) {
        // 두 설정이 있으면 발송에 재사용할 SDK 클라이언트를 한 번 생성합니다.
        // 연결에는 3초, 요청 전체에는 10초의 제한을 둡니다. 설정이 없으면 클라이언트 생성만 건너뜁니다.
        this(apiKey.isBlank() || sender.isBlank() ? null : Resend.builder()
                .apiKey(apiKey.strip())
                .connectTimeout(Duration.ofSeconds(3))
                .callTimeout(Duration.ofSeconds(10))
                .build(), sender.strip());
    }

    // 테스트는 이 생성자로 로컬 모의 서버를 향하는 클라이언트를 주입합니다.
    ResendEmailService(Resend resend, String sender) {
        this.resend = resend;
        this.sender = sender;
    }

    /** 외부 호출 없이 키와 발신 주소가 설정됐는지 확인합니다. */
    public boolean isConfigured() {
        return resend != null;
    }

    /**
     * 전달받은 번호를 메일로 보내고 Resend의 발송 접수 ID를 반환합니다.
     * 접수 ID는 수신함 도착 여부와 별개입니다.
     *
     * @param recipient 인증번호를 받을 단일 이메일 주소
     * @param verificationCode 앞자리 0을 보존하는 6자리 문자열
     * @throws EmailDeliveryException 설정 누락, 발송 형식 오류 또는 발송 결과 확인 실패
     */
    public String sendVerificationCode(String recipient, String verificationCode) {
        if (resend == null) {
            throw new EmailDeliveryException(Failure.NOT_CONFIGURED, "이메일 발송 설정이 준비되지 않았습니다.");
        }
        // 발송 형식만 확인합니다. 사용자 입력과 인증번호를 대조하는 단계는 별도로 처리합니다.
        if (recipient == null || recipient.length() > 50 || !RECIPIENT.matcher(recipient).matches()
                || verificationCode == null || !CODE_FORMAT.matcher(verificationCode).matches()) {
            throw new EmailDeliveryException(Failure.INVALID_MESSAGE, "이메일 주소 또는 인증번호 형식을 확인해 주세요.");
        }

        String deliveryId;
        try {
            // SDK에 발신 주소, 수신 주소, 고정 제목과 인증번호가 들어간 일반 텍스트 본문을 전달합니다.
            var message = CreateEmailOptions.builder()
                    .from(sender)
                    .to(recipient)
                    .subject(SUBJECT)
                    .text("Monokeys 회원가입 인증번호는 " + verificationCode + "입니다.")
                    .build();
            var response = resend.emails().send(message);
            // 응답이 있으면 Resend가 돌려준 접수 ID를 꺼냅니다.
            deliveryId = response == null ? null : response.getId();
        } catch (ResendException | RuntimeException exception) {
            // SDK 예외에 키·수신 주소·인증번호가 섞여 있으면 호출자의 로그에도 남을 수 있습니다.
            // 원본 메시지와 예외를 버리고 호출자가 처리할 수 있는 고정 오류만 던집니다.
            throw sendFailed();
        }
        // 응답을 받았어도 접수 ID가 없으면 발송 성공으로 판단할 근거가 없습니다.
        if (deliveryId == null || deliveryId.isBlank()) {
            throw sendFailed();
        }
        return deliveryId;
    }

    private static EmailDeliveryException sendFailed() {
        return new EmailDeliveryException(Failure.SEND_FAILED, "인증번호 이메일 발송 결과를 확인하지 못했습니다.");
    }

    // 호출자가 오류 문구 대신 이 분류값을 기준으로 후속 동작을 결정합니다.
    public enum Failure {
        NOT_CONFIGURED, INVALID_MESSAGE, SEND_FAILED
    }

    /** 발송 요청의 실패 사유를 민감한 원본 정보 없이 전달합니다. */
    public static final class EmailDeliveryException extends RuntimeException {
        private final Failure failure;

        private EmailDeliveryException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public Failure getFailure() {
            return failure;
        }
    }
}
