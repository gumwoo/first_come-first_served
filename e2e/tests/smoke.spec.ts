import { test, expect } from "@playwright/test";

/**
 * P0 스모크: 로봇(Playwright)이 실제로 도는지 + 홈이 렌더되는지 확인.
 * 헤더 브랜드는 정적 요소라 백엔드 없이도 떠야 한다(순수 프론트 로드 검증).
 * 셀렉터는 role+name(시맨틱) — 디자인 변경에 견고.
 */
test("홈 페이지가 로드되고 헤더 브랜드가 보인다", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "FlowTicket" })).toBeVisible();
});
