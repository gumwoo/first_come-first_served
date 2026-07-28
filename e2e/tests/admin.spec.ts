import { test, expect } from "@playwright/test";
import { loginAsAdmin, seedAdmittedUser } from "../helpers/seed";

/**
 * S07 운영 콘솔 E2E — 권한 경계(가장 중요) + 사이드바 콘솔 스모크.
 * 성공: 관리자는 사이드바로 대시보드·주문·DLQ·알림·공연에 접근하고 등록·설정을 한다.
 * 실패: 비관리자/미로그인은 접근 거부.
 * 관리자 계정은 백엔드가 ADMIN_EMAIL/ADMIN_PASSWORD로 부트스트랩(CI env 주입).
 * 주의: E2E CI 잡엔 Kafka 브로커가 없어 Kafka는 '미연결', DLQ는 빈 상태 — DLQ 재처리 로직은
 *      백엔드 DlqIntegrationTest가 커버한다(브라우저로 실패 메시지 생성은 비실용적).
 */

// --- 성공 케이스 ---

test("관리자는 사이드바로 콘솔 섹션을 이동한다", async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto("/admin");

  // 대시보드
  await expect(page.getByRole("heading", { name: "대시보드" })).toBeVisible();
  await expect(page.getByText("총 공연")).toBeVisible();
  await expect(page.getByText("누적 매출")).toBeVisible();

  // 사이드바 네비로 각 섹션 이동
  await page.getByRole("link", { name: "주문 조회" }).click();
  await expect(page).toHaveURL(/\/admin\/orders$/);
  await expect(page.getByRole("heading", { name: "주문 조회" })).toBeVisible();

  await page.getByRole("link", { name: "DLQ" }).click();
  await expect(page).toHaveURL(/\/admin\/dlq$/);
  await expect(page.getByRole("heading", { name: /DLQ/ })).toBeVisible();

  await page.getByRole("link", { name: "알림" }).click();
  await expect(page).toHaveURL(/\/admin\/alerts$/);
  await expect(page.getByRole("heading", { name: "알림" })).toBeVisible();
});

test("관리자는 공연을 등록한다", async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto("/admin/events");

  const title = `E2E 등록공연 ${Date.now()}`;
  await page.getByRole("button", { name: "공연 등록" }).click();
  await page.getByPlaceholder("예: 뮤지컬 캣츠").fill(title);
  await page.getByRole("button", { name: "저장" }).click();

  await expect(page.getByText(title)).toBeVisible();
});

test("관리자는 DLQ 경고 임계치를 변경한다", async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto("/admin/alerts");

  await page.getByLabel("경고 임계치").fill("5");
  await page.getByRole("button", { name: "저장" }).click();

  // 저장 후 재조회에도 반영(영속)
  await page.reload();
  await expect(page.getByLabel("경고 임계치")).toHaveValue("5");
});

test("관리자 헤더에 '운영' 링크가 보인다", async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto("/"); // 고객 사이트 셸(헤더 존재)

  await expect(page.getByRole("link", { name: "운영" })).toBeVisible();
});

// --- 실패 케이스(권한 경계) ---

test("비관리자(일반회원)는 운영 콘솔 접근 거부", async ({ page }) => {
  await seedAdmittedUser(page); // ROLE_USER 로그인
  await page.goto("/admin");

  await expect(page.getByRole("heading", { name: "접근 권한이 없습니다" })).toBeVisible();
});

test("일반회원 헤더엔 '운영' 링크가 없다", async ({ page }) => {
  await seedAdmittedUser(page);
  await page.goto("/");

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
