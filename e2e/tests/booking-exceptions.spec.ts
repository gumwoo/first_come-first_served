import { test, expect } from "@playwright/test";
import { seedAdmittedUser, firstSelectable, seatTitle } from "../helpers/seed";

/**
 * P2 예외 흐름 — 과거 수동으로 잡던 버그를 회귀 테스트로 박제.
 * 조건 대기 + 시맨틱/콘텐츠 셀렉터만 사용(flaky 방지).
 */

// TS-007(FE): 선점 취소가 같은 경로 라우팅만 해서 성공 화면에 머물던 버그.
test("선점 취소 → 좌석 선택 화면으로 복귀", async ({ page }) => {
  const { eventId, queueToken } = await seedAdmittedUser(page);
  await page.goto(`/events/${eventId}/seats?qt=${queueToken}`);

  await page.locator('button[title*="번"]:not([disabled])').first().click();
  await page.getByRole("button", { name: "선택 완료" }).click();
  await expect(page.getByRole("heading", { name: "좌석 선점 완료!" })).toBeVisible();

  await page.getByRole("button", { name: "선점 취소" }).click();

  // 성공 화면이 사라지고 좌석 선택 화면으로 실제 복귀
  await expect(page.getByRole("heading", { name: "좌석 선택" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "좌석 선점 완료!" })).toBeHidden();
});

// TS-006(FE): MAX_PER_USER_EXCEEDED가 화면에 무음 처리되던 버그.
test("1인 최대 초과 시 안내 배너가 뜬다", async ({ page }) => {
  const { eventId, queueToken } = await seedAdmittedUser(page);
  const seatsUrl = `/events/${eventId}/seats?qt=${queueToken}`;

  // 먼저 4매 선점(1인 한도)
  await page.goto(seatsUrl);
  const avail = page.locator('button[title*="번"]:not([disabled])');
  for (let i = 0; i < 4; i++) await avail.nth(i).click();
  await page.getByRole("button", { name: "선택 완료" }).click();
  await expect(page.getByRole("heading", { name: "좌석 선점 완료!" })).toBeVisible();

  // 다시 좌석 화면(4매 보유 상태)에서 1매 더 시도 → 초과 안내
  await page.goto(seatsUrl);
  await page.locator('button[title*="번"]:not([disabled])').first().click();
  await page.getByRole("button", { name: "선택 완료" }).click();

  await expect(page.getByText("1인 구매 가능 수량")).toBeVisible();
});

// 좌석 경합: 고른 좌석을 남이 먼저 가져가도 **매진이 아니다** — 다른 좌석은 남아 있다.
//
// 이 테스트는 원래 "매진 화면으로 이동"을 기대했다. 서버가 부분 선점 실패를 전부
// SOLD_OUT으로 뭉뚱그렸기 때문인데, 그게 곧 버그였다 — 99석이 남았는데도 사용자를
// 매진 화면으로 보내 예매를 포기시켰다. 서버가 SEAT_CONFLICT를 분리하면서 기대 동작을
// "좌석 선택 화면에 남아 안내 + 좌석맵 갱신"으로 바꾼다.
test("고른 좌석을 뺏기면 매진이 아니라 다시 고르게 한다", async ({ page }) => {
  const { eventId, queueToken, accessToken } = await seedAdmittedUser(page);
  const map = (await (await page.request.get(`/api/events/${eventId}/seats`)).json()).data;
  const seat = firstSelectable(map);

  await page.goto(`/events/${eventId}/seats?qt=${queueToken}`);
  await page.getByTitle(seatTitle(seat.grade, seat.col), { exact: true }).click();

  // 제출 직전 그 좌석만 API로 먼저 선점 → 경합 조건 생성(공연 전체는 여전히 여유 있음)
  const holdRes = await page.request.post(`/api/events/${eventId}/seats/hold`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { seatIds: [seat.id], queueToken },
  });
  expect(holdRes.ok()).toBeTruthy();

  await page.getByRole("button", { name: "선택 완료" }).click();

  // 매진 화면으로 튕기지 않는다 — 여기 남아 다시 고를 수 있어야 한다.
  await expect(page.getByText(/다른 좌석을 선택해 주세요/)).toBeVisible();
  await expect(page).not.toHaveURL(new RegExp(`/events/${eventId}/sold-out`));
  // 좌석맵이 갱신돼 뺏긴 좌석은 더 이상 고를 수 없다.
  await expect(page.getByTitle(seatTitle(seat.grade, seat.col), { exact: true })).toBeDisabled();
});
