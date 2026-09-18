import { test, expect } from "@playwright/test";

/**
 * P0 스모크: Playwright가 돌고 홈이 렌더되는지 확인한다.
 * 헤더 브랜드는 정적 요소라 백엔드 없이도 떠야 한다. 셀렉터는 role+name을 쓴다.
 */
test("홈 페이지가 로드되고 헤더 브랜드가 보인다", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "FlowTicket" })).toBeVisible();
});
