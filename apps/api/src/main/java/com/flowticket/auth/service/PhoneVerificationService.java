package com.flowticket.auth.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 휴대폰 인증. 데모는 외부 SMS 없이 Mock(고정 코드).
 * - 인증번호 TTL 3분, 성공 플래그 TTL 10분, 1시간 5회 발송 제한.
 */
@Slf4j
@Service
public class PhoneVerificationService {

    private static final String CODE = "phone:code:";
    private static final String VERIFIED = "phone:verified:";
    private static final String COUNT = "phone:count:";
    private static final Duration CODE_TTL = Duration.ofMinutes(3);
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(10);
    private static final Duration COUNT_TTL = Duration.ofHours(1);
    private static final int MAX_SEND_PER_HOUR = 5;

    private final StringRedisTemplate redis;
    private final boolean mock;
    private final String mockCode;

    public PhoneVerificationService(StringRedisTemplate redis,
                                    @Value("${auth.phone.mock:true}") boolean mock,
                                    @Value("${auth.phone.mock-code:123456}") String mockCode) {
        this.redis = redis;
        this.mock = mock;
        this.mockCode = mockCode;
    }

    /** 인증번호 발송(Mock). 발송 횟수 제한 적용. */
    public void requestCode(String phone) {
        Long count = redis.opsForValue().increment(COUNT + phone);
        if (count != null && count == 1L) {
            redis.expire(COUNT + phone, COUNT_TTL);
        }
        if (count != null && count > MAX_SEND_PER_HOUR) {
            throw new BusinessException(ErrorCode.PHONE_VERIFICATION_LIMIT_EXCEEDED);
        }
        String code = mock ? mockCode : generateCode();
        redis.opsForValue().set(CODE + phone, code, CODE_TTL);
        log.info("[phone] 인증번호 발송 phone={} (mock={})", phone, mock);
    }

    /** 인증번호 확인 → 성공 플래그 저장. */
    public void verifyCode(String phone, String inputCode) {
        String saved = redis.opsForValue().get(CODE + phone);
        if (saved == null || !saved.equals(inputCode)) {
            throw new BusinessException(ErrorCode.PHONE_VERIFICATION_FAILED);
        }
        redis.delete(CODE + phone);
        redis.opsForValue().set(VERIFIED + phone, "1", VERIFIED_TTL);
    }

    /** 가입 시 인증 성공 여부 확인(미인증이면 예외). */
    public void assertVerified(String phone) {
        if (!Boolean.TRUE.equals(redis.hasKey(VERIFIED + phone))) {
            throw new BusinessException(ErrorCode.PHONE_VERIFICATION_REQUIRED);
        }
    }

    /** 가입 성공 시 성공 플래그 소비(1회성). */
    public void consumeVerification(String phone) {
        redis.delete(VERIFIED + phone);
    }

    private String generateCode() {
        return String.valueOf((int) (Math.random() * 900000) + 100000);
    }
}
