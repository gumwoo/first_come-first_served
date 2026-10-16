// 위반 fixture: 예외 주석은 달았지만 사유가 비어 있다.
// 사유 자리가 줄 끝에서 끝나므로, 다음 줄의 코드를 사유로 읽으면 안 된다.
// harness:allow-sse-no-resync:
export function useDemo(onMessage: (data: string) => void) {
  const es = new EventSource("/api/sse/demo");
  es.onmessage = (e) => onMessage(e.data);
  return () => es.close();
}
