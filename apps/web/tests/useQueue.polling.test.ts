import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FakeEventSource, installFakeEventSource } from "./fakeEventSource";

// api 모듈을 대역으로 바꾼다 — 이 테스트가 보는 것은 네트워크가 아니라 **언제 부르는가**다.
const getQueueStatus = vi.fn();
vi.mock("@/features/queue/api/queue", () => ({
  issueQueueToken: vi.fn(async () => ({ token: "tok-1", status: "WAITING", rank: 5, total: 10 })),
  getQueueStatus: (...args: unknown[]) => getQueueStatus(...args),
  queueSseUrl: (t: string) => `/api/sse/queue/${t}`,
}));
vi.mock("@/features/auth/store/authStore", () => ({
  useAuthStore: (sel: (s: { accessToken: string | null }) => unknown) => sel({ accessToken: "at" }),
}));

import { useQueue } from "@/features/queue/hooks/useQueue";

/** 폴링 호출 시각(가짜 타이머 기준 ms). */
function callTimes() {
  return getQueueStatus.mock.calls.map((_, i) => marks[i]);
}
let marks: number[] = [];

describe("useQueue 폴링", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    installFakeEventSource();
    marks = [];
    getQueueStatus.mockReset();
    getQueueStatus.mockImplementation(async () => {
      marks.push(Date.now());
      return { status: "WAITING", rank: 5, total: 10, etaSeconds: 50 };
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * 토큰 발급 → EventSource 생성까지 진행시킨다.
   *
   * <p>`vi.waitFor`가 아니라 `advanceTimersByTimeAsync(0)`을 쓰는 이유: waitFor는 폴링을
   * 기다리느라 **가짜 시간을 50ms 단위로 밀어** 첫 폴링 간격이 1950ms로 관측된다.
   * 여기서 재려는 것은 주기이지 마운트 지연이 아니므로 시간을 소비하지 않고 마이크로태스크만 비운다.
   */
  async function mountAndOpen() {
    const view = renderHook(() => useQueue(1));
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeEventSource.instances.length).toBe(1);
    const es = FakeEventSource.instances[0];
    es.open(); // onopen → sseHealthy = true
    return { view, es };
  }

  async function advance(ms: number) {
    await vi.advanceTimersByTimeAsync(ms);
  }

  it("SSE가 정상이면 폴링 간격이 2→4→8→15초로 늘어난다", async () => {
    const { es } = await mountAndOpen();
    expect(es.readyState).toBe(FakeEventSource.OPEN);

    // onopen 재조회는 폴링이 아니다 — 기준점을 여기서 다시 잡아 폴링만 본다.
    marks = [];
    const base = Date.now();
    for (const ms of [2_000, 4_000, 8_000, 15_000, 15_000]) await advance(ms);

    const gaps = marks.map((m, i) => (i === 0 ? m - base : m - marks[i - 1]));
    expect(gaps).toEqual([2_000, 4_000, 8_000, 15_000, 15_000]);
  });

  it("상한(15초)을 넘겨 늘어나지 않는다", async () => {
    await mountAndOpen();
    for (const ms of [2_000, 4_000, 8_000, 15_000, 15_000, 15_000, 15_000]) await advance(ms);

    const gaps = marks.slice(1).map((m, i) => m - marks[i]);
    expect(Math.max(...gaps)).toBe(15_000);
  });

  /**
   * **이 PR을 만든 결함의 회귀 가드.**
   * 예전 코드는 onerror에서 pollDelay 값만 되돌리고 **이미 예약된 15초 타이머는 그대로 뒀다.**
   * 그래서 SSE가 죽은 직후인데도 최대 15초 동안 폴링이 오지 않는 구간이 생겼다 —
   * 안전망이 촘촘해져야 할 바로 그 순간에 가장 성겼다.
   */
  it("SSE가 끊기면 이미 예약된 긴 타이머를 버리고 2초 뒤에 폴링한다", async () => {
    const { es } = await mountAndOpen();
    for (const ms of [2_000, 4_000, 8_000, 15_000]) await advance(ms); // 상한까지 백오프

    await advance(1_000); // 15초짜리 예약이 걸린 직후(잔여 14초)
    const before = marks.length;

    es.dropTransient(); // onerror

    await advance(2_000);
    expect(marks.length).toBe(before + 1);
  });

  it("끊긴 뒤에는 2초 간격을 유지한다 — 다시 붙기 전까지 폴링이 유일한 복구 경로다", async () => {
    const { es } = await mountAndOpen();
    for (const ms of [2_000, 4_000, 8_000, 15_000]) await advance(ms);
    es.dropTransient();

    marks = [];
    await advance(2_000);
    await advance(2_000);
    await advance(2_000);

    const gaps = marks.slice(1).map((m, i) => m - marks[i]);
    expect(gaps).toEqual([2_000, 2_000]);
  });

  it("SSE가 다시 붙으면 백오프가 처음부터 다시 오른다", async () => {
    const { es } = await mountAndOpen();
    for (const ms of [2_000, 4_000, 8_000, 15_000]) await advance(ms);
    es.dropTransient();
    await advance(2_000);

    es.open(); // 재연결 성립 → onopen 재조회 1건
    marks = [];
    const base = Date.now();
    await advance(2_000);
    await advance(4_000);

    const gaps = marks.map((m, i) => (i === 0 ? m - base : m - marks[i - 1]));
    expect(gaps).toEqual([2_000, 4_000]);
  });

  it("승격되면 폴링을 멈춘다", async () => {
    const { es } = await mountAndOpen();
    await advance(2_000);
    es.emit("queue.admitted", { redirect: "/events/1/seats" });

    marks = [];
    await advance(60_000);
    expect(marks).toEqual([]);
  });
});
