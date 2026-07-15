import { test, expect } from "@playwright/test";
import { seedPaidOrder, seedOrder, seedAdmittedUser } from "../helpers/seed";

/**
 * S06 취소·환불 E2E — Mock 게이트웨이(결정론). 시드 이벤트 공연일이 D+30이라 환불은 전액 허용.
 * 결제까지 API로 순간이동하고, 브라우저는 마이페이지→환불→완료를 검증.
 */

test("예매 취소 → 환불 완료", async ({ page }) => {
  const { orderId } = await seedPaidOrder(page);

  await page.goto("/me/orders");
  // 결제완료 배지 + 예매 항목
  await expect(page.getByText("결제완료").first()).toBeVisible();
  await page.getByText("E2E 테스트 공연").first().click();

  // 상세에서 취소 진입
  await page.getByRole("button", { name: "예매 취소" }).click();
  await expect(page).toHaveURL(new RegExp(`/me/orders/${orderId}/refund`));

  // 환불 예정 금액(D-30 → 전액) + 동의 후 신청
  await expect(page.getByText("환불 예정 금액")).toBeVisible();
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "환불 신청" }).click();

  // 확정 모달 → 취소 확정 → 완료 화면(환불 예정 금액 안내)
  await page.getByRole("button", { name: "취소 확정" }).click();
  await expect(page.getByRole("heading", { name: "예매를 취소했습니다" })).toBeVisible();

  // 목록 반영: 취소 탭엔 "환불완료"로 노출, 예정 탭엔 없음
  await page.goto("/me/orders");
  await expect(page.getByText("환불완료").first()).toBeVisible();
  await page.getByRole("button", { name: "취소" }).click();
  await expect(page.getByText("환불완료").first()).toBeVisible();
  await page.getByRole("button", { name: "예정" }).click();
  await expect(page.getByText("예매 내역이 없습니다")).toBeVisible();
});

test("예매 없는 사용자는 빈 상태", async ({ page }) => {
  await seedAdmittedUser(page); // 로그인만(주문 없음)

  await page.goto("/me/orders");

  await expect(page.getByText("예매 내역이 없습니다")).toBeVisible();
});

test("결제 전 예매는 취소 불가 안내", async ({ page }) => {
  const { orderId } = await seedOrder(page); // PENDING(미결제)

  await page.goto(`/me/orders/${orderId}/refund`);

  await expect(page.getByText("취소할 수 없는 예매입니다")).toBeVisible();
  await expect(page.getByRole("button", { name: "환불 신청" })).toHaveCount(0);
});
