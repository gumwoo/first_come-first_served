import { test, expect } from "@playwright/test";
import { seedOrder } from "../helpers/seed";

/**
 * P4 결제 해피패스 — Mock 게이트웨이(외부 연동 없음, 결정론).
 * 좌석 선점·주문 생성은 API로 순간이동하고, 브라우저는 결제 화면부터 결제→완료를 검증.
 */

test("카드 결제 → 예매 완료(QR)", async ({ page }) => {
  const { orderId } = await seedOrder(page);

  await page.goto(`/orders/${orderId}/pay`);
  await expect(page.getByRole("heading", { name: "결제" })).toBeVisible();

  // 카드 탭이 기본 → 결제하기(금액 포함 라벨)
  await page.getByRole("button", { name: /결제하기/ }).click();

  await expect(page).toHaveURL(new RegExp(`/orders/${orderId}/complete`));
  await expect(page.getByRole("heading", { name: "예매가 완료되었습니다" })).toBeVisible();
});

test("무통장 → 가상계좌 발급 → 입금 확인 → 완료", async ({ page }) => {
  const { orderId } = await seedOrder(page);

  await page.goto(`/orders/${orderId}/pay`);
  await page.getByRole("button", { name: "무통장입금" }).click();
  await page.getByRole("button", { name: /결제하기/ }).click();

  // 입금 대기 → 개발용 입금 확인
  await expect(page.getByText("입금 대기")).toBeVisible();
  await page.getByRole("button", { name: /입금 확인/ }).click();

  await expect(page).toHaveURL(new RegExp(`/orders/${orderId}/complete`));
  await expect(page.getByRole("heading", { name: "예매가 완료되었습니다" })).toBeVisible();
});
