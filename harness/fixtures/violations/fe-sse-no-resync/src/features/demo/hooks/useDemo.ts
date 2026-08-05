// 위반 fixture: SSE를 열지만 구독 공백을 메우는 경로가 없다.
// onopen 재조회도 없고 폴링도 없어, 끊긴 사이 지나간 이벤트는 영영 반영되지 않는다.
export function useDemo(id: number) {
  const es = new EventSource(`/api/demo/${id}/stream`);
  es.addEventListener("demo.updated", () => {
    /* 상태 갱신 */
  });
  es.onerror = () => {};
  return es;
}
