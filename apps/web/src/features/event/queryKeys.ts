/**
 * 공연·검색 쿼리 키. 사용자에 종속되지 않는 공개 데이터라 로그아웃 때 지우지 않는다.
 * 지워도 정확성에는 문제가 없지만, 로그인 여부와 무관한 목록을 다시 받을 이유가 없다.
 */
export const eventKeys = {
  all: ["events"] as const,

  popular: () => [...eventKeys.all, "popular"] as const,
  realtimeRanking: () => [...eventKeys.all, "ranking", "realtime"] as const,
  list: (params: Record<string, unknown>) => [...eventKeys.all, "list", params] as const,
  detail: (id: number) => [...eventKeys.all, "detail", id] as const,
  search: (keyword: string, filters: Record<string, unknown>) =>
    [...eventKeys.all, "search", keyword, filters] as const,
};

export const searchKeys = {
  all: ["search"] as const,

  popularKeywords: () => [...searchKeys.all, "popular-keywords"] as const,
};
