import { test, expect } from "@playwright/test";
import { seedLoggedInUser, issueQueueToken, queueStatus } from "../helpers/seed";
import { fillQueueCapacity, releaseQueueCapacity } from "../helpers/redis";

/**
 * 대기(WAITING) 상태를 결정적으로 만드는 기반을 검증한다.
 *
 * 정원이 100이라 단일 사용자는 즉시 승격되고, `QUEUE_CAPACITY`를 낮추면 같은 백엔드를 공유하는 다른 E2E가 전부 깨진다
 * (`seedAdmittedUser`에 의존하는 예매·결제·환불). 그래서 `queue:admitcount:<eventId>`를
 * 직접 채워 그 이벤트만 정원이 찬 상태로 만든다.
 *
 * 첫 테스트는 그 기반을 검증한다(정원을 채우면 막히는가, 비우면 풀리는가).
 * 그 위에 대기 화면의 폴링 회귀(ADR-023 §2 — 대기열 SSE 제거)가 올라간다.
 *
 * 뒷정리가 필수라 모든 조작을 `try/finally`로 감싼다. 남기면 그 이벤트가 영구히 정원이 찬 상태가 된다.
 *
 * fixture는 값을 덮어쓰지 않고 더했다 빼는 방식이다. 이유는 `helpers/redis.ts` 참고.
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

    // 정원을 비우면 다음 주기에 승격된다. 이 단언이 곧 뒷정리가 실제로 먹혔다는 증거다.
    // 여기가 통과하지 않으면 위 admitcount가 남아 뒤 테스트를 막고 있다는 뜻이다.
    await releaseQueueCapacity(eventId);
    await expect
      .poll(async () => queueStatus(page, token), {
        timeout: 15_000,
        intervals: [400, 600, 1000],
      })
      .toBe("ADMITTED");
  } finally {
    // 위에서 이미 비웠어도 한 번 더 부른다. 중간 실패로 그 줄에 못 갔을 때가 목적이다.
    // 두 번 불려도 안전하다(헬퍼가 멱등). DECRBY를 쓰므로 그 보장이 없으면 음수로 내려간다.
    await releaseQueueCapacity(eventId);
  }
});

/**
 * ADR-023 §2 회귀: 대기 화면은 상태 폴링만으로 승격을 알아채고 좌석 선택으로 넘어간다(대기열 SSE 없음).
 *
 * 다음 조회 시각은 서버가 정한다(`retryAfterMs` — 순번이 앞이면 2초). 정원을 비우면 승격 워커가 다음 주기(1.5초)에
 * 승격시키고, 화면은 다음 폴링(2초 + jitter 20% 이내)에 ADMITTED를 받아 좌석 선택으로 이동한다.
 * 대기열 SSE 요청이 하나도 나가지 않아야 한다 — 제거한 경로를 프론트가 다시 부르면 여기서 잡힌다.
 */
test("정원이 비면 폴링만으로 승격을 알아채고 좌석 선택으로 이동한다", async ({ page }) => {
  const { eventId } = await seedLoggedInUser(page);
  const sseRequests: string[] = [];
  page.on("request", (req) => {
    if (req.url().includes("/sse/queue")) sseRequests.push(req.url());
  });

  await fillQueueCapacity(eventId);
  try {
    await page.goto(`/events/${eventId}/queue`);
    await expect(page.getByText("현재 대기 순번")).toBeVisible();

    await releaseQueueCapacity(eventId);

    // 승격 주기(1.5초) + 폴링 간격(2초 + jitter 0.4초 미만) 안쪽이면 충분하다. 여유를 둔다.
    await expect(page).toHaveURL(new RegExp(`/events/${eventId}/seats`), { timeout: 15_000 });
    expect(sseRequests).toEqual([]);
  } finally {
    await releaseQueueCapacity(eventId);
  }
});

test("정원이 차 있으면 대기 화면에 머문다", async ({ page }) => {
  const { eventId } = await seedLoggedInUser(page);

  await fillQueueCapacity(eventId);
  try {
    // 페이지가 스스로 토큰을 발급한다(useQueue): 위 테스트와 달리 UI 경로를 그대로 탄다.
    await page.goto(`/events/${eventId}/queue`);
    await expect(page.getByRole("heading", { name: "예매 대기열" })).toBeVisible();

    // 여기서 끝내면 안 된다. 진입 직후에는 fixture가 먹지 않았어도 WAITING 화면이
    // 잠깐 보이고, 1.5초 뒤 승격돼 좌석으로 넘어간다. 그래도 단언은 통과해버린다.
    // 위 API 테스트와 같은 기준을 적용한다: 워커가 여러 번 돌고도 여전히 대기 화면이어야 한다.
    await page.waitForTimeout(ADMIT_INTERVAL_MS * 2);

    await expect(page).toHaveURL(new RegExp(`/events/${eventId}/queue`));
    await expect(page.getByText("현재 대기 순번")).toBeVisible();
  } finally {
    await releaseQueueCapacity(eventId);
  }
});
