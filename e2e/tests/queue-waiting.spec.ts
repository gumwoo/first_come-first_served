import { test, expect } from "@playwright/test";
import { seedLoggedInUser, issueQueueToken, queueStatus } from "../helpers/seed";
import { fillQueueCapacity, releaseQueueCapacity } from "../helpers/redis";

/**
 * 대기(WAITING) 상태를 **결정적으로** 만드는 기반을 검증한다.
 *
 * <p>지금까지 E2E는 대기 화면을 한 번도 보지 못했다. 정원이 100이라 단일 사용자는 즉시
 * 승격되고, `QUEUE_CAPACITY`를 낮추면 같은 백엔드를 공유하는 다른 E2E가 전부 깨진다
 * (`seedAdmittedUser`에 의존하는 예매·결제·환불). 그래서 `queue:admitcount:<eventId>`를
 * 직접 채워 **그 이벤트만** 정원이 찬 상태로 만든다.
 *
 * <p>이 파일 자체는 기능 회귀가 아니라 <b>다음 테스트를 위한 기반</b>이다 —
 * [[ADR-015]] ①(onopen 재동기화)의 회귀 테스트가 이 위에 올라간다.
 * 그래서 여기서는 두 가지만 본다: <b>정원을 채우면 막히는가</b>, <b>비우면 풀리는가</b>.
 *
 * <p>⚠️ 뒷정리가 선택이 아니다. reclaim은 `queue:admitexp:<eventId>`에 있는 만료 토큰
 * 수만큼만 `DECRBY`하는데 여기서는 admitExp에 아무것도 넣지 않으므로 **스스로 줄어들지
 * 않는다.** 남기면 그 이벤트는 영구히 정원이 찬 상태가 되어 뒤따르는 E2E가 막힌다.
 * 그래서 모든 조작을 `try/finally`로 감싼다.
 */

// 승격 워커 주기 1500ms(application.yml `queue.admit-interval-ms`).
// "막혔다"를 주장하려면 워커가 최소 한 번은 돌고도 승격되지 않았어야 한다.
const ADMIT_INTERVAL_MS = 1500;

test("정원이 차 있으면 승격되지 않고, 비우면 승격된다", async ({ page }) => {
  const { eventId, accessToken } = await seedLoggedInUser(page);

  await fillQueueCapacity(eventId);
  try {
    const token = await issueQueueToken(page, eventId, accessToken);

    // 워커가 두 주기 이상 돌 시간을 준 뒤에도 WAITING이어야 "막혔다"고 말할 수 있다.
    await page.waitForTimeout(ADMIT_INTERVAL_MS * 2);
    expect(await queueStatus(page, token)).toBe("WAITING");

    // 정원을 비우면 다음 주기에 승격된다 — 이 단언이 곧 **뒷정리가 실제로 먹혔다는 증거**다.
    // 여기가 통과하지 않으면 위 admitcount가 남아 뒤 테스트를 막고 있다는 뜻이다.
    await releaseQueueCapacity(eventId);
    await expect
      .poll(async () => queueStatus(page, token), {
        timeout: 15_000,
        intervals: [400, 600, 1000],
      })
      .toBe("ADMITTED");
  } finally {
    // 위에서 이미 비웠어도 한 번 더 — 중간 실패 시 상태가 새는 것을 막는 것이 목적이다.
    await releaseQueueCapacity(eventId);
  }
});

test("정원이 차 있으면 대기 화면에 머문다", async ({ page }) => {
  const { eventId } = await seedLoggedInUser(page);

  await fillQueueCapacity(eventId);
  try {
    // 페이지가 스스로 토큰을 발급한다(useQueue) — 위 테스트와 달리 UI 경로를 그대로 탄다.
    await page.goto(`/events/${eventId}/queue`);

    await expect(page.getByRole("heading", { name: "예매 대기열" })).toBeVisible();
    await expect(page.getByText("현재 대기 순번")).toBeVisible();
    // 좌석 화면으로 자동 이동하지 않는다 — 이동했다면 정원 주입이 먹지 않은 것이다.
    await expect(page).not.toHaveURL(new RegExp(`/events/${eventId}/seats`));
  } finally {
    await releaseQueueCapacity(eventId);
  }
});
