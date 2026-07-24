import { test, expect } from "@playwright/test";
import { loginAsAdmin, seedAdmittedUser } from "../helpers/seed";

/**
 * S07 운영 콘솔 E2E — 권한 경계(가장 중요) + 관리자 기능 스모크.
 * 성공: 관리자는 운영 콘솔·공연 관리 접근. 실패: 비관리자/미로그인은 접근 거부.
 * 관리자 계정은 백엔드가 ADMIN_EMAIL/ADMIN_PASSWORD로 부트스트랩(CI env 주입).
 */

// --- 성공 케이스 ---

test("관리자는 운영 콘솔에서 지표·주문·DLQ를 본다", async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto("/admin");

  // 헤더에 관리자 전용 '운영' 링크 노출
  await expect(page.getByRole("link", { name: "운영" })).toBeVisible();

  // 콘솔 구성요소
  await expect(page.getByRole("heading", { name: "운영 콘솔" })).toBeVisible();
  await expect(page.getByText("총 공연")).toBeVisible();
  await expect(page.getByText("누적 매출")).toBeVisible();
  await expect(page.getByRole("heading", { name: "주문 조회" })).toBeVisible();
  await expect(page.getByRole("heading", { name: /DLQ/ })).toBeVisible();
});

test("관리자는 공연을 등록한다", async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto("/admin/events");

  const title = `E2E 등록공연 ${Date.now()}`;
  await page.getByRole("button", { name: "공연 등록" }).click();
  await page.getByPlaceholder("예: 뮤지컬 캣츠").fill(title);
  await page.getByRole("button", { name: "저장" }).click();

  // 목록에 새 공연이 노출(모달 닫힘 후 목록 무효화→갱신)
  await expect(page.getByText(title)).toBeVisible();
});

// --- 실패 케이스(권한 경계) ---

test("비관리자(일반회원)는 운영 콘솔 접근 거부", async ({ page }) => {
  await seedAdmittedUser(page); // ROLE_USER 로그인
  await page.goto("/admin");

  await expect(page.getByRole("heading", { name: "접근 권한이 없습니다" })).toBeVisible();
  // 헤더에도 '운영' 링크가 없어야 함
  await expect(page.getByRole("link", { name: "운영" })).toHaveCount(0);
});

test("미로그인 사용자는 로그인 안내", async ({ page }) => {
  await page.goto("/admin");

  await expect(page.getByRole("heading", { name: "로그인이 필요합니다" })).toBeVisible();
});

test("비관리자는 공연 관리도 접근 거부", async ({ page }) => {
  await seedAdmittedUser(page);
  await page.goto("/admin/events");

  await expect(page.getByRole("heading", { name: "접근 권한이 없습니다" })).toBeVisible();
});
