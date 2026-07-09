import { test, expect } from "@playwright/test";
import { seedAdmittedUser } from "../helpers/seed";

/**
 * P1 예매 해피패스 — 대기열 입장 상태에서 좌석을 골라 선점까지.
 * "순간이동"(seedAdmittedUser)으로 로그인·대기열은 API로 미리 세팅하고,
 * 브라우저는 좌석 선택 화면부터 시작(테스트 독립·속도).
 * 조건 대기 + 시맨틱/콘텐츠 셀렉터만 사용(flaky 방지).
 */
test("좌석 선택 → 선택 완료 → 선점 완료", async ({ page }) => {
  const { eventId, queueToken } = await seedAdmittedUser(page);

  await page.goto(`/events/${eventId}/seats?qt=${queueToken}`);

  // 좌석맵 로드 확인
  await expect(page.getByRole("heading", { name: "좌석 선택" })).toBeVisible();

  // 선택 가능한(비활성=판매완료 제외) 첫 좌석 클릭. 좌석 버튼은 title="<등급> N열 M번".
  const availableSeat = page.locator('button[title*="번"]:not([disabled])').first();
  await expect(availableSeat).toBeVisible();
  await availableSeat.click();

  // 사이드바에 선택/합계 반영
  await expect(page.getByText("총 결제 금액")).toBeVisible();

  // 선택 완료 → 선점
  await page.getByRole("button", { name: "선택 완료" }).click();

  // 선점 완료 화면 도달
  await expect(page.getByRole("heading", { name: "좌석 선점 완료!" })).toBeVisible();
});
