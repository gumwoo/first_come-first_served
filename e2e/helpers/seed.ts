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

export type Admitted = { eventId: number; queueToken: string; email: string };

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
  const listRes = await req.get("/api/events?status=ON_SALE&size=5");
  const items = (await listRes.json()).data.items as Array<{ id: number }>;
  let eventId = 0;
  for (const e of items) {
    const map = (await (await req.get(`/api/events/${e.id}/seats`)).json()).data;
    if (map.seats?.some((s: { status: string }) => s.status === "AVAILABLE")) {
      eventId = e.id;
      break;
    }
  }
  if (!eventId) throw new Error("판매중이면서 좌석 여유가 있는 이벤트를 찾지 못함");

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

  return { eventId, queueToken, email };
}
