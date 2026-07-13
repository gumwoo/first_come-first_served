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

// FE는 idempotencyKey를 랜덤 생성해 UI로 Mock 거절을 강제할 수 없으므로,
// 결제 응답을 경계에서 가로채(route) 예외를 결정론화한다(e2e-rules 모킹 정책).
test("결제 거절 → 실패 화면 + 재시도 복귀", async ({ page }) => {
  const { orderId } = await seedOrder(page);
  await page.route(`**/api/orders/${orderId}/payments`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: { paymentId: 1, paymentStatus: "FAILED", orderStatus: "PENDING", vbankAccount: null, depositDeadline: null },
      }),
    })
  );

  await page.goto(`/orders/${orderId}/pay`);
  await page.getByRole("button", { name: /결제하기/ }).click();

  await expect(page).toHaveURL(new RegExp(`/orders/${orderId}/failed`));
  await expect(page.getByRole("heading", { name: "예매를 완료하지 못했습니다" })).toBeVisible();

  // 재시도 → 결제 화면 복귀
  await page.getByRole("button", { name: "다시 시도" }).click();
  await expect(page).toHaveURL(new RegExp(`/orders/${orderId}/pay`));
  await expect(page.getByRole("heading", { name: "결제" })).toBeVisible();
});

test("결제 제한시간 만료 → 만료 안내", async ({ page }) => {
  const { orderId, eventId } = await seedOrder(page);
  const past = new Date(Date.now() - 60_000).toISOString();
  // 주문 조회를 만료된 상태로 가로챔 → 타이머 0 → 만료 UI
  await page.route(`**/api/orders/${orderId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        data: { orderId, eventId, status: "PENDING", amount: 30000, expiresAt: past, items: [] },
      }),
    })
  );

  await page.goto(`/orders/${orderId}/pay`);
  await expect(page.getByRole("heading", { name: "결제 시간이 만료되었습니다" })).toBeVisible();
});
