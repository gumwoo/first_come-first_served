package com.flowticket.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 감사 로그의 토큰 표기. 사후 재구성은 같은 토큰의 승격·회수·이탈을 묶어야 하므로 표기가 안정적이어야 하고,
 * 토큰은 입장 권한과 묶인 값이라 원문이 로그에 남으면 안 된다.
 */
class QueueAuditTest {

    @Test
    void 같은_토큰은_같은_표기다() {
        assertThat(QueueAudit.ref("3f2b-token")).isEqualTo(QueueAudit.ref("3f2b-token"));
    }

    @Test
    void 다른_토큰은_다른_표기다() {
        assertThat(QueueAudit.ref("token-a")).isNotEqualTo(QueueAudit.ref("token-b"));
    }

    @Test
    void 원문을_드러내지_않는다() {
        String token = "c0ffee00-1111-2222-3333-444455556666";

        String ref = QueueAudit.ref(token);

        assertThat(ref).hasSize(16).matches("[0-9a-f]+");
        assertThat(token).doesNotContain(ref);
    }
}
