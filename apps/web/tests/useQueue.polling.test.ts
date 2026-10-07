import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/apiClient";

// api 모듈을 대역으로 바꾼다. 이 테스트는 호출 시점과 phase만 본다.
const issueQueueToken = vi.fn();
const getQueueStatus = vi.fn();
vi.mock("@/features/queue/api/queue", () => ({
  issueQueueToken: (...args: unknown[]) => issueQueueToken(...args),
  getQueueStatus: (...args: unknown[]) => getQueueStatus(...args),
  leaveQueue: vi.fn(),
}));
vi.mock("@/features/auth/store/authStore", () => ({
  useAuthStore: (sel: (s: { accessToken: string | null }) => unknown) => sel({ accessToken: "at" }),
}));

import { nextPollDelay, useQueue } from "@/features/queue/hooks/useQueue";

const waiting = (retryAfterMs?: number) => ({ status: "WAITING", rank: 500, total: 1000, etaSeconds: 10, retryAfterMs });

/** 상태 조회가 불린 시각(가짜 타이머 기준 ms). */
let marks: number[] = [];
let t0 = 0;

describe("useQueue 폴링(서버 retryAfterMs + jitter)", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(Math, "random").mockReturnValue(0); // jitter 0 — 간격을 결정적으로 본다
    marks = [];
    issueQueueToken.mockReset();
    getQueueStatus.mockReset();
    issueQueueToken.mockResolvedValue({ token: "tok-1", status: "WAITING", rank: 500, total: 1000, retryAfterMs: 5_000 });
    getQueueStatus.mockImplementation(async () => {
      marks.push(Date.now() - t0);
      return waiting(2_000);
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  /** 토큰 발급까지 진행시킨다(시간은 소비하지 않고 마이크로태스크만 비운다). */
  async function mount() {
    t0 = Date.now();
    const view = renderHook(() => useQueue(1));
    await vi.advanceTimersByTimeAsync(0);
    return view;
  }

  it("첫 조회는 진입 응답의 retryAfterMs가 지난 뒤에만 한다(조기 요청 0)", async () => {
    await mount();
    await vi.advanceTimersByTimeAsync(4_999);
    expect(marks).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(marks).toEqual([5_000]);
  });

  it("다음 조회 간격은 직전 상태 응답의 retryAfterMs를 따른다", async () => {
    getQueueStatus
      .mockImplementationOnce(async () => (marks.push(Date.now() - t0), waiting(30_000)))
      .mockImplementationOnce(async () => (marks.push(Date.now() - t0), waiting(2_000)));
    await mount();
    await vi.advanceTimersByTimeAsync(5_000 + 30_000 + 2_000);
    expect(marks).toEqual([5_000, 35_000, 37_000]);
  });

  it("retryAfterMs가 없거나 최소(2초)보다 짧으면 2초를 지킨다", async () => {
    issueQueueToken.mockResolvedValue({ token: "tok-1", status: "WAITING", rank: 1, total: 1 }); // 구버전 응답
    getQueueStatus.mockImplementation(async () => (marks.push(Date.now() - t0), waiting(500)));
    await mount();
    await vi.advanceTimersByTimeAsync(6_000);
    expect(marks).toEqual([2_000, 4_000, 6_000]);
  });

  it("입장하면 폴링을 멈추고 admitted가 된다", async () => {
    getQueueStatus.mockImplementationOnce(async () => (marks.push(Date.now() - t0), { status: "ADMITTED", rank: 0, total: 999, etaSeconds: 0, retryAfterMs: 0 }));
    const { result } = await mount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(result.current.phase).toBe("admitted");
    await vi.advanceTimersByTimeAsync(120_000);
    expect(marks).toEqual([5_000]);
  });

  it("QUEUE_EXPIRED면 expired가 되고 멈춘다", async () => {
    getQueueStatus.mockImplementationOnce(async () => {
      marks.push(Date.now() - t0);
      throw new ApiError("QUEUE_EXPIRED", "만료");
    });
    const { result } = await mount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(result.current.phase).toBe("expired");
    await vi.advanceTimersByTimeAsync(120_000);
    expect(marks).toEqual([5_000]);
  });

  it("일시 오류면 2→4→8초로 늘리며 계속 묻고, 성공하면 서버 간격으로 돌아온다", async () => {
    const fail = async () => {
      marks.push(Date.now() - t0);
      throw new Error("network");
    };
    getQueueStatus.mockImplementationOnce(fail).mockImplementationOnce(fail).mockImplementationOnce(fail);
    await mount();
    await vi.advanceTimersByTimeAsync(5_000 + 2_000 + 4_000 + 8_000 + 2_000);
    // 5초(첫 조회 실패) → +2초 실패 → +4초 실패 → +8초 성공(retryAfterMs 2초) → +2초
    expect(marks).toEqual([5_000, 7_000, 11_000, 19_000, 21_000]);
  });
});

describe("useQueue 폴링 — 경계", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(Math, "random").mockReturnValue(0);
    marks = [];
    issueQueueToken.mockReset();
    getQueueStatus.mockReset();
    issueQueueToken.mockResolvedValue({ token: "tok-1", status: "WAITING", rank: 500, total: 1000, retryAfterMs: 5_000 });
    getQueueStatus.mockImplementation(async () => (marks.push(Date.now() - t0), waiting(2_000)));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function mount() {
    t0 = Date.now();
    const view = renderHook(() => useQueue(1));
    await vi.advanceTimersByTimeAsync(0);
    return view;
  }

  it("진입 응답이 바로 ADMITTED면 한 번도 묻지 않는다", async () => {
    issueQueueToken.mockResolvedValue({ token: "tok-1", status: "ADMITTED", rank: 0, total: 0, retryAfterMs: 0 });
    let view!: Awaited<ReturnType<typeof mount>>;
    await act(async () => {
      view = await mount();
    });
    expect(view.result.current.phase).toBe("admitted");
    await vi.advanceTimersByTimeAsync(120_000);
    expect(marks).toEqual([]);
  });

  it("오류 백오프는 성공하면 0으로 돌아가 다음 실패는 다시 2초부터다", async () => {
    const fail = async () => {
      marks.push(Date.now() - t0);
      throw new Error("network");
    };
    getQueueStatus
      .mockImplementationOnce(fail) // 5초 실패 → +2초
      .mockImplementationOnce(fail) // 7초 실패 → +4초
      .mockImplementationOnce(async () => (marks.push(Date.now() - t0), waiting(3_000))) // 11초 성공 → +3초
      .mockImplementationOnce(fail); // 14초 실패 → +2초(초기화됐으면)
    await mount();
    await vi.advanceTimersByTimeAsync(5_000 + 2_000 + 4_000 + 3_000 + 2_000);
    expect(marks).toEqual([5_000, 7_000, 11_000, 14_000, 16_000]);
  });

  it("언마운트하면 예약된 조회를 버린다", async () => {
    const { unmount } = await mount();
    unmount();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(marks).toEqual([]);
  });
});

describe("nextPollDelay", () => {
  it("jitter는 0 이상 20% 미만이고 서버 최소 대기보다 줄지 않는다", () => {
    expect(nextPollDelay(30_000, () => 0)).toBe(30_000);
    expect(nextPollDelay(30_000, () => 0.999999)).toBe(35_999);
    expect(nextPollDelay(undefined, () => 0)).toBe(2_000);
    expect(nextPollDelay(100, () => 0.5)).toBe(2_200);
  });
});
