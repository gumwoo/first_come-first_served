package com.flowticket.global.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 예매 경로 요청의 DB 사용을 이 이름으로 잰다(BookingDbMetrics). 컨트롤러 메서드에 단다.
 *
 * 값은 고정된 몇 개만 쓴다(hold·release·order·pay·confirm). 지표 태그가 되므로 경로 변수나
 * 사용자 입력을 넣으면 시계열이 끝없이 는다(카디널리티).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BookingDbOp {

    String value();
}
