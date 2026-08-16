import { test, expect } from "@playwright/test";
import { seedAdmittedUser, firstSelectable, seatTitle } from "../helpers/seed";

/**
 * [[ADR-015]] §① 검증 경과에서 확인한 결함이 **좌석 SSE에도 같은 형태로 있었다.**
 *
 * <p>`SeatSseRegistry.subscribe()`도 emitter를 등록만 하고 아무것도 보내지 않았다.
 * 첫 전송 전까지 응답이 커밋되지 않으면 브라우저 `EventSource`가 OPEN으로 전이하지 않고
 * `onopen`이 불리지 않는다. 그러면 `useSeats`의 `es.onopen = () => refresh()`가 발동하지 못한다.
 *
 * <p><b>대기열보다 나쁘다.</b> `useSeats`에는 폴링이 없다(`setInterval` 0건) —
 * 재연결 뒤 <b>자동</b> 복구 경로는 `onopen` 재조회뿐이다. 이 경로가 발동하지 않으면,
 * 사용자가 새로고침하거나 페이지를 다시 열기 전까지 <b>끊긴 사이의 좌석 상태 변경을
 * 화면이 반영하지 못한다.</b> 그동안 이미 선점된 좌석을 고르고 제출 단계에서야 거절당한다.
 *
 * <p>대기열 회귀 테스트(`queue-waiting.spec.ts`)와 같은 구조다 — SSE를 막아 이벤트를
 * 소실시키고, 풀었을 때 재연결만으로 복구되는지 본다. 초기 프레임 전송을 되돌리면 실패한다.
 */
test("SSE가 끊긴 사이 좌석이 선점돼도 재연결하면 좌석맵이 갱신된다", async ({ page }) => {
  const { eventId, queueToken, accessToken } = await seedAdmittedUser(page);
  const map = (await (await page.request.get(`/api/events/${eventId}/seats`)).json()).data;
  const seat = firstSelectable(map);
  const title = seatTitle(seat.grade, seat.col);

  // 1) 좌석 SSE를 막는다. 마운트 시 refresh()는 정상적으로 돌아 초기 좌석맵은 최신이다.
  const sseRoute = "**/sse/events/*/seats";
  await page.route(sseRoute, (route) => route.abort());

  await page.goto(`/events/${eventId}/seats?qt=${queueToken}`);
  await expect(page.getByTitle(title, { exact: true })).toBeEnabled();

  // 2) **브라우저 UI 밖에서** 그 좌석을 선점해 서버 상태만 HELD로 바꾼다.
  //    seat.held 이벤트는 SSE로만 나가고, 연결이 없어 소실된다.
  //
  //    같은 사용자의 토큰을 쓰지만 상관없다 — 이 테스트가 보는 것은 "누가 잡았나"가 아니라
  //    **UI가 모르는 사이 서버 상태가 바뀌었을 때 재연결로 따라잡는가**다. 별도 사용자를
  //    만들면 가입 절차만 늘고 검증 내용은 같다.
  //    (page.request라 브라우저 라우트에 걸리지 않는다)
  const holdRes = await page.request.post(`/api/events/${eventId}/seats/hold`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { seatIds: [seat.id], queueToken },
  });
  expect(holdRes.ok()).toBeTruthy();

  // 3) 화면은 아직 낡은 채다 — 폴링이 없으므로 스스로 갱신될 경로가 없다.
  //    이 단언이 "지금 복구 가능한 경로가 SSE 재연결뿐"이라는 조건을 고정한다.
  await page.waitForTimeout(2000);
  await expect(page.getByTitle(title, { exact: true })).toBeEnabled();

  // 4) SSE를 풀어준다 → 재연결 → (초기 프레임 덕에) onopen → refresh() → 좌석맵 갱신.
  //    seat.held 이벤트는 이미 소실됐으므로 재조회 말고는 알아낼 방법이 없다.
  await page.unroute(sseRoute);

  await expect(page.getByTitle(title, { exact: true })).toBeDisabled({ timeout: 30_000 });
});
