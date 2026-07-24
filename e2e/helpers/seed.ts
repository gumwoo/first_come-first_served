import { Page, expect } from "@playwright/test";

/**
 * A기법(실제 API 재사용) 상태 빌드업 — "순간이동".
 * 매 테스트에서 로그인·대기열 UI를 반복하지 않고, 실제 백엔드 API로
 * "회원가입 → 로그인 → 대기열 입장(ADMITTED)" 상태를 미리 만든다.
 * page.request를 쓰므로 로그인 refresh 쿠키가 브라우저 컨텍스트에 저장돼,
 * 이후 페이지 로드 시 AuthBootstrap이 access 토큰을 복원한다(= 로그인 상태).
 *
 * 전제: dev 프로필(auth.phone.mock=true, 코드 123456). 실 SMS 미사용.
 */
const PASSWORD = "Test1234!";

/**
 * 관리자 계정(S07). 백엔드가 기동 시 ADMIN_EMAIL/ADMIN_PASSWORD env로 부트스트랩한 계정.
 * CI는 합성 픽스처(ci.yml env)로 주입. 로컬 실행 시엔 같은 값을 env로 넣어야 admin 테스트가 통과.
 */
export const ADMIN_EMAIL = process.env.ADMIN_EMAIL ?? "admin-e2e@flowticket.local";
export const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? "e2e-admin-secret";

/** 관리자 로그인(A기법). refresh 쿠키가 컨텍스트에 저장돼 이후 네비게이션에서 admin 세션 복원. */
export async function loginAsAdmin(page: Page): Promise<void> {
  const res = await page.request.post("/api/auth/login", {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD, remember: true },
  });
  if (!res.ok()) {
    throw new Error(
      `admin 로그인 실패(${res.status()}). ADMIN_EMAIL/ADMIN_PASSWORD 부트스트랩을 확인하세요.`
    );
  }
}

export type Admitted = { eventId: number; queueToken: string; accessToken: string; email: string };

/** 좌석맵에서 화면(등급 VIP→R→S→A, seatCol 오름차순)이 처음 렌더할 '선택 가능' 좌석. */
export function firstSelectable(
  map: { seats: Array<{ id: number; grade: string; seatCol: number; status: string }> }
): { id: number; grade: string; col: number } {
  const order: Record<string, number> = { VIP: 0, R: 1, S: 2, A: 3 };
  const s = map.seats
    .filter((x) => x.status === "AVAILABLE")
    .sort((a, b) => (order[a.grade] - order[b.grade]) || a.seatCol - b.seatCol)[0];
  if (!s) throw new Error("선택 가능한 좌석 없음");
  return { id: s.id, grade: s.grade, col: s.seatCol };
}

/** 좌석 버튼 title = "<등급> N열 M번" (열은 10석 단위 분할). */
export function seatTitle(grade: string, col: number): string {
  return `${grade} ${Math.floor((col - 1) / 10) + 1}열 ${col}번`;
}

export async function seedAdmittedUser(page: Page): Promise<Admitted> {
  const req = page.request;
  const rnd = `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 90 + 10); // 10자리
  const email = `e2e_${rnd}@test.com`;
  const phone = `010${rnd.slice(-8)}`; // 01[0-9]{9} 형식

  // 1) 휴대폰 인증(mock 코드)
  await req.post("/api/auth/phone/request", { data: { phone } });
  await req.post("/api/auth/phone/verify", { data: { phone, code: "123456" } });

  // 2) 회원가입
  await req.post("/api/auth/signup", {
    data: { email, password: PASSWORD, name: "E2E유저", phone, termsAccepted: true, marketingOptIn: false },
  });

  // 3) 로그인 → refresh 쿠키가 컨텍스트 쿠키잔에 저장(이후 네비게이션에서 전송)
  const loginRes = await req.post("/api/auth/login", {
    data: { email, password: PASSWORD, remember: true },
  });
  const accessToken = (await loginRes.json()).data.accessToken as string;

  // 4) 판매중 + 좌석 여유가 있는 이벤트 선택
  const listRes = await req.get("/api/events?status=ON_SALE&size=20");
  const items = (await listRes.json()).data.items as Array<{ id: number }>;
  // 랜덤 순회로 이벤트를 분산 선택(테스트 간 좌석 충돌·고갈 완화). 충분한 여유(>=8) 있는 것만.
  const shuffled = [...items].sort(() => Math.random() - 0.5);
  let eventId = 0;
  for (const e of shuffled) {
    const map = (await (await req.get(`/api/events/${e.id}/seats`)).json()).data;
    const free = (map.seats ?? []).filter((s: { status: string }) => s.status === "AVAILABLE").length;
    if (free >= 8) {
      eventId = e.id;
      break;
    }
  }
  if (!eventId) throw new Error("판매중이면서 좌석 여유(>=8)가 있는 이벤트를 찾지 못함");

  // 5) 대기열 입장 토큰 발급 후 승격(ADMITTED) 대기
  const tokRes = await req.post(`/api/events/${eventId}/queue/token`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const queueToken = (await tokRes.json()).data.token as string;

  await expect
    .poll(
      async () => {
        const s = await req.get(`/api/queue/status?token=${queueToken}`);
        return (await s.json()).data.status as string;
      },
      { timeout: 15_000, intervals: [400, 600, 1000] }
    )
    .toBe("ADMITTED");

  return { eventId, queueToken, accessToken, email };
}

/** 결제까지 순간이동: seedAdmittedUser → 좌석 1개 선점(API) → 주문 생성(API). 반환 {orderId, eventId, accessToken}. */
export async function seedOrder(
  page: Page
): Promise<{ orderId: number; eventId: number; accessToken: string }> {
  const req = page.request;
  const { eventId, queueToken, accessToken } = await seedAdmittedUser(page);

  const map = (await (await req.get(`/api/events/${eventId}/seats`)).json()).data;
  const seat = firstSelectable(map);

  const holdRes = await req.post(`/api/events/${eventId}/seats/hold`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { seatIds: [seat.id], queueToken },
  });
  const holdId = (await holdRes.json()).data.holdId as number;

  const orderRes = await req.post("/api/orders", {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { holdId },
  });
  const orderId = (await orderRes.json()).data.orderId as number;

  return { orderId, eventId, accessToken };
}

/** 결제 완료(PAID)까지 순간이동: seedOrder → Mock 카드 결제(API). 반환 {orderId, eventId}. */
export async function seedPaidOrder(page: Page): Promise<{ orderId: number; eventId: number }> {
  const { orderId, eventId, accessToken } = await seedOrder(page);
  await page.request.post(`/api/orders/${orderId}/payments`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { method: "card", idempotencyKey: `e2e-pay-${orderId}` },
  });
  return { orderId, eventId };
}
