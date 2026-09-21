/**
 * 운영 화면 쿼리 키.
 *
 * 훅마다 배열 리터럴을 적으면 무효화하는 쪽과 조회하는 쪽이 서로를 모른다. 한 글자만 달라도
 * 무효화가 조용히 빗나가고, 키 구조를 바꾸려면 모든 사용처를 찾아다녀야 한다.
 *
 * 계층을 지켜서 만든다(all → 그룹 → 항목). 그래야 접두사 부분 일치로 하위를 한 번에 무효화할 수 있다.
 * 로그아웃 때 all 하나로 전부 제거하는 것도 이 계층 덕분이다(ADR 없음 — clearUserScopedCache 참고).
 */
export const adminKeys = {
  all: ["admin"] as const,

  dashboard: () => [...adminKeys.all, "dashboard"] as const,

  orders: () => [...adminKeys.all, "orders"] as const,
  orderList: (status: string, page: number, size: number) =>
    [...adminKeys.orders(), { status, page, size }] as const,

  events: () => [...adminKeys.all, "events"] as const,
  eventList: (page: number, size: number) => [...adminKeys.events(), { page, size }] as const,

  dlq: () => [...adminKeys.all, "dlq"] as const,
  dlqList: (status: string, page: number, size: number) =>
    [...adminKeys.dlq(), { status, page, size }] as const,

  alerts: () => [...adminKeys.all, "alerts"] as const,
};
