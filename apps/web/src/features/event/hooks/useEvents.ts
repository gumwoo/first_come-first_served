import { useQuery } from "@tanstack/react-query";
import * as eventApi from "@/features/event/api/event";

export function usePopular() {
  return useQuery({ queryKey: ["events", "popular"], queryFn: eventApi.getPopular });
}

export function useEvents(params: { page?: number; size?: number; genre?: string } = {}) {
  return useQuery({
    queryKey: ["events", "list", params],
    queryFn: () => eventApi.listEvents(params),
  });
}

export function useEvent(id: number) {
  return useQuery({
    queryKey: ["events", "detail", id],
    queryFn: () => eventApi.getEvent(id),
    enabled: Number.isFinite(id),
  });
}

export function useSearch(
  keyword: string,
  filters: { genre?: string; status?: string; page?: number } = {}
) {
  return useQuery({
    queryKey: ["events", "search", keyword, filters],
    queryFn: () => eventApi.searchEvents(keyword, filters),
    enabled: keyword.length > 0 || !!filters.genre || !!filters.status,
  });
}
