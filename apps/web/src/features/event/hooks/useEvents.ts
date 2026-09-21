import { useQuery } from "@tanstack/react-query";
import * as eventApi from "@/features/event/api/event";
import { eventKeys, searchKeys } from "@/features/event/queryKeys";

export function usePopular() {
  return useQuery({ queryKey: eventKeys.popular(), queryFn: eventApi.getPopular });
}

export function useRealtimeRanking() {
  return useQuery({
    queryKey: eventKeys.realtimeRanking(),
    queryFn: eventApi.getRealtimeRanking,
    refetchInterval: 60_000, // 실시간: 1분마다 갱신
  });
}

export function usePopularKeywords() {
  return useQuery({ queryKey: searchKeys.popularKeywords(), queryFn: eventApi.getPopularKeywords });
}

export function useEvents(
  params: { page?: number; size?: number; genre?: string; status?: string } = {}
) {
  return useQuery({
    queryKey: eventKeys.list(params),
    queryFn: () => eventApi.listEvents(params),
  });
}

export function useEvent(id: number) {
  return useQuery({
    queryKey: eventKeys.detail(id),
    queryFn: () => eventApi.getEvent(id),
    enabled: Number.isFinite(id),
  });
}

export function useSearch(
  keyword: string,
  filters: { genre?: string; region?: string; status?: string; page?: number } = {}
) {
  // 검색어·필터가 없어도 전체를 페이징 조회(= 전체 공연 둘러보기). 항상 조회.
  return useQuery({
    queryKey: eventKeys.search(keyword, filters),
    queryFn: () => eventApi.searchEvents(keyword, filters),
  });
}
