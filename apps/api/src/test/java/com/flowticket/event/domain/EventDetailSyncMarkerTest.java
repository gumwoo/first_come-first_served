package com.flowticket.event.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * "상세를 받았는가"의 판정 기준을 고정한다.
 *
 * <p><b>왜 이 테스트가 있나</b>: 기동 스크립트가 외부에서 진행 상황을 볼 수단이 없어
 * {@code runningTime}이 비었는지로 대신 셌다. <b>그 대리값은 틀렸다</b> —
 * {@link Event#updateDetail}은 상세 응답에 그 필드가 없어도 {@code detailSyncedAt}을 찍는다.
 * 그래서 "상세는 받았는데 runningTime만 없는 공연"이 정상적으로 존재하고, 대리값으로 세면
 * 그것들이 영원히 "미수집"으로 남아 <b>완료 판정이 되지 않는다.</b>
 *
 * <p>실제로 실기동에서 53건이 그 상태로 남아 루프가 헛돌았다.
 */
class EventDetailSyncMarkerTest {

    @Test
    @DisplayName("runningTime이 비어도 상세를 받았으면 detailSyncedAt이 찍힌다")
    void 상세_수집_표시는_runningTime과_무관하다() {
        Event e = new Event();

        // KOPIS 상세에 공연시간이 없는 경우 — 나머지는 정상 수집됐다.
        e.updateDetail(null, "만 12세 이상", "R석 90,000원", "출연진", "줄거리", "일정");

        assertThat(e.getDetailSyncedAt())
                .as("상세 호출이 성공했으므로 수집 표시는 찍혀야 한다 — 이게 없으면 매 회차 같은 공연을 다시 부른다")
                .isNotNull();
        assertThat(e.getRunningTime())
                .as("빈 값으로 덮어쓰지 않는다(목록에서 받은 값을 지우면 안 되므로)")
                .isNull();
    }

    @Test
    @DisplayName("빈 문자열도 덮어쓰지 않는다")
    void 빈_문자열은_무시된다() {
        Event e = new Event();
        ReflectionTestUtils.setField(e, "runningTime", "120분");

        e.updateDetail("   ", null, null, null, null, null);

        assertThat(e.getRunningTime()).isEqualTo("120분");
        assertThat(e.getDetailSyncedAt()).isNotNull();
    }
}
