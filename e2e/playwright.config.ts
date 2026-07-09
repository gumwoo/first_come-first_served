import { defineConfig, devices } from "@playwright/test";

/**
 * FlowTicket E2E — Critical User Flow만 검증. 규칙: docs/testing/e2e-rules.md
 * P0: 스모크(홈 로드). 이후 P1(예매 해피패스)/P2(예외 흐름)로 확장.
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: true,
  forbidOnly: !!process.env.CI, // CI에선 test.only 금지(실수 방지)
  retries: 0,                   // flaky는 retry로 숨기지 않고 번인(--repeat-each)으로 잡는다
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",      // 실패 시 CCTV(trace) — 에이전트 디버깅용
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  // 로컬은 이미 뜬 dev 서버 재사용, 없으면 기동. (CI는 풀스택 기동 후 E2E_BASE_URL 주입)
  webServer: {
    command: "npm --prefix ../apps/web run dev",
    url: "http://localhost:3000",
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
