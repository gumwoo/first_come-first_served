import { defineConfig } from "vitest/config";
import path from "node:path";

/**
 * 프론트 단위 테스트.
 *
 * <p><b>E2E와 역할이 다르다.</b> E2E는 브라우저 전체 흐름을 보고, 이쪽은 **타이밍 로직**을 본다 —
 * 폴링 백오프의 재예약, SSE `readyState` 분기처럼 E2E로는 관측조차 못 한 것들이 있었다
 * (로컬에서 `page.route`·`setOffline`·서버 강제 종료 셋 다 브라우저에 단절이 전달되지 않았다).
 *
 * <p><b>테스트를 `src` 밖에 두는 이유</b>: 프론트 하네스 규칙 ⑥이 "정의만 하고 안 쓰는 API 함수"를
 * 호출처 유무로 판정한다. 테스트가 `src` 안에 있으면 <b>테스트가 유일한 호출처인 죽은 코드</b>를
 * 하네스가 살아 있다고 오판한다.
 */
export default defineConfig({
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  test: {
    environment: "jsdom",
    include: ["tests/**/*.test.ts"],
    // 타이밍 로직을 fake timer로 돌리므로 실시간 대기가 없다 — 느려지면 그건 버그 신호다.
    testTimeout: 5000,
  },
});
