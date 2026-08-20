/**
 * 테스트용 EventSource. jsdom에는 EventSource가 없고, 있더라도 **끊김을 임의로 만들 수 없다.**
 *
 * <p>이 대역이 필요한 이유가 실제 사건에서 나왔다 — 로컬에서 SSE 단절을 만들려고
 * `page.route(...).abort()` / `context.setOffline(true)` / API 프로세스 강제 종료를 모두 시도했지만
 * 브라우저는 셋 다 단절로 인지하지 못했다(Next dev 프록시가 클라이언트 연결을 붙들었다).
 * 그래서 "끊겼을 때 무엇을 하는가"라는 계약이 검증되지 않은 채 남았고, 그 자리에 결함이 있었다.
 */
export class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;

  /** 생성된 인스턴스들(재연결 여부를 세는 데 쓴다). */
  static instances: FakeEventSource[] = [];

  readyState = FakeEventSource.CONNECTING;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  private listeners = new Map<string, ((e: unknown) => void)[]>();

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, fn: (e: unknown) => void) {
    const arr = this.listeners.get(type) ?? [];
    arr.push(fn);
    this.listeners.set(type, arr);
  }

  close() {
    this.closed = true;
    this.readyState = FakeEventSource.CLOSED;
  }

  // ── 테스트가 조종하는 부분 ─────────────────────────────
  open() {
    this.readyState = FakeEventSource.OPEN;
    this.onopen?.();
  }

  /** 일시적 단절 — 브라우저가 스스로 재연결한다(readyState=CONNECTING). */
  dropTransient() {
    this.readyState = FakeEventSource.CONNECTING;
    this.onerror?.();
  }

  /** 영구 실패 — 브라우저가 재연결을 포기한다(2xx가 아닌 응답 등). */
  failPermanently() {
    this.readyState = FakeEventSource.CLOSED;
    this.onerror?.();
  }

  emit(type: string, data: unknown) {
    for (const fn of this.listeners.get(type) ?? []) fn({ data: JSON.stringify(data) });
  }

  static reset() {
    FakeEventSource.instances = [];
  }
}

export function installFakeEventSource() {
  FakeEventSource.reset();
  (globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource;
}
